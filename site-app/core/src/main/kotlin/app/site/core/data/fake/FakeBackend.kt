package app.site.core.data.fake

import app.site.core.data.ActivityRepository
import app.site.core.data.AppError
import app.site.core.data.AttendanceRepository
import app.site.core.data.ProjectRepository
import app.site.core.data.ReportRepository
import app.site.core.data.SessionRepository
import app.site.core.data.TaskRepository
import app.site.core.logic.Progress
import app.site.core.logic.Validate
import app.site.core.model.ActivityEvent
import app.site.core.model.ActivityVerb
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.NewProject
import app.site.core.model.NewReport
import app.site.core.model.NewTask
import app.site.core.model.NewTaskUpdate
import app.site.core.model.NewWorker
import app.site.core.model.OrgRole
import app.site.core.model.Organisation
import app.site.core.model.Project
import app.site.core.model.Report
import app.site.core.model.ReportStatus
import app.site.core.model.Session
import app.site.core.model.Task
import app.site.core.model.TaskUpdate
import app.site.core.model.User
import app.site.core.model.Worker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * In-memory stand-in for Room + Supabase with the same behaviour the schema enforces:
 * per-project numbers, one attendance record per worker per day, progress roll-up, feed events.
 * Data lives for the life of the process. The demo OTP is [DEMO_OTP].
 */
