package app.site.core

import app.site.core.remote.AuthApi
import app.site.core.remote.FileStore
import app.site.core.remote.RemoteApi
import app.site.core.remote.RemoteError
import app.site.core.remote.TokenStore
import app.site.core.remote.Tokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * An in-memory Supabase for sync tests: PostgREST-style filters, natural-key merges, per-project numbering,
 * server-side progress roll-up, tombstones and file storage. Behaviour mirrors the migrations, not their SQL.
 */
class FakeServer {
    val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    val files = mutableMapOf<String, ByteArray>()
    private val tombstones = mutableListOf<JsonObject>()
    private var tick = 0L
    private val counters = mutableMapOf<String, Int>()
    /** Return an error to refuse a write: (table, op, row). */
    var reject: (String, String, JsonObject) -> RemoteError? = { _, _, _ -> null }

    fun stamp(): String = Instant.parse("2026-10-14T00:00:00Z").plusSeconds(++tick).toString()
    fun table(name: String) = tables.getOrPut(name) { linkedMapOf() }
    fun rows(name: String): List<JsonObject> = table(name).values.toList()

    // ---------------------------------------------------------------- users

    fun userFor(phone: String): String {
        table("profiles").values.firstOrNull { it["phone"]?.jsonPrimitive?.contentOrNull == phone }?.let { return it.s("id")!! }
        val id = UUID.randomUUID().toString()
        put("profiles", buildJsonObject { put("id", id); put("name", ""); put("phone", phone) })
        return id
    }

    fun createOrg(userId: String, name: String): String {
        val orgId = UUID.randomUUID().toString()
        put("organisations", buildJsonObject { put("id", orgId); put("name", name); put("plan", "free") })
        put("org_members", buildJsonObject { put("id", UUID.randomUUID().toString()); put("org_id", orgId); put("user_id", userId); put("role", "owner") })
        return orgId
    }

    fun addMember(orgId: String, userId: String) =
        put("org_members", buildJsonObject { put("id", UUID.randomUUID().toString()); put("org_id", orgId); put("user_id", userId); put("role", "member") })

    private fun put(table: String, row: JsonObject): JsonObject {
        val withStamp = JsonObject(row + ("updated_at" to JsonPrimitive(stamp())))
        table(table)[withStamp.s("id") ?: withStamp.s("project_id")!!] = withStamp
        return withStamp
    }

    // ---------------------------------------------------------------- rules the migrations implement

    private fun afterWrite(table: String, row: JsonObject) {
        when (table) {
            "projects" -> if (table("project_settings")[row.s("id")] == null) {
                put("project_settings", buildJsonObject { put("project_id", row.s("id")!!); put("shift_hours", 8.0); put("attendance_remarks", true) })
            }
            "task_updates" -> recompute(row.s("task_id")!!)
        }
    }

    private fun recompute(taskId: String) {
        val t = table("tasks")[taskId] ?: return
        val ups = rows("task_updates").filter { it.s("task_id") == taskId }
        val done = ups.sumOf { it.d("qty_done") ?: 0.0 }
        val planned = t.d("planned_qty")
        val pct = if (planned != null && ups.any { it.d("qty_done") != null }) minOf(100, (done / planned * 100).roundToInt())
        else ups.filter { it.d("progress_pct") != null }.maxByOrNull { it.s("work_date") + it.s("created_at") }?.d("progress_pct")?.toInt() ?: 0
        val status = when { pct >= 100 -> "completed"; pct > 0 || done > 0 -> "in_progress"; else -> "not_started" }
        put("tasks", JsonObject(t + mapOf("done_qty" to JsonPrimitive(done), "progress_pct" to JsonPrimitive(pct), "status" to JsonPrimitive(status))))
        val pid = t.s("project_id")!!
        table("projects")[pid]?.let { p ->
            val avg = rows("tasks").filter { it.s("project_id") == pid }.map { it.d("progress_pct") ?: 0.0 }.average()
            put("projects", JsonObject(p + ("progress_pct" to JsonPrimitive(avg))))
        }
    }

