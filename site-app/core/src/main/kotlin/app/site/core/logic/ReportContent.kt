package app.site.core.logic

import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.Project
import app.site.core.model.ReportParts
import app.site.core.model.ReportType
import app.site.core.model.Task
import app.site.core.model.TaskUpdate
import app.site.core.model.User
import app.site.core.model.Worker
import java.time.LocalDate

/**
 * What goes in a report, as data. The renderer (Android PdfDocument today, the HTML/Chromium worker later)
 * decides layout and wording. Nothing here is translated, so one builder serves every language.
 */
data class ReportContent(
    val project: Project,
    val type: ReportType,
    val from: LocalDate,
    val to: LocalDate,
    val parts: ReportParts,
    /** Detailed reports: one section per day that has anything in it, oldest first. */
    val days: List<Day>,
    /** Summary reports: one row per task touched in the period. */
    val taskSummary: List<TaskRow>,
    val workerSummary: List<WorkerRow>,
    val totals: Totals,
) {
    data class Day(val date: LocalDate, val updates: List<UpdateRow>, val attendance: Headcount?, val photos: List<String>)
    data class UpdateRow(
        val taskNumber: Int?, val taskName: String, val qtyDone: Double?, val unit: String?,
        val progressPct: Int?, val remarks: String?, val author: String,
    )
    data class Headcount(val present: Int, val halfDay: Int, val absent: Int, val otHours: Double)
    data class TaskRow(val taskNumber: Int?, val taskName: String, val qtyInPeriod: Double, val unit: String?, val progressNow: Int)
    data class WorkerRow(val name: String, val skill: String?, val days: Double, val otHours: Double, val wagesPaise: Long)
    data class Totals(val updates: Int, val photos: Int, val workerDays: Double, val wagesPaise: Long)

    val isEmpty: Boolean get() = days.isEmpty() && taskSummary.isEmpty() && workerSummary.isEmpty()
}

object ReportBuilder {
    fun build(
        project: Project,
        type: ReportType,
        from: LocalDate,
        to: LocalDate,
        parts: ReportParts,
        tasks: List<Task>,
        updates: List<TaskUpdate>,
        workers: List<Worker>,
        attendance: List<Attendance>,
        people: Map<String, User>,
    ): ReportContent {
        val inRange = { d: LocalDate -> !d.isBefore(from) && !d.isAfter(to) }
        val taskById = tasks.associateBy { it.id }
        val ups = updates.filter { it.projectId == project.id && inRange(it.workDate) }.sortedBy { it.createdAt }
        val att = attendance.filter { it.projectId == project.id && inRange(it.date) }

        val days = if (type == ReportType.DETAILED) {
            val dates = (ups.map { it.workDate } + att.map { it.date }).toSortedSet()
            dates.mapNotNull { date ->
                val dayUps = if (parts.taskUpdates) ups.filter { it.workDate == date } else emptyList()
                val dayAtt = if (parts.attendance) att.filter { it.date == date } else emptyList()
                val photos = if (parts.photos) dayUps.flatMap { it.photos } else emptyList()
                val rows = dayUps.map { u ->
                    val t = taskById[u.taskId]
                    ReportContent.UpdateRow(
                        t?.number, t?.name ?: "", u.qtyDone, t?.unit, u.progressPct, u.remarks,
                        people[u.authorId]?.name ?: "",
                    )
                }
                val head = if (dayAtt.isEmpty()) null else headcount(dayAtt)
                if (rows.isEmpty() && head == null && photos.isEmpty()) null
                else ReportContent.Day(date, rows, head, photos)
            }
        } else emptyList()

        val taskSummary = if (type == ReportType.SUMMARY && parts.taskUpdates) {
            ups.groupBy { it.taskId }.mapNotNull { (taskId, list) ->
                val t = taskById[taskId] ?: return@mapNotNull null
                ReportContent.TaskRow(t.number, t.name, list.sumOf { it.qtyDone ?: 0.0 }, t.unit, t.progressPct)
            }.sortedBy { it.taskNumber ?: Int.MAX_VALUE }
        } else emptyList()

        val wageLines = Wages.lines(workers.filter { it.projectId == project.id }, att, project.shiftHours)
        val workerSummary = if (type == ReportType.SUMMARY && parts.attendance) {
            wageLines.map {
                ReportContent.WorkerRow(it.worker.name, it.worker.skill, it.days, it.otHours, if (parts.wages) it.amountPaise else 0)
            }
        } else emptyList()

        val totals = ReportContent.Totals(
            updates = if (parts.taskUpdates) ups.size else 0,
            photos = if (parts.photos) ups.sumOf { it.photos.size } else 0,
            workerDays = if (parts.attendance) att.sumOf { Wages.dayUnits(it.status) } else 0.0,
            wagesPaise = if (parts.wages) wageLines.sumOf { it.amountPaise } else 0,
        )
        return ReportContent(project, type, from, to, parts, days, taskSummary, workerSummary, totals)
    }

    private fun headcount(list: List<Attendance>) = ReportContent.Headcount(
        present = list.count { it.status == AttendanceStatus.PRESENT },
        halfDay = list.count { it.status == AttendanceStatus.HALF_DAY },
        absent = list.count { it.status == AttendanceStatus.ABSENT },
        otHours = list.sumOf { it.otHours },
    )
}
