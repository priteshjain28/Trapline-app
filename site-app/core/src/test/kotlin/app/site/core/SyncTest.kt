package app.site.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.site.core.data.AppError
import app.site.core.data.synced.SyncedBackend
import app.site.core.local.SiteDb
import app.site.core.model.AttendanceStatus
import app.site.core.model.NewProject
import app.site.core.model.NewReport
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import app.site.core.model.NewWorker
import app.site.core.model.ReportParts
import app.site.core.model.ReportStatus
import app.site.core.model.ReportType
import app.site.core.model.TaskStatus
import app.site.core.remote.MemoryTokenStore
import app.site.core.remote.RemoteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))
    private val server = FakeServer()
    private val tmp: File = Files.createTempDirectory("sync").toFile()

    /** A phone: its own database, token store and connection. Background sync never runs on its own here. */
    private inner class Phone(phone: String) {
        val tokens = MemoryTokenStore()
        val net = server.Device(tokens)
        val files = MemoryFiles(tmp)
        val db = SiteDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SiteDb.Schema.create(it) })
        val app = SyncedBackend(db, net, net, tokens, files, CoroutineScope(StandardTestDispatcher()), clock, Dispatchers.Unconfined)
        val number = phone
        suspend fun signIn(name: String = "Anita", org: String? = "Sharma Constructions") {
            app.verifyOtp(number, "123456").getOrThrow()
            if (app.session.value!!.needsSetup) app.completeProfile(name, org).getOrThrow()
        }
        suspend fun sync() = app.engine.sync()
        fun pending() = db.siteQueries.pendingCount().executeAsOne()
    }

    @Test fun signInCreatesOrgAndWrongCodeIsWorded() = runTest {
        val a = Phone("9876543210")
        val bad = a.app.verifyOtp("9876543210", "000000").exceptionOrNull()
        assertIs<AppError.Invalid>(bad)
        assertEquals(AppError.Reason.WRONG_CODE, bad.reason)
        a.app.verifyOtp("98765 43210", "123456").getOrThrow()
        assertTrue(a.app.session.value!!.needsSetup, "new user: no name, no org")
        val s = a.app.completeProfile("Anita Rao", "Sharma Constructions").getOrThrow()
        assertEquals("Sharma Constructions", s.org?.name)
        assertEquals("Anita Rao", server.rows("profiles").single().s("name"))
    }

    @Test fun worksOfflineThenSyncs() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        a.net.offline = true
        val p = a.app.create(NewProject("Green Park Tower B")).getOrThrow()
        val t = a.app.create(NewTask(p.id, "Brickwork", unit = "m2", plannedQty = 200.0)).getOrThrow()
        a.files.content["content://photo-1"] = byteArrayOf(1, 2, 3)
        a.app.addUpdate(NewTaskUpdate(t.id, a.app.today(), qtyDone = 50.0, remarks = "East wall", photos = listOf("content://photo-1"))).getOrThrow()

        // everything is on the phone already; the number waits for the server
        assertEquals(null, a.app.task(t.id).first()!!.number)
        assertEquals(25, a.app.task(t.id).first()!!.progressPct, "optimistic roll-up, same rule as the server")
        assertEquals(listOf("content://photo-1"), a.app.updates(t.id).first().single().photos)
        assertEquals(5, a.pending(), "project, task, update, photo upload, media row")
        assertTrue(a.sync().offline)
        assertEquals(5, a.pending(), "nothing lost while offline")

        a.net.offline = false
        a.sync()
        assertEquals(0, a.pending())
        assertEquals(1, a.app.task(t.id).first()!!.number, "number assigned by the server")
        assertEquals(25.0, server.table("tasks")[t.id]!!.d("progress_pct"))
        assertTrue(server.files.keys.single().startsWith("project-media/${p.id}/"), "photo uploaded under the project")
        assertEquals(server.files.keys.single().removePrefix("project-media/"), server.rows("media").single().s("storage_path"))
        assertEquals(listOf("content://photo-1"), a.app.updates(t.id).first().single().photos, "this phone keeps showing its own file")
    }

    @Test fun twoPhonesShareASite() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        val b = Phone("9876500000")
        b.signIn(name = "Ravi", org = null)
        // B joins A's organisation (the invite flow on the server)
        val orgId = server.rows("organisations").first().s("id")!!
        server.tables["org_members"]!!.values.removeIf { it.s("user_id") == server.rows("profiles").first { p -> p.s("name") == "Ravi" }.s("id") }
        server.addMember(orgId, server.rows("profiles").first { it.s("name") == "Ravi" }.s("id")!!)

        val p = a.app.create(NewProject("Tower")).getOrThrow()
        val w = a.app.addWorker(NewWorker(p.id, "Suresh", wagePaise = 80_000)).getOrThrow()
        val t = a.app.create(NewTask(p.id, "Brickwork", unit = "m2", plannedQty = 100.0)).getOrThrow()
        a.sync(); b.sync()
        assertEquals(listOf("Tower"), b.app.projects().first().map { it.name })

        // both log work on the same task: quantities add up, nobody's work is lost
        a.app.addUpdate(NewTaskUpdate(t.id, a.app.today(), qtyDone = 30.0))
        b.app.addUpdate(NewTaskUpdate(t.id, b.app.today(), qtyDone = 20.0))
        a.sync(); b.sync(); a.sync()
        assertEquals(50, a.app.task(t.id).first()!!.progressPct)
        assertEquals(50, b.app.task(t.id).first()!!.progressPct)

        // both mark the same worker the same day: one record, the later write wins, ids converge
        a.app.mark(p.id, w.id, a.app.today(), AttendanceStatus.PRESENT)
        b.app.mark(p.id, w.id, b.app.today(), AttendanceStatus.HALF_DAY, otHours = 1.0)
        a.sync(); b.sync(); a.sync()
        assertEquals(1, server.rows("attendance").size)
        assertEquals(AttendanceStatus.HALF_DAY, a.app.day(p.id, a.app.today()).first().single().status)
        assertEquals(1, a.db.siteQueries.attendanceOn(p.id, a.app.today().toString()).executeAsList().size)
        assertEquals(server.rows("attendance").single().s("id"), a.db.siteQueries.attendanceOn(p.id, a.app.today().toString()).executeAsOne().id)

        // A clears it; B hears about it through a tombstone
        a.app.clear(p.id, w.id, a.app.today())
        a.sync(); b.sync()
        assertTrue(b.app.day(p.id, b.app.today()).first().isEmpty())
    }

    @Test fun aPendingLocalChangeIsNotOverwrittenByAPull() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        val p = a.app.create(NewProject("Tower")).getOrThrow()
        val w = a.app.addWorker(NewWorker(p.id, "Suresh")).getOrThrow()
        a.app.mark(p.id, w.id, a.app.today(), AttendanceStatus.PRESENT)
        a.sync()
        // the server changes (another phone), while this phone holds a newer local mark that can't upload yet
        server.upsert("attendance", kotlinx.serialization.json.buildJsonObject {
            put("project_id", kotlinx.serialization.json.JsonPrimitive(p.id)); put("worker_id", kotlinx.serialization.json.JsonPrimitive(w.id))
            put("work_date", kotlinx.serialization.json.JsonPrimitive(a.app.today().toString())); put("status", kotlinx.serialization.json.JsonPrimitive("absent"))
        }, "project_id,worker_id,work_date")
        server.reject = { table, _, _ -> if (table == "attendance") RemoteError.Server(503) else null }
        a.app.mark(p.id, w.id, a.app.today(), AttendanceStatus.HALF_DAY)
        a.sync()
        assertEquals(AttendanceStatus.HALF_DAY, a.app.day(p.id, a.app.today()).first().single().status, "local change kept")
        server.reject = { _, _, _ -> null }
        a.sync()
        assertEquals("half_day", server.rows("attendance").single().s("status"), "and it reaches the server later")
    }

    @Test fun refusedChangesAreUndoneOnThePhone() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        val p = a.app.create(NewProject("Tower")).getOrThrow()
        val t = a.app.create(NewTask(p.id, "Brickwork", unit = "m2", plannedQty = 100.0)).getOrThrow()
        a.sync()
        server.reject = { table, _, _ -> if (table == "task_updates") RemoteError.Forbidden("new row violates row-level security policy") else null }
        a.app.addUpdate(NewTaskUpdate(t.id, a.app.today(), qtyDone = 40.0))
        assertEquals(40, a.app.task(t.id).first()!!.progressPct)
        val status = a.sync()
        assertEquals(1, status.rejected)
        assertEquals(0, a.pending())
        assertTrue(a.app.updates(t.id).first().isEmpty(), "the refused update is gone")
        // and the free-plan limit is checked on the phone before anything is queued
        a.app.create(NewProject("Two")).getOrThrow(); a.app.create(NewProject("Three")).getOrThrow()
        assertEquals(AppError.PlanLimit("projects"), a.app.create(NewProject("Four")).exceptionOrNull())
    }

    @Test fun reportsUploadAndDownloadOnAnotherPhone() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        val p = a.app.create(NewProject("Tower")).getOrThrow()
        val r = a.app.request(NewReport(p.id, ReportType.DETAILED, a.app.today().minusDays(6), a.app.today(), ReportParts())).getOrThrow()
        val pdf = File(tmp, "made-here.pdf").apply { writeBytes("%PDF-1.4 test".toByteArray()) }
        a.app.markReady(r.id, pdf.absolutePath)
        a.sync()
        assertEquals("ready", server.table("reports")[r.id]!!.s("status"))
        assertEquals("${p.id}/${r.id}.pdf", server.table("reports")[r.id]!!.s("storage_path"))
        assertNotNull(server.files["reports/${p.id}/${r.id}.pdf"])

        val b = Phone("9876543210").apply { signIn() }    // same person, second phone
        b.sync()
        val onB = b.app.reports(p.id).first().single()
        assertEquals(ReportStatus.READY, onB.status)
        assertNull(onB.filePath, "not downloaded yet")
        val path = b.app.fetchFile(r.id).getOrThrow()
        assertEquals("%PDF-1.4 test", File(path).readText())
    }

    @Test fun signOutWipesThePhone() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        a.app.create(NewProject("Tower")).getOrThrow()
        a.sync()
        a.app.signOut()
        assertNull(a.app.session.value)
        assertTrue(a.app.projects().first().isEmpty())
        assertNull(a.tokens.load())
    }

    @Test fun taskStatusFollowsTheServerRule() = runTest {
        val a = Phone("9876543210").apply { signIn() }
        val p = a.app.create(NewProject("Tower")).getOrThrow()
        val t = a.app.create(NewTask(p.id, "Snagging")).getOrThrow()
        a.app.addUpdate(NewTaskUpdate(t.id, a.app.today().minusDays(1), progressPct = 70))
        a.app.addUpdate(NewTaskUpdate(t.id, a.app.today(), progressPct = 100))
        assertEquals(TaskStatus.COMPLETED, a.app.task(t.id).first()!!.status)
        a.sync()
        assertEquals("completed", server.table("tasks")[t.id]!!.s("status"))
    }
}