    private fun defaults(table: String, row: JsonObject, existing: JsonObject?): JsonObject {
        val m = row.toMutableMap()
        if (existing == null) {
            m.putIfAbsent("id", JsonPrimitive(UUID.randomUUID().toString()))
            m.putIfAbsent("created_at", JsonPrimitive(stamp()))
            when (table) {
                "tasks" -> {
                    val key = "tasks|" + row.s("project_id")
                    counters[key] = (counters[key] ?: 0) + 1
                    m["number"] = JsonPrimitive(counters[key])
                    m.putIfAbsent("status", JsonPrimitive("not_started")); m.putIfAbsent("done_qty", JsonPrimitive(0)); m.putIfAbsent("progress_pct", JsonPrimitive(0))
                    m.putIfAbsent("category", JsonPrimitive("General"))
                }
                "grns", "material_issues", "issues" -> {
                    val key = table + "|" + row.s("project_id")
                    counters[key] = (counters[key] ?: 0) + 1
                    m["number"] = JsonPrimitive(counters[key])
                    if (table == "issues") m.putIfAbsent("status", JsonPrimitive("open"))
                }
                "projects" -> { m.putIfAbsent("status", JsonPrimitive("active")); m.putIfAbsent("progress_pct", JsonPrimitive(0)) }
                "reports" -> m["expires_at"] = JsonPrimitive(Instant.parse(m["created_at"]!!.jsonPrimitive.content).plusSeconds(90L * 86400).toString())
                "workers" -> m.putIfAbsent("active", JsonPrimitive(true))
            }
        }
        return JsonObject(m)
    }

    // ---------------------------------------------------------------- PostgREST-ish

    fun select(table: String, query: Map<String, String>): JsonArray {
        val src = if (table == "tombstones") tombstones else rows(table)
        val stampCol = if (table == "tombstones") "deleted_at" else "updated_at"
        var out = src.filter { row -> query.all { (k, v) -> k in setOf("select", "order", "limit") || matches(row, k, v) } }
        out = out.sortedBy { Instant.parse(it.s(stampCol) ?: "1970-01-01T00:00:00Z") }
        query["limit"]?.toInt()?.let { out = out.take(it) }
        if (query["select"]?.contains("organisations(") == true) {
            out = out.map { m -> JsonObject(m + ("organisations" to (table("organisations")[m.s("org_id")] ?: JsonNull))) }
        }
        return JsonArray(out)
    }

    private fun matches(row: JsonObject, col: String, cond: String): Boolean {
        val (op, value) = cond.split(".", limit = 2)
        val actual = row.s(col)
        return when (op) {
            "eq" -> actual == value
            "gte" -> actual != null && Instant.parse(actual) >= Instant.parse(value)
            "gt" -> actual != null && Instant.parse(actual) > Instant.parse(value)
            else -> error("filter $op not in the fake")
        }
    }

    fun upsert(table: String, row: JsonObject, onConflict: String?) {
        reject(table, "upsert", row)?.let { throw it }
        val keys = onConflict?.split(",") ?: listOf(if (table == "project_settings") "project_id" else "id")
        val existing = table(table).values.firstOrNull { e -> keys.all { k -> e.s(k) != null && e.s(k) == row.s(k) } }
        val merged = defaults(table, JsonObject((existing ?: emptyMap()) + row), existing)
        val stored = put(table, merged)
        if (existing != null && existing.s("id") != stored.s("id")) table(table).remove(existing.s("id"))
        afterWrite(table, stored)
    }

    fun update(table: String, filter: Map<String, String>, changes: JsonObject) {
        reject(table, "update", changes)?.let { throw it }
        rows(table).filter { r -> filter.all { (k, v) -> matches(r, k, v) } }.forEach { r ->
            val stored = put(table, JsonObject(r + changes))
            afterWrite(table, stored)
        }
    }

    fun delete(table: String, filter: Map<String, String>) {
        reject(table, "delete", JsonObject(emptyMap()))?.let { throw it }
        rows(table).filter { r -> filter.all { (k, v) -> matches(r, k, v) } }.forEach { r ->
            table(table).remove(r.s("id"))
            tombstones += buildJsonObject { put("table_name", table); put("row_id", r.s("id")!!); put("deleted_at", stamp()) }
            if (table == "task_updates") recompute(r.s("task_id")!!)
        }
    }

