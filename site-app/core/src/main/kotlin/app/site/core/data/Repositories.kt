package app.site.core.data

import app.site.core.model.ActivityEvent
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.NewProject
import app.site.core.model.NewReport
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import app.site.core.model.NewWorker
import app.site.core.model.Project
import app.site.core.model.Report
import app.site.core.model.Session
import app.site.core.model.Task
import app.site.core.model.TaskUpdate
import app.site.core.model.Worker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * The data layer the screens talk to. Today it is backed by [app.site.core.data.fake.FakeBackend];
 * /replica-backend swaps in Room + Supabase behind the same interfaces.
 *
 * Reads are Flows (the local database is the source of truth, so screens update when sync lands).
 * Writes are suspend functions returning [Result]; failures carry an [AppError], never a server message.
 */

sealed class AppError(message: String) : Exception(message) {
    data object Offline : AppError("offline")
    data object NotAllowed : AppError("not allowed")
    data class Invalid(val field: String, val reason: Reason) : AppError("$field: $reason")
    data class Unknown(val detail: String) : AppError(detail)
    /** The organisation's plan doesn't allow more of [what] (e.g. "projects"). */
    data class PlanLimit(val what: String) : AppError("plan limit: $what")
    /** The only owner can't leave while other people are in the organisation. */
    data object OwnerMustTransfer : AppError("owner must transfer")

    enum class Reason { REQUIRED, TOO_LONG, END_BEFORE_START, NOT_POSITIVE, WRONG_CODE, BAD_PHONE }
}

interface SessionRepository {
    val session: StateFlow<Session?>
    suspend fun requestOtp(phone: String): Result<Unit>
    suspend fun verifyOtp(phone: String, code: String): Result<Session>
    /** First sign-in: set your name and, if you're not in one yet, create your organisation. Also used to rename. */
    suspend fun completeProfile(name: String, orgName: String?): Result<Session>
    /** Deletes the account for real (store requirement). */
    suspend fun deleteAccount(): Result<Unit>
    fun signOut()
}

interface PeopleRepository {
    /** Everyone in the user's organisations, by id. */
    fun people(): Flow<Map<String, app.site.core.model.User>>
}

data class SyncStatus(
    val pending: Long = 0,
    val syncing: Boolean = false,
    val offline: Boolean = false,
    /** Changes the server refused (permissions, plan limits); they were undone on the phone. */
    val rejected: Int = 0,
    val lastSyncedAt: java.time.Instant? = null,
)

interface SyncRepository {
    val status: StateFlow<SyncStatus>
    /** Push local changes, then pull. Safe to call often. */
    fun syncNow()
}

interface ProjectRepository {
    fun projects(): Flow<List<Project>>
    fun project(id: String): Flow<Project?>
    suspend fun create(input: NewProject): Result<Project>
    suspend fun updateSettings(projectId: String, shiftHours: Double, attendanceRemarks: Boolean): Result<Unit>
}

interface TaskRepository {
    fun tasks(projectId: String): Flow<List<Task>>
    fun task(id: String): Flow<Task?>
    fun updates(taskId: String): Flow<List<TaskUpdate>>
    fun updatesInRange(projectId: String, from: LocalDate, to: LocalDate): Flow<List<TaskUpdate>>
    suspend fun create(input: NewTask): Result<Task>
    suspend fun addUpdate(input: NewTaskUpdate): Result<TaskUpdate>
}

interface AttendanceRepository {
    fun workers(projectId: String): Flow<List<Worker>>
    fun day(projectId: String, date: LocalDate): Flow<List<Attendance>>
    fun range(projectId: String, from: LocalDate, to: LocalDate): Flow<List<Attendance>>
    suspend fun addWorker(input: NewWorker): Result<Worker>
    /** Upsert on (project, worker, date): a second mark replaces the first. */
    suspend fun mark(
        projectId: String,
        workerId: String,
        date: LocalDate,
        status: AttendanceStatus,
        otHours: Double = 0.0,
        remarks: String? = null,
    ): Result<Unit>
    suspend fun clear(projectId: String, workerId: String, date: LocalDate): Result<Unit>
}

interface ReportRepository {
    fun reports(projectId: String): Flow<List<Report>>
    fun report(id: String): Flow<Report?>
    suspend fun request(input: NewReport): Result<Report>
    /** Called by the renderer (on-device today, the PDF worker later). */
    suspend fun markReady(id: String, filePath: String): Result<Unit>
    suspend fun markFailed(id: String): Result<Unit>
    /** A local copy of the report's PDF, downloading it if it was made on another phone. */
    suspend fun fetchFile(id: String): Result<String>
}

interface ActivityRepository {
    fun feed(projectId: String): Flow<List<ActivityEvent>>
}
