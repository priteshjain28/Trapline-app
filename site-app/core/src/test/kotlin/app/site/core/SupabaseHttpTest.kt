package app.site.core

import app.site.core.remote.MemoryTokenStore
import app.site.core.remote.RemoteError
import app.site.core.remote.SupabaseHttp
import app.site.core.remote.Tokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseHttpTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_760_000_000), ZoneOffset.UTC)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun tokens(expiresAt: Long = 1_760_003_600) = MemoryTokenStore(Tokens("old-access", "refresh-1", expiresAt, "u1", "+919876543210"))

    @Test fun sendsKeyAndTokenAndRefreshesOnce401() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            val auth = req.headers["Authorization"].orEmpty()
            seen += "${req.method.value} ${req.url.encodedPath} $auth apikey=${req.headers["apikey"]}"
            when {
                req.url.encodedPath == "/auth/v1/token" -> respond(
                    """{"access_token":"new-access","refresh_token":"refresh-2","expires_in":3600,"user":{"id":"u1","phone":"919876543210"}}""",
                    HttpStatusCode.OK, jsonHeaders)
                auth == "Bearer old-access" -> respondError(HttpStatusCode.Unauthorized, """{"message":"JWT expired"}""")
                else -> respond("""[{"id":"p1"}]""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val store = tokens()
        val api = SupabaseHttp("https://x.supabase.co/", "anon-key", HttpClient(engine), store, clock)
        val rows = api.select("projects", mapOf("select" to "id"))
        assertEquals(1, rows.size)
        assertEquals("new-access", store.load()!!.accessToken, "refreshed token saved")
        assertTrue(seen.all { it.endsWith("apikey=anon-key") }, "anon key on every call")
        assertEquals(listOf("GET /rest/v1/projects Bearer old-access", "POST /auth/v1/token Bearer anon-key", "GET /rest/v1/projects Bearer new-access"),
            seen.map { it.substringBefore(" apikey") })
    }

    @Test fun refreshesBeforeExpiry() = runTest {
        var refreshed = 0
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/auth/v1/token") {
                refreshed++
                respond("""{"access_token":"a2","refresh_token":"r2","expires_at":1760007200,"user":{"id":"u1"}}""", HttpStatusCode.OK, jsonHeaders)
            } else respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        val api = SupabaseHttp("https://x.supabase.co", "k", HttpClient(engine), tokens(expiresAt = 1_760_000_030), clock)
        api.select("projects", emptyMap())
        api.select("tasks", emptyMap())
        assertEquals(1, refreshed, "one refresh, then the new token is reused")
    }

    @Test fun failedRefreshSignsOut() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/auth/v1/token") respondError(HttpStatusCode.BadRequest, """{"error_code":"refresh_token_not_found"}""")
            else respondError(HttpStatusCode.Unauthorized, "{}")
        }
        val store = tokens(expiresAt = 0)
        val api = SupabaseHttp("https://x.supabase.co", "k", HttpClient(engine), store, clock)
        assertFailsWith<RemoteError.Unauthorized> { api.select("projects", emptyMap()) }
        assertNull(store.load(), "tokens cleared")
    }

    @Test fun errorsAreSorted() = runTest {
        var reply: Pair<HttpStatusCode, String> = HttpStatusCode.OK to "[]"
        val engine = MockEngine { respondError(reply.first, reply.second) }
        val api = SupabaseHttp("https://x.supabase.co", "k", HttpClient(engine), tokens(), clock)
        val row = buildJsonObject { put("id", "p1") }

        reply = HttpStatusCode.Forbidden to """{"code":"42501","message":"new row violates row-level security policy"}"""
        assertTrue(assertFailsWith<RemoteError.Forbidden> { api.upsert("projects", row) }.permanent)

        reply = HttpStatusCode.BadRequest to """{"code":"P0001","message":"plan_limit:projects"}"""
        val e = assertFailsWith<RemoteError.Rejected> { api.upsert("projects", row) }
        assertEquals("plan_limit:projects", e.message)

        reply = HttpStatusCode.ServiceUnavailable to "{}"
        assertTrue(!assertFailsWith<RemoteError.Server> { api.upsert("projects", row) }.permanent, "5xx is retried later")
    }

    @Test fun noNetworkIsOffline() = runTest {
        val engine = MockEngine { throw IOException("Unable to resolve host") }
        val api = SupabaseHttp("https://x.supabase.co", "k", HttpClient(engine), tokens(), clock)
        assertFailsWith<RemoteError.Offline> { api.select("projects", emptyMap()) }
    }

    @Test fun upsertAsksForMerge() = runTest {
        var prefer: String? = null
        var conflict: String? = null
        val engine = MockEngine { req ->
            prefer = req.headers["Prefer"]; conflict = req.url.parameters["on_conflict"]
            respond("", HttpStatusCode.Created)
        }
        val api = SupabaseHttp("https://x.supabase.co", "k", HttpClient(engine), tokens(), clock)
        api.upsert("attendance", buildJsonObject { put("status", "present") }, "project_id,worker_id,work_date")
        assertEquals("resolution=merge-duplicates,return=minimal", prefer)
        assertEquals("project_id,worker_id,work_date", conflict)
    }
}
