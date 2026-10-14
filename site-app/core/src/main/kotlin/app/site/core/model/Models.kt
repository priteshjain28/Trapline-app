package app.site.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * Domain models. They mirror the tables in supabase/migrations/20261014000000_init.sql,
 * so swapping the fake data layer for Supabase changes no screen code.
 * Money is always paise (Long). Site days are LocalDate in the project's time zone.
 */

data class User(val id: String, val name: String, val phone: String, val email: String? = null)

enum class Plan { FREE, PRO, PRO_PLUS, ADVANCED }

data class Organisation(val id: String, val name: String, val plan: Plan = Plan.FREE)

enum class OrgRole { OWNER, ADMIN, MEMBER }

enum class ProjectRole { MANAGER, ENGINEER, SUPERVISOR, STOREKEEPER, ACCOUNTANT, VIEWER }

data class Session(val user: User, val org: Organisation, val orgRole: OrgRole)

enum class ProjectStatus { ACTIVE, ON_HOLD, COMPLETED, ARCHIVED }

data class Project(
    val id: String,
    val orgId: String,
    val name: String,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val address: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val progressPct: Int = 0,
    val shiftHours: Double = 8.0,
    val attendanceRemarks: Boolean = true,
)

data class NewProject(
    val name: String,
    val address: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

enum class TaskStatus { NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETED }

data class Task(
    val id: String,
    val projectId: String,
    /** Per-project number, assigned when the row reaches the server. Null while pending. */
    val number: Int?,
    val name: String,
    val category: String = "General",
    val unit: String? = null,
    val plannedQty: Double? = null,
    val doneQty: Double = 0.0,
    val progressPct: Int = 0,
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val assigneeId: String? = null,
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    val hasWorkOrder: Boolean = false,
    val openIssues: Int = 0,
) {
    fun isDelayed(today: LocalDate): Boolean =
        status != TaskStatus.COMPLETED && dueDate != null && dueDate.isBefore(today)
}

data class NewTask(
    val projectId: String,
    val name: String,
    val category: String = "General",
    val unit: String? = null,
    val plannedQty: Double? = null,
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val assigneeId: String? = null,
)

data class TaskUpdate(
    val id: String,
    val taskId: String,
    val projectId: String,
    val authorId: String,
    val workDate: LocalDate,
    val qtyDone: Double? = null,
    val progressPct: Int? = null,
    val remarks: String? = null,
    /** Local content URIs until upload; storage paths after. */
    val photos: List<String> = emptyList(),
    val createdAt: Instant,
)

data class NewTaskUpdate(
    val taskId: String,
    val workDate: LocalDate,
    val qtyDone: Double? = null,
    val progressPct: Int? = null,
    val remarks: String? = null,
    val photos: List<String> = emptyList(),
)

enum class WageType { DAILY, HOURLY }

data class Worker(
    val id: String,
    val projectId: String,
    val name: String,
    val skill: String? = null,
    val phone: String? = null,
    val wageType: WageType = WageType.DAILY,
    val wagePaise: Long = 0,
    val otRatePaise: Long = 0,
    val active: Boolean = true,
)

data class NewWorker(
    val projectId: String,
    val name: String,
    val skill: String? = null,
    val phone: String? = null,
    val wageType: WageType = WageType.DAILY,
    val wagePaise: Long = 0,
    val otRatePaise: Long = 0,
)

enum class AttendanceStatus { PRESENT, HALF_DAY, ABSENT }

/** One record per worker per day (unique key in the schema). */
data class Attendance(
    val projectId: String,
    val workerId: String,
    val date: LocalDate,
    val status: AttendanceStatus,
    val otHours: Double = 0.0,
    val remarks: String? = null,
)

enum class ReportType { DETAILED, SUMMARY }

data class ReportParts(
    val taskUpdates: Boolean = true,
    val photos: Boolean = true,
    val attendance: Boolean = true,
    val wages: Boolean = false,
)

enum class ReportStatus { QUEUED, RENDERING, READY, FAILED }

data class Report(
    val id: String,
    val projectId: String,
    val type: ReportType,
    val from: LocalDate,
    val to: LocalDate,
    val parts: ReportParts,
    val status: ReportStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
    val filePath: String? = null,
)

data class NewReport(
    val projectId: String,
    val type: ReportType,
    val from: LocalDate,
    val to: LocalDate,
    val parts: ReportParts,
)

enum class ActivityVerb { CREATED_PROJECT, JOINED, CREATED_TASK, UPDATED_PROGRESS, RAISED_ISSUE, CLOSED_ISSUE }

data class ActivityEvent(
    val id: String,
    val projectId: String,
    val actorId: String?,
    val actorName: String,
    val verb: ActivityVerb,
    val entityId: String?,
    val summary: String,
    val createdAt: Instant,
)
