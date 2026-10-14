package app.site.core.data.fake

import app.site.core.logic.Progress
import app.site.core.model.ActivityEvent
import app.site.core.model.ActivityVerb
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.Organisation
import app.site.core.model.Project
import app.site.core.model.ProjectStatus
import app.site.core.model.Task
import app.site.core.model.TaskUpdate
import app.site.core.model.User
import app.site.core.model.Worker
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Demo data: a busy site, a quiet one and an empty one. Names are invented. */
internal object Seed {
    fun build(clock: Clock, newId: () -> String): FakeBackend.State {
        val today = LocalDate.now(clock)
        val zone: ZoneId = clock.zone
        fun at(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant()

        val ravi = User(newId(), "Ravi Singh", "+919800000001")
        val meena = User(newId(), "Meena Iyer", "+919800000002")
        // on a paid plan, so the demo can still add sites past the free plan's 3
        val org = Organisation(newId(), "Sharma Constructions", app.site.core.model.Plan.PRO)

        val tower = Project(
            newId(), org.id, "Green Park Tower B", ProjectStatus.ACTIVE, "Sector 21, Gurugram",
            today.minusMonths(2).withDayOfMonth(1), today.plusMonths(6),
        )
        val villas = Project(newId(), org.id, "Lakeview Villas", ProjectStatus.ACTIVE, "Whitefield, Bengaluru", today.minusDays(20), null)
        val office = Project(newId(), org.id, "Site office renovation", ProjectStatus.ON_HOLD)

        var tasks = listOf(
            Task(newId(), tower.id, 1, "Excavation and PCC", "Civil", "m3", 420.0, startDate = today.minusDays(55), dueDate = today.minusDays(30)),
            Task(newId(), tower.id, 2, "Brickwork level 3", "Masonry", "m2", 600.0, startDate = today.minusDays(12), dueDate = today.plusDays(10), assigneeId = ravi.id),
            Task(newId(), tower.id, 3, "Plaster level 2", "Finishing", "m2", 900.0, startDate = today.minusDays(20), dueDate = today.minusDays(2), assigneeId = meena.id, hasWorkOrder = true),
            Task(newId(), tower.id, 4, "Electrical conduits level 3", "MEP", startDate = today.plusDays(5), dueDate = today.plusDays(25)),
            Task(newId(), villas.id, 1, "Site clearing", "Civil", startDate = today.minusDays(18), dueDate = today.minusDays(10)),
        )

        val updates = mutableListOf<TaskUpdate>()
        fun update(task: Task, daysAgo: Long, qty: Double?, pct: Int?, remarks: String?, author: User) {
            val d = today.minusDays(daysAgo)
            updates += TaskUpdate(newId(), task.id, task.projectId, author.id, d, qty, pct, remarks, emptyList(), at(d, 17))
            tasks = tasks.map { if (it.id == task.id) Progress.apply(it, qty, pct) else it }
        }
        update(tasks[0], 40, 420.0, null, "PCC done for all footings.", ravi)
        update(tasks[1], 6, 120.0, null, "East wall up to sill level.", ravi)
        update(tasks[1], 3, 95.0, null, null, ravi)
        update(tasks[1], 1, 110.0, null, "Waiting on bricks for the north side.", ravi)
        update(tasks[2], 4, 300.0, null, "Internal plaster, flats 201-204.", meena)
        update(tasks[4], 12, null, 100, "Cleared and levelled.", meena)

        val workers = listOf(
            Worker(newId(), tower.id, "Suresh Kumar", "Mason", "+919811111111", wagePaise = 80_000, otRatePaise = 10_000),
            Worker(newId(), tower.id, "Imran Khan", "Mason", wagePaise = 80_000, otRatePaise = 10_000),
            Worker(newId(), tower.id, "Lakshmi Devi", "Helper", wagePaise = 55_000, otRatePaise = 7_000),
            Worker(newId(), tower.id, "Gopal Yadav", "Helper", wagePaise = 55_000, otRatePaise = 7_000),
            Worker(newId(), tower.id, "Arjun Patil", "Carpenter", wagePaise = 90_000, otRatePaise = 12_000),
            Worker(newId(), villas.id, "Manoj Das", "Helper", wagePaise = 50_000),
        )
        val attendance = mutableMapOf<String, Attendance>()
        for (daysAgo in 1L..6L) {
            val d = today.minusDays(daysAgo)
            if (d.dayOfWeek.value == 7) continue
            workers.filter { it.projectId == tower.id }.forEachIndexed { i, w ->
                val status = when ((i + daysAgo.toInt()) % 7) {
                    0 -> AttendanceStatus.ABSENT
                    3 -> AttendanceStatus.HALF_DAY
                    else -> AttendanceStatus.PRESENT
                }
                val ot = if (status == AttendanceStatus.PRESENT && i < 2 && daysAgo % 2 == 0L) 2.0 else 0.0
                attendance["${tower.id}|${w.id}|$d"] = Attendance(tower.id, w.id, d, status, ot)
            }
        }

        val events = mutableListOf<ActivityEvent>()
        events += ActivityEvent(newId(), tower.id, null, "", ActivityVerb.CREATED_PROJECT, tower.id, tower.name, at(tower.startDate!!, 9))
        events += ActivityEvent(newId(), tower.id, ravi.id, ravi.name, ActivityVerb.JOINED, null, "engineer", at(tower.startDate, 10))
        tasks.filter { it.projectId == tower.id }.forEach {
            events += ActivityEvent(newId(), tower.id, meena.id, meena.name, ActivityVerb.CREATED_TASK, it.id, it.name,
                at(minOf(it.startDate ?: today, today.minusDays(1)).minusDays(1), 9))
        }
        updates.filter { it.projectId == tower.id }.forEach { u ->
            val name = if (u.authorId == ravi.id) ravi.name else meena.name
            events += ActivityEvent(newId(), tower.id, u.authorId, name, ActivityVerb.UPDATED_PROGRESS, u.taskId,
                tasks.first { it.id == u.taskId }.name, u.createdAt)
        }

        // material on the tower: an opening count, two deliveries, two issues
        fun material(name: String, spec: String, uom: String, category: String) =
            app.site.core.model.Material(app.site.core.model.Material.idFor(org.id, name, spec, uom), org.id, name, spec, uom, category)
        val cement = material("Cement", "OPC 53", "bag", "Binders")
        val bricks = material("Red bricks", "Class A", "nos", "Masonry")
        val sand = material("River sand", "", "m3", "Aggregates")
        val steel = material("TMT bar", "Fe 500D 12mm", "kg", "Steel")
        val materials = listOf(cement, bricks, sand, steel)
        fun line(m: app.site.core.model.Material, q: Double) = app.site.core.model.MaterialLine(m.id, q)
        val grns = listOf(
            app.site.core.model.Grn(newId(), tower.id, 1, today.minusDays(12), "CH-1182", null, ravi.id,
                listOf(line(cement, 200.0), line(bricks, 8000.0)), at(today.minusDays(12), 11)),
            app.site.core.model.Grn(newId(), tower.id, 2, today.minusDays(5), "CH-1219", "Two bundles short, supplier to send Monday", meena.id,
                listOf(line(sand, 12.0), line(steel, 1500.0)), at(today.minusDays(5), 15)),
        )
        val brickwork = tasks.first { it.projectId == tower.id && it.number == 2 }
        val plaster = tasks.first { it.projectId == tower.id && it.number == 3 }
        val issues = listOf(
            app.site.core.model.MaterialIssue(newId(), tower.id, 1, brickwork.id, today.minusDays(6), ravi.id,
                listOf(line(cement, 60.0), line(bricks, 5200.0)), at(today.minusDays(6), 9)),
            app.site.core.model.MaterialIssue(newId(), tower.id, 2, plaster.id, today.minusDays(2), meena.id,
                listOf(line(cement, 45.0), line(sand, 6.0)), at(today.minusDays(2), 9)),
        )
        val opening = FakeBackend.Adjustment(newId(), tower.id, cement.id, app.site.core.model.StockEntryKind.OPENING, 40.0,
            today.minusDays(30), null, "Left from the slab pour", at(today.minusDays(30), 18))

        // issues: one open on the plaster (behind schedule), one closed
        val plasterTask = tasks.first { it.projectId == tower.id && it.number == 3 }
        val crack = app.site.core.model.Issue(newId(), tower.id, 1, "Hairline cracks in plaster near the lift lobby",
            "Second floor, both sides of the lift. Check before the next coat.", plasterTask.id, today.plusDays(2), meena.id,
            raisedBy = ravi.id, createdAt = at(today.minusDays(3), 11))
        val water = app.site.core.model.Issue(newId(), tower.id, 2, "Water tanker late two days running", null, null, null, null,
            app.site.core.model.IssueStatus.CLOSED, ravi.id, at(today.minusDays(9), 8), at(today.minusDays(8), 16))
        val crackReply = app.site.core.model.Comment(newId(), crack.id, meena.id, "Seen it. Mason will cut and refill on Thursday.",
            at(today.minusDays(2), 10))
        events += ActivityEvent(newId(), tower.id, ravi.id, ravi.name, ActivityVerb.RAISED_ISSUE, crack.id, crack.title, crack.createdAt)

        // money: a part-paid crane bill and a petty-cash spend on the tower
        val crane = app.site.core.model.Payable(newId(), tower.id, 1, app.site.core.model.PayableKind.CUSTOM, 18_000_00,
            title = "Crane hire, 2 days", createdAt = at(today.minusDays(5), 12))
        val craneAdvance = app.site.core.model.Payment(newId(), crane.id, 10_000_00, today.minusDays(5), app.site.core.model.PaymentMode.UPI,
            "UPI ref 4021", at(today.minusDays(5), 12))
        val tea = app.site.core.model.Expense(newId(), tower.id, app.site.core.model.ExpenseCategory.PETTY_SPEND, 1_250_00, today.minusDays(3),
            "Tea and snacks for the slab crew", at(today.minusDays(3), 17))

        val projects = listOf(tower, villas, office).map { p ->
            p.copy(progressPct = Progress.project(tasks.filter { it.projectId == p.id }))
        }
        return FakeBackend.State(
            users = listOf(ravi, meena).associateBy { it.id },
            org = org,
            projects = projects.associateBy { it.id },
            tasks = tasks.associateBy { it.id },
            updates = updates.associateBy { it.id },
            workers = workers.associateBy { it.id },
            attendance = attendance,
            events = events,
            counters = mapOf("tasks|${tower.id}" to 4, "tasks|${villas.id}" to 1, "grns|${tower.id}" to 2, "issues|${tower.id}" to 2, "site-issues|${tower.id}" to 2, "payables|${tower.id}" to 1),
            payables = mapOf(crane.id to crane),
            payments = mapOf(craneAdvance.id to craneAdvance),
            expenses = mapOf(tea.id to tea),
            siteIssues = listOf(crack, water).associateBy { it.id },
            comments = mapOf(crackReply.id to crackReply),
            materials = materials.associateBy { it.id },
            grns = grns.associateBy { it.id },
            issues = issues.associateBy { it.id },
            adjustments = mapOf(opening.id to opening),
            projectMembers = mapOf(
                tower.id to mapOf(ravi.id to app.site.core.model.ProjectRole.ENGINEER, meena.id to app.site.core.model.ProjectRole.SUPERVISOR),
                villas.id to mapOf(meena.id to app.site.core.model.ProjectRole.MANAGER),
            ),
        )
    }
}
