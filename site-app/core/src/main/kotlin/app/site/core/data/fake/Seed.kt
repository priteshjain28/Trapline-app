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
        val org = Organisation(newId(), "Sharma Constructions")

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
        tasks = tasks.map { if (it.number == 3 && it.projectId == tower.id) it.copy(openIssues = 1) else it }

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
            events += ActivityEvent(newId(), tower.id, meena.id, meena.name, ActivityVerb.CREATED_TASK, it.id, it.name, at(it.startDate ?: today, 9))
        }
        updates.filter { it.projectId == tower.id }.forEach { u ->
            val name = if (u.authorId == ravi.id) ravi.name else meena.name
            events += ActivityEvent(newId(), tower.id, u.authorId, name, ActivityVerb.UPDATED_PROGRESS, u.taskId,
                tasks.first { it.id == u.taskId }.name, u.createdAt)
        }

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
            counters = mapOf("tasks|${tower.id}" to 4, "tasks|${villas.id}" to 1),
        )
    }
}
