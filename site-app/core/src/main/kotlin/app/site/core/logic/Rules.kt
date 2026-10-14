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

    const val MATERIAL_NAME_MAX = 80
    const val MATERIAL_SPEC_MAX = 60
    const val UOM_MAX = 12

    fun material(m: app.site.core.model.NewMaterial): Result<app.site.core.model.NewMaterial> {
        val name = m.name.trim().replace(Regex("\\s+"), " ")
        val spec = m.spec.trim().replace(Regex("\\s+"), " ")
        val uom = m.uom.trim()
        return when {
            name.isEmpty() -> fail("name", Reason.REQUIRED)
            name.length > MATERIAL_NAME_MAX -> fail("name", Reason.TOO_LONG)
            spec.length > MATERIAL_SPEC_MAX -> fail("spec", Reason.TOO_LONG)
            uom.isEmpty() -> fail("uom", Reason.REQUIRED)
            uom.length > UOM_MAX -> fail("uom", Reason.TOO_LONG)
            else -> Result.success(m.copy(name = name, spec = spec, uom = uom, category = m.category?.trim()?.ifBlank { null }))
        }
    }

    /** At least one line, every quantity above zero; the same material twice becomes one line. */
    fun lines(lines: List<app.site.core.model.MaterialLine>): Result<List<app.site.core.model.MaterialLine>> = when {
        lines.isEmpty() -> fail("lines", Reason.REQUIRED)
        lines.any { !(it.qty > 0) } -> fail("qty", Reason.NOT_POSITIVE)
        else -> Result.success(lines.groupBy { it.materialId }.map { (id, ls) -> app.site.core.model.MaterialLine(id, ls.sumOf { it.qty }) })
    }

    const val CATEGORY_MAX = 60

    fun category(name: String): Result<String> {
        val n = name.trim().replace(Regex("\\s+"), " ")
        return when {
            n.isEmpty() -> fail("category", Reason.REQUIRED)
            n.length > CATEGORY_MAX -> fail("category", Reason.TOO_LONG)
            else -> Result.success(n)
        }
    }

    const val ALBUM_NAME_MAX = 60

    fun albumName(name: String): Result<String> {
        val n = name.trim().replace(Regex("\\s+"), " ")
        return when {
            n.isEmpty() -> fail("name", Reason.REQUIRED)
            n.length > ALBUM_NAME_MAX -> fail("name", Reason.TOO_LONG)
            else -> Result.success(n)
        }
    }

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** Optional; blank means none. */
    fun email(raw: String?): Result<String?> {
        val e = raw?.trim().orEmpty()
        return when {
            e.isEmpty() -> Result.success(null)
            e.length > 254 || !EMAIL.matches(e) -> fail("email", Reason.BAD_EMAIL)
            else -> Result.success(e.lowercase())
        }
    }

    fun notFuture(date: LocalDate, today: LocalDate): Result<Unit> =
        if (date.isAfter(today)) fail("date", Reason.IN_FUTURE) else Result.success(Unit)

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

/** Stock on a site: what came in, minus what went out, plus corrections. Same rule as the server's stock ledger. */
object Stock {
    /** Quantities are compared to 3 decimals, like the server's numeric(14,3). */
    private const val EPS = 0.0005

    /** Refuses to hand out more than the site holds, naming nothing: the screen shows the shortfall per line. */
    fun canIssue(lines: List<app.site.core.model.MaterialLine>, inStock: Map<String, Double>): Result<Unit> =
        if (lines.any { it.qty > (inStock[it.materialId] ?: 0.0) + EPS }) Result.failure(AppError.Invalid("qty", Reason.NOT_ENOUGH_STOCK))
        else Result.success(Unit)

    /** For a count: the correction that brings [current] to [counted]; null when nothing changes. */
    fun correction(current: Double, counted: Double): Double? =
        (counted - current).takeIf { kotlin.math.abs(it) > EPS }?.let { Math.round(it * 1000) / 1000.0 }
}

/** Task categories: what a new company starts with, and how lists are merged. */
object Categories {
    val COMMON = listOf("General", "Civil", "Masonry", "Concrete", "Steel", "Plumbing", "Electrical", "Finishing", "Painting", "MEP")

    fun idFor(orgId: String, name: String): String =
        java.util.UUID.nameUUIDFromBytes("category|$orgId|${name.trim().lowercase()}".toByteArray()).toString()

    /** One entry per name ignoring case, first spelling wins; General first, then A to Z. */
    fun merge(vararg lists: List<String>): List<String> =
        lists.flatMap { it }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .sortedWith(compareBy({ !it.equals("General", true) }, { it.lowercase() }))
}
