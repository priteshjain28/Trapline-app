package app.site.core.sync

import app.site.core.data.SyncStatus
import app.site.core.local.SiteDb
import app.site.core.remote.FileStore
import app.site.core.remote.RemoteApi
import app.site.core.remote.RemoteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Keeps the phone's database and Supabase in step.
 *
 * Push: the outbox, oldest first. Offline or a server hiccup stops the run and keeps the change for next time.
 * A permanent refusal (permissions, a check, a plan limit) drops that change and restores the row from the server,
 * so the phone never keeps something the server won't accept.
 *
 * Pull: per table, rows changed since the last cursor (minus a 2-minute overlap for transactions that commit late),
 * then tombstones for deletions. A row with a local change still waiting in the outbox is not overwritten.
 */
class SyncEngine(
    private val db: SiteDb,
    private val api: RemoteApi,
    private val files: FileStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val tables: List<PullSpec>,
    private val tombstoneHandlers: Map<String, (String) -> Unit>,
    private val restore: suspend (table: String, rowId: String) -> Unit,
) {
    private val q = db.siteQueries
    private val lock = Mutex()
    private val _status = MutableStateFlow(SyncStatus(pending = q.pendingCount().executeAsOne()))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var running: Job? = null

    /** A table to pull. [apply] writes one server row into the local database. */
    class PullSpec(val table: String, val columns: String, val apply: (JsonObject) -> Unit, val key: (JsonObject) -> String)

    // ---------------------------------------------------------------- enqueue (call inside the write's transaction)

    fun enqueueUpsert(table: String, rowId: String, row: JsonObject, onConflict: String? = null) =
        q.enqueue(table, rowId, "upsert", row.toString(), onConflict)

    fun enqueueUpdate(table: String, rowId: String, filter: Map<String, String>, changes: JsonObject) =
        q.enqueue(table, rowId, "update", buildJsonObject { put("filter", filter.toJson()); put("changes", changes) }.toString(), null)

    fun enqueueDelete(table: String, rowId: String, filter: Map<String, String>) =
        q.enqueue(table, rowId, "delete", buildJsonObject { put("filter", filter.toJson()) }.toString(), null)

    fun enqueueUpload(rowId: String, bucket: String, path: String, uri: String, contentType: String) =
        q.enqueue("storage", rowId, "upload", buildJsonObject {
            put("bucket", bucket); put("path", path); put("uri", uri); put("content_type", contentType)
        }.toString(), null)

    fun hasPending(table: String, rowId: String): Boolean = q.pendingFor(table, rowId).executeAsOne() > 0

    /** Fire and forget; runs collapse into one. */
    fun requestSync() {
        if (running?.isActive == true) return
        running = scope.launch { sync() }
    }

    suspend fun sync(): SyncStatus = lock.withLock {
        _status.update { it.copy(syncing = true) }
        var offline = false
        try {
            try {
                push()
            } catch (e: RemoteError.Server) {
                // the server choked on a write; still bring other people's changes down (pending rows are protected)
            }
            pull()
            _status.update { it.copy(lastSyncedAt = clock.instant()) }
        } catch (e: RemoteError.Offline) {
            offline = true
        } catch (e: RemoteError.Server) {
            // try again next time
        } catch (e: RemoteError.Unauthorized) {
            // the session layer notices on its next call and signs out
        }
        _status.update { it.copy(syncing = false, offline = offline, pending = q.pendingCount().executeAsOne()) }
        return _status.value
    }

    // ---------------------------------------------------------------- push

    private suspend fun push() {
        while (true) {
            val batch = q.nextOutbox(50).executeAsList()
            if (batch.isEmpty()) return
            for (item in batch) {
                try {
                    send(item.table_name, item.op, item.payload, item.on_conflict)
                    q.doneOutbox(item.seq)
                } catch (e: RemoteError) {
                    if (!e.permanent) {
                        q.failOutbox(e.message, item.seq)
                        throw e
                    }
                    // the server won't take this change: drop it and put the server's version back
                    q.doneOutbox(item.seq)
                    _status.update { it.copy(rejected = it.rejected + 1) }
                    if (item.op != "upload" && !hasPending(item.table_name, item.row_id)) restore(item.table_name, item.row_id)
                }
                _status.update { it.copy(pending = q.pendingCount().executeAsOne()) }
            }
        }
    }

    private suspend fun send(table: String, op: String, payload: String, onConflict: String?) {
        val body = Json.parseToJsonElement(payload).jsonObject
        when (op) {
            "upsert" -> api.upsert(table, body, onConflict)
            "update" -> api.update(table, body.getValue("filter").jsonObject.toFilter(), body.getValue("changes").jsonObject)
            "delete" -> api.delete(table, body.getValue("filter").jsonObject.toFilter())
            "upload" -> {
                val uri = body.getValue("uri").jsonPrimitive.content
                val bytes = files.read(uri) ?: throw RemoteError.Rejected("file_missing", uri)
                api.upload(body.getValue("bucket").jsonPrimitive.content, body.getValue("path").jsonPrimitive.content, bytes,
                    body.getValue("content_type").jsonPrimitive.content)
            }
            else -> error("unknown op $op")
        }
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pull() {
        for (spec in tables) pullTable(spec)
        pullTombstones()
    }

    private suspend fun pullTable(spec: PullSpec) {
        var since = q.cursorFor(spec.table).executeAsOneOrNull()
        repeat(MAX_PAGES) {
            val rows = api.select(spec.table, buildMap {
                put("select", spec.columns)
                put("order", "updated_at.asc")
                put("limit", PAGE.toString())
                since?.let { put("updated_at", "gte.${overlap(it)}") }
            })
            var newest = since
            db.transaction {
                for (el in rows) {
                    val row = el.jsonObject
                    val stamp = row["updated_at"]?.jsonPrimitive?.content
                    if (stamp != null && (newest == null || parseInstant(stamp) > parseInstant(newest!!))) newest = stamp
                    if (!hasPending(spec.table, spec.key(row))) spec.apply(row)
                }
                newest?.let { q.setCursor(spec.table, it) }
            }
            if (rows.size < PAGE || newest == since) return
            since = newest
        }
    }

    private suspend fun pullTombstones() {
        val since = q.cursorFor("tombstones").executeAsOneOrNull()
        val rows = api.select("tombstones", buildMap {
            put("select", "table_name,row_id,deleted_at")
            put("order", "deleted_at.asc")
            put("limit", "1000")
            since?.let { put("deleted_at", "gte.${overlap(it)}") }
        })
        db.transaction {
            var newest = since
            for (el in rows) {
                val row = el.jsonObject
                val table = row.getValue("table_name").jsonPrimitive.content
                val id = row.getValue("row_id").jsonPrimitive.content
                tombstoneHandlers[table]?.invoke(id)
                val stamp = row.getValue("deleted_at").jsonPrimitive.content
                if (newest == null || parseInstant(stamp) > parseInstant(newest!!)) newest = stamp
            }
            newest?.let { q.setCursor("tombstones", it) }
        }
    }

    private fun overlap(stamp: String): String = parseInstant(stamp).minus(OVERLAP).toString()

    companion object {
        const val PAGE = 500
        const val MAX_PAGES = 40
        val OVERLAP: Duration = Duration.ofMinutes(2)

        fun parseInstant(s: String): Instant = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrElse { Instant.parse(s) }
    }
}

private fun Map<String, String>.toJson(): JsonObject = buildJsonObject { forEach { (k, v) -> put(k, v) } }
private fun JsonObject.toFilter(): Map<String, String> = mapValues { it.value.jsonPrimitive.content }
