package app.site.core.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the sync engine needs from the server. [SupabaseHttp] talks to Supabase (PostgREST, Storage, Functions);
 * tests use an in-memory server. Filters use PostgREST syntax, e.g. "id" to "eq.123".
 */
interface RemoteApi {
    suspend fun select(table: String, query: Map<String, String>): JsonArray
    /** Insert or merge. [onConflict] = comma-separated natural key; null = primary key. */
    suspend fun upsert(table: String, row: JsonObject, onConflict: String? = null)
    /** Partial update of existing rows. */
    suspend fun update(table: String, filter: Map<String, String>, changes: JsonObject)
    suspend fun delete(table: String, filter: Map<String, String>)
    suspend fun rpc(function: String, args: JsonObject = JsonObject(emptyMap())): JsonElement
    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String)
    suspend fun download(bucket: String, path: String): ByteArray
    /** An Edge Function, as the signed-in user. */
    suspend fun function(name: String, body: JsonObject): JsonElement
}

interface AuthApi {
    suspend fun requestOtp(phone: String)
    suspend fun verifyOtp(phone: String, code: String): Tokens
    suspend fun refresh(refreshToken: String): Tokens
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    /** epoch seconds */
    val expiresAt: Long,
    val userId: String,
    val phone: String?,
)

/** Where the session lives between launches. Android: app-private SharedPreferences. */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens?)
}

class MemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    override fun load() = tokens
    override fun save(tokens: Tokens?) { this.tokens = tokens }
}

/** Failures from the server, already sorted into what the app should do about them. */
sealed class RemoteError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection or a timeout: keep the change and try again later. */
    class Offline(cause: Throwable? = null) : RemoteError("offline", cause)
    /** Session expired and couldn't be refreshed: sign in again. */
    class Unauthorized : RemoteError("unauthorized")
    /** Row level security said no. Permanent for this change. */
    class Forbidden(message: String) : RemoteError(message)
    /** The database refused the data (check, unique, plan limit...). Permanent for this change. */
    class Rejected(val code: String, message: String) : RemoteError(message)
    /** 5xx: the server's problem, try again later. */
    class Server(val status: Int) : RemoteError("server $status")

    val permanent: Boolean get() = this is Forbidden || this is Rejected
}

/** Reads photos and writes downloaded files. The Android app backs it with its own storage. */
interface FileStore {
    fun read(uri: String): ByteArray?
    /** Saves a downloaded report and returns the local path. */
    fun saveReport(id: String, bytes: ByteArray): String
}
