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

    enum class Reason { REQUIRED, TOO_LONG, END_BEFORE_START, NOT_POSITIVE, WRONG_CODE, BAD_PHONE, NOT_ENOUGH_STOCK, IN_FUTURE, ALREADY_MEMBER, BAD_EMAIL }
}

interface SessionRepository {
    val session: StateFlow<Session?>
    suspend fun requestOtp(phone: String): Result<Unit>
    suspend fun verifyOtp(phone: String, code: String): Result<Session>
    /** First sign-in: set your name and, if you're not in one yet, create your organisation. Also used to rename. */
    suspend fun completeProfile(name: String, orgName: String?): Result<Session>
    /** Your name and email. Works offline; the server gets it on the next sync. */
    suspend fun updateProfile(name: String, email: String?): Result<Session>
    /** Company name and logo (a local image URI). Owners and admins only. */
    suspend fun updateOrganisation(name: String, logoUri: String?): Result<Session>
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
    /** The site's cover photo, from a local image URI. */
    suspend fun setImage(projectId: String, uri: String): Result<Unit>
}

interface TaskRepository {
    fun tasks(projectId: String): Flow<List<Task>>
    fun task(id: String): Flow<Task?>
    fun updates(taskId: String): Flow<List<TaskUpdate>>
    fun updatesInRange(projectId: String, from: LocalDate, to: LocalDate): Flow<List<TaskUpdate>>
    suspend fun create(input: NewTask): Result<Task>
    suspend fun addUpdate(input: NewTaskUpdate): Result<TaskUpdate>
    /** The company's task categories, the common ones and any already used on tasks; sorted, no duplicates. */
    fun categories(): Flow<List<String>>
    /** Adds a category to the company's list (or returns the existing one with that name). */
    suspend fun addCategory(name: String): Result<String>
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

/**
 * Material on a site: the organisation's catalogue, goods received, material issued to tasks and stock updates.
 * Stock is computed from those records (never stored), so it is right on every phone once they sync.
 */
interface MaterialRepository {
    /** The organisation's catalogue, for pickers. */
    fun catalogue(): Flow<List<app.site.core.model.Material>>
    /** Every material this site has had, with what it holds now. */
    fun stock(projectId: String): Flow<List<app.site.core.model.StockItem>>
    /** One material's in and out on this site, newest first. */
    fun history(projectId: String, materialId: String): Flow<List<app.site.core.model.StockEntry>>
    fun grns(projectId: String): Flow<List<app.site.core.model.Grn>>
    fun issues(projectId: String): Flow<List<app.site.core.model.MaterialIssue>>
    /** Adds to the catalogue, or returns the existing entry with the same name, spec and unit. */
    suspend fun addMaterial(input: app.site.core.model.NewMaterial): Result<app.site.core.model.Material>
    suspend fun receive(input: app.site.core.model.NewGrn): Result<app.site.core.model.Grn>
    /** Refused with [AppError.Reason.NOT_ENOUGH_STOCK] when a line is more than the site holds. */
    suspend fun issue(input: app.site.core.model.NewMaterialIssue): Result<app.site.core.model.MaterialIssue>
    /** Opening stock, material used, or a count. A count that matches the stock records nothing. */
    suspend fun adjust(input: app.site.core.model.NewAdjustment): Result<Unit>
}

/** Photos of a site and the albums they're grouped in. */
interface GalleryRepository {
    /** Newest first, from work updates and added straight to the gallery. */
    fun photos(projectId: String): Flow<List<app.site.core.model.Photo>>
    fun albums(projectId: String): Flow<List<app.site.core.model.Album>>
    /** Adds photos (local URIs) to the site, optionally into an album. Returns how many were added. */
    suspend fun addPhotos(projectId: String, uris: List<String>, albumId: String? = null): Result<Int>
    /** Creates an album, or returns the existing one with the same name. */
    suspend fun createAlbum(projectId: String, name: String): Result<app.site.core.model.Album>
    /** Moves photos into an album, or out of every album with null. */
    suspend fun moveToAlbum(photoIds: List<String>, albumId: String?): Result<Unit>
}

/** Who works on a site, with what role, and who's been invited. */
interface TeamRepository {
    fun team(projectId: String): Flow<app.site.core.model.Team>
    /**
     * Invites a phone number to this site with a role; they get an SMS and join when they sign in.
     * Needs a connection (the server sends the SMS). Returns whether the SMS went out.
     */
    suspend fun invite(projectId: String, phone: String, role: app.site.core.model.ProjectRole): Result<Boolean>
    suspend fun setRole(projectId: String, userId: String, role: app.site.core.model.ProjectRole): Result<Unit>
    suspend fun remove(projectId: String, userId: String): Result<Unit>
}

/** Issues raised on a site and the conversation on each. */
interface IssueRepository {
    /** Newest first. (Material handed out is MaterialRepository.issues.) */
    fun siteIssues(projectId: String): Flow<List<app.site.core.model.Issue>>
    fun siteIssue(id: String): Flow<app.site.core.model.Issue?>
    /** Oldest first, like a conversation. */
    fun comments(issueId: String): Flow<List<app.site.core.model.Comment>>
    suspend fun raise(input: app.site.core.model.NewIssue): Result<app.site.core.model.Issue>
    suspend fun setStatus(issueId: String, status: app.site.core.model.IssueStatus): Result<Unit>
    suspend fun comment(issueId: String, body: String): Result<app.site.core.model.Comment>
}
