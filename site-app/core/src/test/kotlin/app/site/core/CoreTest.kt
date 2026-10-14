package app.site.core

import app.site.core.data.AppError
import app.site.core.data.fake.FakeBackend
import app.site.core.logic.Format
import app.site.core.logic.ReportBuilder
import app.site.core.logic.Validate
import app.site.core.logic.Wages
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.NewProject
import app.site.core.model.NewReport
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import app.site.core.model.NewWorker
import app.site.core.model.ReportParts
import app.site.core.model.ReportType
import app.site.core.model.TaskStatus
import app.site.core.model.Worker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormatTest {
    @Test fun indianGrouping() {
        assertEquals("₹0", Format.rupees(0))
        assertEquals("₹800", Format.rupees(80_000))
        assertEquals("₹1,19,999", Format.rupees(11_999_900))
        assertEquals("₹1,00,00,000", Format.rupees(1_000_000_000))
        assertEquals("₹1,234.50", Format.rupees(123_450))
    }

    @Test fun parsing() {
        assertEquals(80_000L, Format.parseRupees("800"))
        assertEquals(120_050L, Format.parseRupees("1,200.5"))
        assertEquals(null, Format.parseRupees("abc"))
        assertEquals(null, Format.parseRupees("-5"))
        assertEquals("8000", Format.rupeesPlain(8_000_00))
        assertEquals("1234.5", Format.rupeesPlain(1_234_50))
        assertEquals(1_234_50L, Format.parseRupees(Format.rupeesPlain(1_234_50)))
        assertEquals("12.5", Format.qty(12.5))
        assertEquals("420", Format.qty(420.0))
    }

    @Test fun initials() {
        assertEquals("RS", Format.initials("Ravi Singh"))
        assertEquals("M", Format.initials("  meena "))
        assertEquals("?", Format.initials(""))
    }
}

class ValidateTest {
    @Test fun phone() {
        assertEquals("+919812345678", Validate.phone("98123 45678").getOrThrow())
        assertEquals("+919812345678", Validate.phone("+91 98123-45678").getOrThrow())
        assertTrue(Validate.phone("12345").isFailure)
        assertTrue(Validate.phone("5812345678").isFailure)
    }

    @Test fun datesCannotRunBackwards() {
        val r = Validate.project(NewProject("Tower", startDate = LocalDate.of(2026, 10, 12), endDate = LocalDate.of(2026, 10, 1)))
        val e = r.exceptionOrNull()
        assertIs<AppError.Invalid>(e)
        assertEquals(AppError.Reason.END_BEFORE_START, e.reason)
    }

    @Test fun longNamesRejected() {
        assertTrue(Validate.project(NewProject("x".repeat(121))).isFailure)
        assertTrue(Validate.project(NewProject("x".repeat(60))).isSuccess)
    }
}

class WagesTest {
    @Test fun matchesTheSchemaExample() {
        // same case as supabase/tests/smoke.sql: 3.5 days x Rs 800 + 2 OT hours x Rs 100 = Rs 3,000
        val w = Worker("w", "p", "Suresh", wagePaise = 80_000, otRatePaise = 10_000)
        val d = LocalDate.of(2026, 10, 5)
        val recs = listOf(
            Attendance("p", "w", d, AttendanceStatus.PRESENT, 2.0),
            Attendance("p", "w", d.plusDays(1), AttendanceStatus.PRESENT),
            Attendance("p", "w", d.plusDays(2), AttendanceStatus.PRESENT),
            Attendance("p", "w", d.plusDays(3), AttendanceStatus.HALF_DAY),
            Attendance("p", "w", d.plusDays(4), AttendanceStatus.ABSENT),
        )
        val line = Wages.lines(listOf(w), recs, 8.0).single()
        assertEquals(3.5, line.days)
        assertEquals(300_000L, line.amountPaise)
    }
}

class FakeBackendTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))
    private fun backend(seed: Boolean = true) = FakeBackend(clock, latencyMs = 0, seed = seed)

    @Test fun otpSignInCreatesSession() = runTest {
        val b = backend()
        assertTrue(b.verifyOtp("9812345678", "000000").isFailure)
        val s = b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        assertEquals("+919812345678", s.user.phone)
        assertEquals("Sharma Constructions", s.org?.name)
    }

    @Test fun seedFeedIsNeverInTheFuture() = runTest {
        val b = backend()
        val tower = b.projects().first().first { it.name == "Green Park Tower B" }
        val now = java.time.Instant.now(clock)
        assertTrue(b.feed(tower.id).first().all { !it.createdAt.isAfter(now) })
    }

    @Test fun seedHasABusySite() = runTest {
        val b = backend()
        val projects = b.projects().first()
        assertEquals(3, projects.size)
        val tower = projects.first { it.name == "Green Park Tower B" }
        assertTrue(tower.progressPct in 1..99)
        assertEquals(4, b.tasks(tower.id).first().size)
        assertTrue(b.tasks(tower.id).first().any { it.isDelayed(b.today()) })
    }

    @Test fun tasksGetNumbersAndProgressRollsUp() = runTest {
        val b = backend(seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP)
        val p = b.create(NewProject("Tower")).getOrThrow()
        val t1 = b.create(NewTask(p.id, "Brickwork", unit = "m2", plannedQty = 100.0)).getOrThrow()
        val t2 = b.create(NewTask(p.id, "Plaster")).getOrThrow()
        assertEquals(1, t1.number)
        assertEquals(2, t2.number)

        b.addUpdate(NewTaskUpdate(t1.id, b.today(), qtyDone = 40.0, remarks = "East wall")).getOrThrow()
        assertEquals(40, b.task(t1.id).first()!!.progressPct)
        assertEquals(TaskStatus.IN_PROGRESS, b.task(t1.id).first()!!.status)
        b.addUpdate(NewTaskUpdate(t1.id, b.today(), qtyDone = 70.0)).getOrThrow()
        assertEquals(100, b.task(t1.id).first()!!.progressPct)
        assertEquals(TaskStatus.COMPLETED, b.task(t1.id).first()!!.status)
        assertEquals(50, b.project(p.id).first()!!.progressPct)
        assertEquals(listOf("CREATED_PROJECT", "CREATED_TASK", "CREATED_TASK", "UPDATED_PROGRESS", "UPDATED_PROGRESS"),
            b.feed(p.id).first().reversed().map { it.verb.name })
    }

    // BUG-001: the demo let a free organisation add a 4th active site; the server refuses it
    @Test fun demoEnforcesTheFreePlanLikeTheServer() = runTest {
        val b = backend(seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP)
        repeat(3) { b.create(NewProject("Site ${'$'}it")).getOrThrow() }
        assertEquals(AppError.PlanLimit("projects"), b.create(NewProject("Site 4")).exceptionOrNull())
    }

    @Test fun demoSeedOrgHasRoomForMoreSites() = runTest {
        val b = backend()
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP)
        assertTrue(b.create(NewProject("Riverside Row Houses")).isSuccess, "the demo org is on a paid plan")
    }

    @Test fun emptyUpdateRejected() = runTest {
        val b = backend()
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP)
        val t = b.tasks(b.projects().first().first().id).first().first()
        assertTrue(b.addUpdate(NewTaskUpdate(t.id, b.today())).isFailure)
    }

    @Test fun attendanceIsOnePerWorkerPerDay() = runTest {
        val b = backend(seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP)
        val p = b.create(NewProject("Tower")).getOrThrow()
        val w = b.addWorker(NewWorker(p.id, "Suresh", wagePaise = 80_000)).getOrThrow()
        b.mark(p.id, w.id, b.today(), AttendanceStatus.PRESENT)
        b.mark(p.id, w.id, b.today(), AttendanceStatus.HALF_DAY)
        val day = b.day(p.id, b.today()).first()
        assertEquals(1, day.size)
        assertEquals(AttendanceStatus.HALF_DAY, day.single().status)
        assertTrue(b.mark(p.id, w.id, b.today().plusDays(1), AttendanceStatus.PRESENT).isFailure, "no future days")
        b.clear(p.id, w.id, b.today())
        assertTrue(b.day(p.id, b.today()).first().isEmpty())
    }

    @Test fun reportsQueueAndExpireIn90Days() = runTest {
        val b = backend()
        val p = b.projects().first().first()
        assertTrue(b.request(NewReport(p.id, ReportType.DETAILED, b.today(), b.today().minusDays(1), ReportParts())).isFailure)
        val r = b.request(NewReport(p.id, ReportType.DETAILED, b.today().minusDays(6), b.today(), ReportParts())).getOrThrow()
        assertEquals(90, java.time.Duration.between(r.createdAt, r.expiresAt).toDays())
        b.markReady(r.id, "/tmp/r.pdf")
        assertNotNull(b.report(r.id).first()?.filePath)
    }

    @Test fun reportContentFromSeed() = runTest {
        val b = backend()
        val p = b.projects().first().first { it.name == "Green Park Tower B" }
        val from = b.today().minusDays(6)
        val to = b.today()
        val tasks = b.tasks(p.id).first()
        val ups = b.updatesInRange(p.id, from, to).first()
        val workers = b.workers(p.id).first()
        val att = b.range(p.id, from, to).first()

        val detailed = ReportBuilder.build(p, ReportType.DETAILED, from, to, ReportParts(), tasks, ups, workers, att, b.people)
        assertFalse(detailed.isEmpty)
        assertTrue(detailed.days.zipWithNext().all { (a, c) -> a.date < c.date }, "days in order")
        assertEquals(4, detailed.totals.updates)
        assertTrue(detailed.days.any { it.attendance != null })

        val summary = ReportBuilder.build(p, ReportType.SUMMARY, from, to, ReportParts(wages = true), tasks, ups, workers, att, b.people)
        assertEquals(2, summary.taskSummary.size)
        assertEquals(5, summary.workerSummary.size)
        assertTrue(summary.totals.wagesPaise > 0)

        val noAttendance = ReportBuilder.build(p, ReportType.DETAILED, from, to, ReportParts(attendance = false), tasks, ups, workers, att, b.people)
        assertTrue(noAttendance.days.all { it.attendance == null })
    }
}