class FakeBackend(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val latencyMs: Long = 250,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    seed: Boolean = true,
) : SessionRepository, ProjectRepository, TaskRepository, AttendanceRepository, ReportRepository, ActivityRepository {

    companion object {
        const val DEMO_OTP = "123456"
        val REPORT_LIFETIME: Duration = Duration.ofDays(90)
    }

    internal data class State(
        val users: Map<String, User> = emptyMap(),
        val org: Organisation? = null,
        val projects: Map<String, Project> = emptyMap(),
        val tasks: Map<String, Task> = emptyMap(),
        val updates: Map<String, TaskUpdate> = emptyMap(),
        val workers: Map<String, Worker> = emptyMap(),
        /** key: projectId|workerId|date */
        val attendance: Map<String, Attendance> = emptyMap(),
        val reports: Map<String, Report> = emptyMap(),
        val events: List<ActivityEvent> = emptyList(),
        val counters: Map<String, Int> = emptyMap(),
    )

    private val state = MutableStateFlow(State())
    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        if (seed) state.value = Seed.build(clock, newId)
    }

    val people: Map<String, User> get() = state.value.users
    fun today(): LocalDate = LocalDate.now(clock)

    private suspend fun io() { if (latencyMs > 0) delay(latencyMs) }
    private fun me(): User = _session.value?.user ?: throw AppError.NotAllowed
    private fun attKey(p: String, w: String, d: LocalDate) = "$p|$w|$d"

    // ------------------------------------------------------------------ session

    override suspend fun requestOtp(phone: String): Result<Unit> {
        io()
        return Validate.phone(phone).map { }
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<Session> {
        io()
        val normalised = Validate.phone(phone).getOrElse { return Result.failure(it) }
        if (code.trim() != DEMO_OTP) return Result.failure(AppError.Invalid("code", AppError.Reason.WRONG_CODE))
        var user: User? = null
        state.update { s ->
            val existing = s.users.values.firstOrNull { it.phone == normalised }
            val u = existing ?: User(newId(), "", normalised)
            user = u
            s.copy(users = s.users + (u.id to u))
        }
        val org = state.value.org ?: Organisation(newId(), "My Organisation").also { o -> state.update { it.copy(org = o) } }
        val session = Session(user!!, org, OrgRole.OWNER)
        _session.value = session
        return Result.success(session)
    }

    override fun signOut() { _session.value = null }

    fun updateProfileName(name: String) {
        val u = _session.value?.user ?: return
        val nu = u.copy(name = name.trim())
        state.update { it.copy(users = it.users + (nu.id to nu)) }
        _session.update { it?.copy(user = nu) }
    }

    // ------------------------------------------------------------------ projects

    override fun projects(): Flow<List<Project>> =
        state.map { s -> s.projects.values.sortedWith(compareBy({ it.status.ordinal }, { it.name.lowercase() })) }

    override fun project(id: String): Flow<Project?> = state.map { it.projects[id] }

    override suspend fun create(input: NewProject): Result<Project> {
        io()
        val v = Validate.project(input).getOrElse { return Result.failure(it) }
        val org = state.value.org ?: return Result.failure(AppError.NotAllowed)
        val p = Project(newId(), org.id, v.name, address = v.address, startDate = v.startDate, endDate = v.endDate)
        state.update { s -> s.copy(projects = s.projects + (p.id to p)) }
        event(p.id, ActivityVerb.CREATED_PROJECT, p.id, p.name)
        return Result.success(p)
    }

    override suspend fun updateSettings(projectId: String, shiftHours: Double, attendanceRemarks: Boolean): Result<Unit> {
        io()
        if (shiftHours <= 0 || shiftHours > 24) return Result.failure(AppError.Invalid("shiftHours", AppError.Reason.NOT_POSITIVE))
        state.update { s ->
            val p = s.projects[projectId] ?: return@update s
            s.copy(projects = s.projects + (p.id to p.copy(shiftHours = shiftHours, attendanceRemarks = attendanceRemarks)))
        }
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ tasks

    override fun tasks(projectId: String): Flow<List<Task>> =
        state.map { s -> s.tasks.values.filter { it.projectId == projectId }.sortedByDescending { it.number ?: Int.MAX_VALUE } }

    override fun task(id: String): Flow<Task?> = state.map { it.tasks[id] }

    override fun updates(taskId: String): Flow<List<TaskUpdate>> =
        state.map { s -> s.updates.values.filter { it.taskId == taskId }.sortedByDescending { it.createdAt } }

    override fun updatesInRange(projectId: String, from: LocalDate, to: LocalDate): Flow<List<TaskUpdate>> =
        state.map { s ->
            s.updates.values.filter { it.projectId == projectId && !it.workDate.isBefore(from) && !it.workDate.isAfter(to) }
        }

    override suspend fun create(input: NewTask): Result<Task> {
        io()
        val v = Validate.task(input).getOrElse { return Result.failure(it) }
        if (state.value.projects[v.projectId] == null) return Result.failure(AppError.NotAllowed)
        var task: Task? = null
        state.update { s ->
            val n = (s.counters["tasks|${v.projectId}"] ?: 0) + 1
            val t = Task(
                newId(), v.projectId, n, v.name, v.category, v.unit, v.plannedQty,
                startDate = v.startDate, dueDate = v.dueDate, assigneeId = v.assigneeId,
            )
            task = t
            s.copy(tasks = s.tasks + (t.id to t), counters = s.counters + ("tasks|${v.projectId}" to n))
        }
        rollUp(v.projectId)
        event(v.projectId, ActivityVerb.CREATED_TASK, task!!.id, task!!.name)
        return Result.success(task!!)
    }

    override suspend fun addUpdate(input: NewTaskUpdate): Result<TaskUpdate> {
        io()
        val author = runCatching { me() }.getOrElse { return Result.failure(it) }
        val v = Validate.taskUpdate(input).getOrElse { return Result.failure(it) }
        val task = state.value.tasks[v.taskId] ?: return Result.failure(AppError.NotAllowed)
        val u = TaskUpdate(
            newId(), task.id, task.projectId, author.id, v.workDate, v.qtyDone, v.progressPct, v.remarks, v.photos,
            clock.instant(),
        )
        state.update { s ->
            val t = s.tasks[task.id] ?: return@update s
            s.copy(updates = s.updates + (u.id to u), tasks = s.tasks + (t.id to Progress.apply(t, v.qtyDone, v.progressPct)))
        }
        rollUp(task.projectId)
        event(task.projectId, ActivityVerb.UPDATED_PROGRESS, task.id, task.name)
        return Result.success(u)
    }

    private fun rollUp(projectId: String) = state.update { s ->
        val p = s.projects[projectId] ?: return@update s
        val pct = Progress.project(s.tasks.values.filter { it.projectId == projectId })
        s.copy(projects = s.projects + (p.id to p.copy(progressPct = pct)))
    }

    // ------------------------------------------------------------------ attendance

    override fun workers(projectId: String): Flow<List<Worker>> =
        state.map { s -> s.workers.values.filter { it.projectId == projectId && it.active }.sortedBy { it.name.lowercase() } }

    override fun day(projectId: String, date: LocalDate): Flow<List<Attendance>> =
        state.map { s -> s.attendance.values.filter { it.projectId == projectId && it.date == date } }

    override fun range(projectId: String, from: LocalDate, to: LocalDate): Flow<List<Attendance>> =
        state.map { s ->
            s.attendance.values.filter { it.projectId == projectId && !it.date.isBefore(from) && !it.date.isAfter(to) }
        }

    override suspend fun addWorker(input: NewWorker): Result<Worker> {
        io()
        val v = Validate.worker(input).getOrElse { return Result.failure(it) }
        val w = Worker(newId(), v.projectId, v.name, v.skill, v.phone, v.wageType, v.wagePaise, v.otRatePaise)
        state.update { s -> s.copy(workers = s.workers + (w.id to w)) }
        return Result.success(w)
    }

    override suspend fun mark(
        projectId: String, workerId: String, date: LocalDate, status: AttendanceStatus, otHours: Double, remarks: String?,
    ): Result<Unit> {
        if (date.isAfter(today())) return Result.failure(AppError.Invalid("date", AppError.Reason.END_BEFORE_START))
        if (otHours < 0 || otHours > 16) return Result.failure(AppError.Invalid("otHours", AppError.Reason.NOT_POSITIVE))
        val a = Attendance(projectId, workerId, date, status, otHours, remarks?.trim()?.ifBlank { null })
        state.update { s -> s.copy(attendance = s.attendance + (attKey(projectId, workerId, date) to a)) }
        return Result.success(Unit)
    }

    override suspend fun clear(projectId: String, workerId: String, date: LocalDate): Result<Unit> {
        state.update { s -> s.copy(attendance = s.attendance - attKey(projectId, workerId, date)) }
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ reports

    override fun reports(projectId: String): Flow<List<Report>> =
        state.map { s ->
            val now = clock.instant()
            s.reports.values.filter { it.projectId == projectId && it.expiresAt.isAfter(now) }.sortedByDescending { it.createdAt }
        }

    override fun report(id: String): Flow<Report?> = state.map { it.reports[id] }

    override suspend fun request(input: NewReport): Result<Report> {
        io()
        Validate.range(input.from, input.to).getOrElse { return Result.failure(it) }
        val now = clock.instant()
        val r = Report(newId(), input.projectId, input.type, input.from, input.to, input.parts, ReportStatus.QUEUED, now, now.plus(REPORT_LIFETIME))
        state.update { s -> s.copy(reports = s.reports + (r.id to r)) }
        return Result.success(r)
    }

    override suspend fun markReady(id: String, filePath: String): Result<Unit> = setReport(id) {
        it.copy(status = ReportStatus.READY, filePath = filePath)
    }

    override suspend fun markFailed(id: String): Result<Unit> = setReport(id) { it.copy(status = ReportStatus.FAILED) }

    private fun setReport(id: String, f: (Report) -> Report): Result<Unit> {
        state.update { s -> val r = s.reports[id] ?: return@update s; s.copy(reports = s.reports + (id to f(r))) }
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ feed

    override fun feed(projectId: String): Flow<List<ActivityEvent>> =
        // newest first; ties (same instant) keep insertion order, newest first
        state.map { s -> s.events.filter { it.projectId == projectId }.asReversed().sortedByDescending { it.createdAt } }

    private fun event(projectId: String, verb: ActivityVerb, entityId: String?, summary: String) {
        val actor = _session.value?.user
        val e = ActivityEvent(newId(), projectId, actor?.id, actor?.name.orEmpty(), verb, entityId, summary.take(200), clock.instant())
        state.update { it.copy(events = it.events + e) }
    }
}
