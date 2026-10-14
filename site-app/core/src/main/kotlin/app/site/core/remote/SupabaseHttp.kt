package app.site.core.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Supabase over plain HTTP: Auth (GoTrue) for phone OTP, PostgREST for tables, Storage, Edge Functions.
 * Adds the anon key and the user's token to every call, refreshes the token before it expires,
 * and retries once after a 401. Only the public anon key ships in the app; never the service key.
 */
class SupabaseHttp(
    baseUrl: String,
    private val anonKey: String,
    private val http: HttpClient,
    private val tokens: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) : RemoteApi, AuthApi {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
    private val refreshLock = Mutex()

    // ---------------------------------------------------------------- auth

    override suspend fun requestOtp(phone: String) {
        send(authed = false) {
            http.post("$base/auth/v1/otp") { common(false); jsonBody(buildJsonObject { put("phone", phone) }) }
        }
    }

    override suspend fun verifyOtp(phone: String, code: String): Tokens {
        val res = send(authed = false) {
            http.post("$base/auth/v1/verify") {
                common(false)
                jsonBody(buildJsonObject { put("type", "sms"); put("phone", phone); put("token", code) })
            }
        }
        return parseTokens(res).also { tokens.save(it) }
    }

    override suspend fun refresh(refreshToken: String): Tokens {
        val res = send(authed = false) {
            http.post("$base/auth/v1/token") {
                common(false)
                parameter("grant_type", "refresh_token")
                jsonBody(buildJsonObject { put("refresh_token", refreshToken) })
            }
        }
        return parseTokens(res).also { tokens.save(it) }
    }

    private suspend fun parseTokens(res: HttpResponse): Tokens {
        val o = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val user = o["user"]?.jsonObject
        val expiresAt = o["expires_at"]?.jsonPrimitive?.long
            ?: (clock.instant().epochSecond + (o["expires_in"]?.jsonPrimitive?.long ?: 3600))
        return Tokens(
            accessToken = o.getValue("access_token").jsonPrimitive.content,
            refreshToken = o.getValue("refresh_token").jsonPrimitive.content,
            expiresAt = expiresAt,
            userId = user?.get("id")?.jsonPrimitive?.content ?: "",
            phone = user?.get("phone")?.jsonPrimitive?.content,
        )
    }

    /** A token valid for at least another minute, refreshing if needed. */
    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val t = tokens.load() ?: throw RemoteError.Unauthorized()
        if (!forceRefresh && t.expiresAt - clock.instant().epochSecond > 60) return t.accessToken
        try {
            refresh(t.refreshToken).accessToken
        } catch (e: RemoteError.Offline) {
            throw e
        } catch (e: RemoteError) {
            tokens.save(null)
            throw RemoteError.Unauthorized()
        }
    }

    // ---------------------------------------------------------------- data

    override suspend fun select(table: String, query: Map<String, String>): JsonArray {
        val res = send { token -> http.get("$base/rest/v1/$table") { common(true, token); query.forEach { (k, v) -> parameter(k, v) } } }
        return json.parseToJsonElement(res.bodyAsText()) as JsonArray
    }

    override suspend fun upsert(table: String, row: JsonObject, onConflict: String?) {
        send { token ->
            http.post("$base/rest/v1/$table") {
                common(true, token)
                if (onConflict != null) parameter("on_conflict", onConflict)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                jsonBody(row)
            }
        }
    }

    override suspend fun update(table: String, filter: Map<String, String>, changes: JsonObject) {
        send { token ->
            http.patch("$base/rest/v1/$table") {
                common(true, token)
                filter.forEach { (k, v) -> parameter(k, v) }
                header("Prefer", "return=minimal")
                jsonBody(changes)
            }
        }
    }

    override suspend fun delete(table: String, filter: Map<String, String>) {
        require(filter.isNotEmpty()) { "refusing an unfiltered delete" }
        send { token -> http.delete("$base/rest/v1/$table") { common(true, token); filter.forEach { (k, v) -> parameter(k, v) } } }
    }

    override suspend fun rpc(function: String, args: JsonObject): JsonElement {
        val res = send { token -> http.post("$base/rest/v1/rpc/$function") { common(true, token); jsonBody(args) } }
        val text = res.bodyAsText()
        return if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    override suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String) {
        send { token ->
            http.post("$base/storage/v1/object/$bucket/$path") {
                common(true, token)
                header("x-upsert", "true")
                contentType(ContentType.parse(contentType))
                setBody(bytes)
            }
        }
    }

    override suspend fun download(bucket: String, path: String): ByteArray {
        val res = send { token -> http.get("$base/storage/v1/object/authenticated/$bucket/$path") { common(true, token) } }
        return res.readBytes()
    }

    override suspend fun function(name: String, body: JsonObject): JsonElement {
        val res = send { token -> http.post("$base/functions/v1/$name") { common(true, token); jsonBody(body) } }
        val text = res.bodyAsText()
        return if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    /** URL and headers for loading a stored photo with an image library. */
    suspend fun authenticatedObject(bucket: String, path: String): Pair<String, Map<String, String>> =
        "$base/storage/v1/object/authenticated/$bucket/$path" to mapOf("apikey" to anonKey, "Authorization" to "Bearer ${accessToken()}")

    // ---------------------------------------------------------------- plumbing

    private fun HttpRequestBuilder.common(authed: Boolean, token: String? = null) {
        header("apikey", anonKey)
        header("Authorization", "Bearer ${if (authed) token else anonKey}")
    }

    private fun HttpRequestBuilder.jsonBody(body: JsonElement) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private suspend fun send(authed: Boolean = true, call: suspend (token: String?) -> HttpResponse): HttpResponse {
        var token = if (authed) accessToken() else null
        var res = transport { call(token) }
        if (authed && res.status.value == 401) {
            token = accessToken(forceRefresh = true)
            res = transport { call(token) }
        }
        if (res.status.isSuccess()) return res
        throw toError(res.status.value, res.bodyAsText())
    }

    /** Anything that stops the request reaching the server (no network, DNS, timeout) counts as offline. */
    private suspend fun transport(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteError) {
            throw e
        } catch (e: Exception) {
            throw RemoteError.Offline(e)
        }

    internal fun toError(status: Int, body: String): RemoteError {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = o?.get("code")?.jsonPrimitive?.content ?: o?.get("error_code")?.jsonPrimitive?.content ?: ""
        val message = o?.get("message")?.jsonPrimitive?.content ?: o?.get("msg")?.jsonPrimitive?.content
            ?: o?.get("error")?.toString() ?: ""
        return when {
            status == 401 -> RemoteError.Unauthorized()
            status >= 500 -> RemoteError.Server(status)
            code == "42501" || status == 403 -> RemoteError.Forbidden(message)
            else -> RemoteError.Rejected(code.ifEmpty { status.toString() }, message)
        }
    }
}