    /** One phone's connection: its own offline switch and signed-in user. */
    inner class Device(private val tokens: TokenStore) : RemoteApi, AuthApi {
        var offline = false
        private fun net() { if (offline) throw RemoteError.Offline() }
        private fun uid() = tokens.load()?.userId ?: throw RemoteError.Unauthorized()

        override suspend fun select(table: String, query: Map<String, String>) = net().let { this@FakeServer.select(table, query) }
        override suspend fun upsert(table: String, row: JsonObject, onConflict: String?) { net(); uid(); this@FakeServer.upsert(table, row, onConflict) }
        override suspend fun update(table: String, filter: Map<String, String>, changes: JsonObject) { net(); uid(); this@FakeServer.update(table, filter, changes) }
        override suspend fun delete(table: String, filter: Map<String, String>) { net(); uid(); this@FakeServer.delete(table, filter) }
        override suspend fun rpc(function: String, args: JsonObject): JsonElement {
            net()
            return when (function) {
                "accept_invites" -> {
                    // same as public.accept_invites(): pending invites for this phone become memberships
                    val me = uid()
                    val phone = table("profiles")[me]?.s("phone")
                    val pending = rows("invites").filter { it.s("phone") == phone && it.s("accepted_at") == null }
                    for (i in pending) {
                        if (rows("org_members").none { it.s("org_id") == i.s("org_id") && it.s("user_id") == me }) {
                            put("org_members", buildJsonObject {
                                put("id", UUID.randomUUID().toString()); put("org_id", i.s("org_id")); put("user_id", me); put("role", i.s("org_role") ?: "member")
                            })
                        }
                        i.s("project_id")?.let { pid ->
                            if (rows("project_members").none { it.s("project_id") == pid && it.s("user_id") == me }) {
                                put("project_members", buildJsonObject {
                                    put("id", UUID.randomUUID().toString()); put("project_id", pid); put("user_id", me); put("role", i.s("project_role") ?: "engineer")
                                })
                            }
                        }
                        put("invites", JsonObject(i + ("accepted_at" to JsonPrimitive(stamp()))))
                    }
                    JsonPrimitive(pending.size)
                }
                "create_organisation" -> JsonPrimitive(createOrg(uid(), args.s("p_name")!!))
                else -> error("rpc $function not in the fake")
            }
        }
        override suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String) { net(); files["$bucket/$path"] = bytes }
        override suspend fun download(bucket: String, path: String): ByteArray { net(); return files["$bucket/$path"] ?: throw RemoteError.Rejected("404", "not found") }
        override suspend fun function(name: String, body: JsonObject): JsonElement {
            net()
            if (name == "delete-account") table("profiles").remove(uid())
            if (name == "invite") {
                val id = UUID.randomUUID().toString()
                put("invites", buildJsonObject {
                    put("id", id); put("org_id", body.s("org_id")); put("project_id", body.s("project_id")); put("phone", body.s("phone"))
                    put("org_role", body.s("org_role") ?: "member"); put("project_role", body.s("project_role")); put("invited_by", uid())
                    put("created_at", stamp())
                })
                return buildJsonObject { put("invite_id", id); put("sms_sent", false) }
            }
            return JsonNull
        }

        override suspend fun requestOtp(phone: String) = net()
        override suspend fun verifyOtp(phone: String, code: String): Tokens {
            net()
            if (code != "123456") throw RemoteError.Rejected("otp_expired", "Token has expired or is invalid")
            return Tokens("access-$phone", "refresh-$phone", Long.MAX_VALUE, userFor(phone), phone).also { tokens.save(it) }
        }
        override suspend fun refresh(refreshToken: String): Tokens = error("not needed")
    }
}

class MemoryFiles(private val dir: File) : FileStore {
    val content = mutableMapOf<String, ByteArray>()
    override fun read(uri: String): ByteArray? = content[uri] ?: File(uri).takeIf { it.exists() }?.readBytes()
    override fun saveReport(id: String, bytes: ByteArray): String = File(dir, "$id.pdf").also { it.writeBytes(bytes) }.absolutePath
}

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
