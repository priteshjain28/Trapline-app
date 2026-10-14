package app.site.core.logic

import app.site.core.data.AppError
import app.site.core.data.AppError.Reason
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.NewProject
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import app.site.core.model.NewWorker
import app.site.core.model.Task
import app.site.core.model.TaskStatus
import app.site.core.model.WageType
import app.site.core.model.Worker
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Input checks shared by every screen and the data layer. They mirror the schema's check constraints. */
object Validate {
    const val NAME_MAX = 120
    const val TASK_NAME_MAX = 200

    fun phone(raw: String): Result<String> {
        val digits = raw.filter { it.isDigit() }.removePrefix("91").takeLast(10)
        return if (digits.length == 10 && digits.first() in '6'..'9') Result.success("+91$digits")
        else Result.failure(AppError.Invalid("phone", Reason.BAD_PHONE))
    }

    fun project(p: NewProject): Result<NewProject> = when {
        p.name.isBlank() -> fail("name", Reason.REQUIRED)
        p.name.trim().length > NAME_MAX -> fail("name", Reason.TOO_LONG)
        endsBeforeStart(p.startDate, p.endDate) -> fail("endDate", Reason.END_BEFORE_START)
        else -> Result.success(p.copy(name = p.name.trim(), address = p.address?.trim()?.ifBlank { null }))
    }

    fun task(t: NewTask): Result<NewTask> = when {
        t.name.isBlank() -> fail("name", Reason.REQUIRED)
        t.name.trim().length > TASK_NAME_MAX -> fail("name", Reason.TOO_LONG)
        t.plannedQty != null && t.plannedQty <= 0 -> fail("plannedQty", Reason.NOT_POSITIVE)
        endsBeforeStart(t.startDate, t.dueDate) -> fail("dueDate", Reason.END_BEFORE_START)
        else -> Result.success(t.copy(name = t.name.trim(), unit = t.unit?.trim()?.ifBlank { null }))
    }

    fun taskUpdate(u: NewTaskUpdate): Result<NewTaskUpdate> = when {
        u.qtyDone == null && u.progressPct == null && u.remarks.isNullOrBlank() && u.photos.isEmpty() ->
            fail("update", Reason.REQUIRED)
        u.qtyDone != null && u.qtyDone < 0 -> fail("qtyDone", Reason.NOT_POSITIVE)
        u.progressPct != null && u.progressPct !in 0..100 -> fail("progressPct", Reason.NOT_POSITIVE)
        else -> Result.success(u.copy(remarks = u.remarks?.trim()?.ifBlank { null }))
    }

    fun worker(w: NewWorker): Result<NewWorker> = when {
        w.name.isBlank() -> fail("name", Reason.REQUIRED)
        w.name.trim().length > NAME_MAX -> fail("name", Reason.TOO_LONG)
        w.wagePaise < 0 || w.otRatePaise < 0 -> fail("wage", Reason.NOT_POSITIVE)
        else -> Result.success(w.copy(name = w.name.trim(), skill = w.skill?.trim()?.ifBlank { null }))
    }

    fun range(from: LocalDate, to: LocalDate): Result<Unit> =
        if (to.isBefore(from)) fail("to", Reason.END_BEFORE_START) else Result.success(Unit)

    private fun endsBeforeStart(start: LocalDate?, end: LocalDate?) = start != null && end != null && end.isBefore(start)
    private fun <T> fail(field: String, reason: Reason): Result<T> = Result.failure(AppError.Invalid(field, reason))
}

/** Task progress after an update. Quantity wins when the task has a planned quantity. */
object Progress {
    fun apply(task: Task, qtyDone: Double?, pct: Int?): Task {
        val done = task.doneQty + (qtyDone ?: 0.0)
        val newPct = when {
            task.plannedQty != null && qtyDone != null -> ((done / task.plannedQty) * 100).roundToInt()
            pct != null -> pct
            else -> task.progressPct
        }.coerceIn(0, 100)
        val status = when {
            newPct >= 100 -> TaskStatus.COMPLETED
            newPct > 0 || done > 0 -> TaskStatus.IN_PROGRESS
            else -> task.status
        }
        return task.copy(doneQty = done, progressPct = newPct, status = status)
    }

    /**
     * Same rule as app.recompute_task() on the server, from all of a task's updates: quantity wins when the task
     * has a planned quantity and any update has one; otherwise the latest reported percent.
     */
    fun fromUpdates(task: Task, updates: List<app.site.core.model.TaskUpdate>): Task {
        val done = updates.sumOf { it.qtyDone ?: 0.0 }
        val hasQty = updates.any { it.qtyDone != null }
        val pct = when {
            task.plannedQty != null && hasQty -> minOf(100, ((done / task.plannedQty) * 100).roundToInt())
            else -> updates.filter { it.progressPct != null }
                .maxWithOrNull(compareBy({ it.workDate }, { it.createdAt }))?.progressPct ?: 0
        }
        val status = when {
            pct >= 100 -> TaskStatus.COMPLETED
            task.status == TaskStatus.BLOCKED -> TaskStatus.BLOCKED
            pct > 0 || done > 0 -> TaskStatus.IN_PROGRESS
            else -> TaskStatus.NOT_STARTED
        }
        return task.copy(doneQty = done, progressPct = pct, status = status)
    }

    /** Project progress = mean of task progress. Empty project = 0. */
    fun project(tasks: List<Task>): Int = if (tasks.isEmpty()) 0 else tasks.map { it.progressPct }.average().roundToInt()
}

/** Wages from attendance. Same rules as preview_attendance_payables() in the schema. */
object Wages {
    data class Line(val worker: Worker, val days: Double, val otHours: Double, val amountPaise: Long)

    fun dayUnits(status: AttendanceStatus): Double = when (status) {
        AttendanceStatus.PRESENT -> 1.0
        AttendanceStatus.HALF_DAY -> 0.5
        AttendanceStatus.ABSENT -> 0.0
    }

    fun lines(workers: List<Worker>, records: List<Attendance>, shiftHours: Double): List<Line> {
        val byWorker = records.groupBy { it.workerId }
        return workers.mapNotNull { w ->
            val recs = byWorker[w.id].orEmpty()
            val days = recs.sumOf { dayUnits(it.status) }
            val ot = recs.sumOf { it.otHours }
            val base = when (w.wageType) {
                WageType.DAILY -> days * w.wagePaise
                WageType.HOURLY -> days * shiftHours * w.wagePaise
            }
            val amount = (base + ot * w.otRatePaise).roundToLong()
            if (amount > 0 || days > 0) Line(w, days, ot, amount) else null
        }
    }
}
