package app.site.core.data.fake

import app.site.core.data.ActivityRepository
import app.site.core.data.AppError
import app.site.core.data.AttendanceRepository
import app.site.core.data.PeopleRepository
import app.site.core.data.SyncRepository
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
import app.site.core.model.AdjustmentKind
import app.site.core.model.Grn
import app.site.core.model.Material
import app.site.core.model.MaterialIssue
import app.site.core.model.NewAdjustment
import app.site.core.model.NewGrn
import app.site.core.model.NewMaterial
import app.site.core.model.NewMaterialIssue
import app.site.core.model.StockEntry
import app.site.core.model.StockEntryKind
import app.site.core.model.StockItem
import app.site.core.logic.Stock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
) : SessionRepository, ProjectRepository, TaskRepository, AttendanceRepository, ReportRepository, ActivityRepository,
    PeopleRepository, SyncRepository, app.site.core.data.MaterialRepository, app.site.core.data.GalleryRepository {

    companion object {
        const val DEMO_OTP = "123456"
        const val FREE_PROJECTS = 3
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
        val materials: Map<String, Material> = emptyMap(),
        val grns: Map<String, Grn> = emptyMap(),
        val issues: Map<String, MaterialIssue> = emptyMap(),
        val adjustments: Map<String, Adjustment> = emptyMap(),
        /** Photos added straight to the gallery; photos on work updates live on the update. */
        val photos: Map<String, app.site.core.model.Photo> = emptyMap(),
        val albums: Map<String, app.site.core.model.Album> = emptyMap(),
        /** Album of a work-update photo, by its gallery id. */
        val photoAlbums: Map<String, String> = emptyMap(),
    )

    /** A stock_adjustments row: [qty] is signed, like the server's. */
    internal data class Adjustment(
        val id: String, val projectId: String, val materialId: String, val kind: StockEntryKind, val qty: Double,
        val date: LocalDate, val taskId: String?, val note: String?, val createdAt: java.time.Instant,
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

    override suspend fun completeProfile(name: String, orgName: String?): Result<Session> {
        val current = _session.value ?: return Result.failure(AppError.NotAllowed)
        if (name.isBlank()) return Result.failure(AppError.Invalid("name", AppError.Reason.REQUIRED))
        val nu = current.user.copy(name = name.trim())
        state.update { it.copy(users = it.users + (nu.id to nu)) }
        val org = current.org ?: Organisation(newId(), orgName?.trim().orEmpty().ifBlank { "My Organisation" })
            .also { o -> state.update { it.copy(org = o) } }
        val next = current.copy(user = nu, org = org, orgRole = current.orgRole ?: OrgRole.OWNER)
        _session.value = next
        return Result.success(next)
    }

    override suspend fun deleteAccount(): Result<Unit> {
        val u = _session.value?.user ?: return Result.failure(AppError.NotAllowed)
        state.update { it.copy(users = it.users - u.id) }
        _session.value = null
        return Result.success(Unit)
    }

    override fun people(): Flow<Map<String, User>> =
        combine(state, _session) { s, session -> s.users + listOfNotNull(session?.user).associateBy { it.id } }

    private val _sync = MutableStateFlow(app.site.core.data.SyncStatus())
    override val status: StateFlow<app.site.core.data.SyncStatus> = _sync.asStateFlow()
    override fun syncNow() {}

    // ------------------------------------------------------------------ projects

    override fun projects(): Flow<List<Project>> =
        state.map { s -> s.projects.values.sortedWith(compareBy({ it.status.ordinal }, { it.name.lowercase() })) }

    override fun project(id: String): Flow<Project?> = state.map { it.projects[id] }

    override suspend fun create(input: NewProject): Result<Project> {
        io()
        val v = Validate.project(input).getOrElse { return Result.failure(it) }
        val org = state.value.org ?: return Result.failure(AppError.NotAllowed)
        // same rule as the server's check_project_limit()
        val active = state.value.projects.values.count { it.orgId == org.id && it.status != app.site.core.model.ProjectStatus.ARCHIVED }
        if (org.plan == app.site.core.model.Plan.FREE && active >= FREE_PROJECTS) return Result.failure(AppError.PlanLimit("projects"))
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

    override suspend fun fetchFile(id: String): Result<String> =
        state.value.reports[id]?.filePath?.let { Result.success(it) } ?: Result.failure(AppError.Unknown("no file"))

    private fun setReport(id: String, f: (Report) -> Report): Result<Unit> {
        state.update { s -> val r = s.reports[id] ?: return@update s; s.copy(reports = s.reports + (id to f(r))) }
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ material

    override fun catalogue(): Flow<List<Material>> = state.map { s -> s.materials.values.sortedBy { it.label.lowercase() } }

    override fun stock(projectId: String): Flow<List<StockItem>> = state.map { s -> stockOf(s, projectId) }

    override fun history(projectId: String, materialId: String): Flow<List<StockEntry>> = state.map { s ->
        entries(s, projectId).filter { it.first == materialId }.map { it.second }
            .sortedWith(compareByDescending<StockEntry> { it.date }.thenByDescending { it.createdAt })
    }

    override fun grns(projectId: String): Flow<List<Grn>> = state.map { s ->
        s.grns.values.filter { it.projectId == projectId }.sortedWith(compareByDescending<Grn> { it.receivedOn }.thenByDescending { it.createdAt })
    }

    override fun issues(projectId: String): Flow<List<MaterialIssue>> = state.map { s ->
        s.issues.values.filter { it.projectId == projectId }
            .sortedWith(compareByDescending<MaterialIssue> { it.issuedOn }.thenByDescending { it.createdAt })
    }

    override suspend fun addMaterial(input: NewMaterial): Result<Material> {
        io()
        val v = Validate.material(input).getOrElse { return Result.failure(it) }
        val org = state.value.org ?: return Result.failure(AppError.NotAllowed)
        val id = Material.idFor(org.id, v.name, v.spec, v.uom)
        state.value.materials[id]?.let { return Result.success(it) }
        val m = Material(id, org.id, v.name, v.spec, v.uom, v.category)
        state.update { it.copy(materials = it.materials + (m.id to m)) }
        return Result.success(m)
    }

    override suspend fun receive(input: NewGrn): Result<Grn> {
        io()
        val by = runCatching { me() }.getOrElse { return Result.failure(it) }
        val lines = Validate.lines(input.lines).getOrElse { return Result.failure(it) }
        Validate.notFuture(input.receivedOn, today()).getOrElse { return Result.failure(it) }
        val s0 = state.value
        if (s0.projects[input.projectId] == null || lines.any { s0.materials[it.materialId] == null }) return Result.failure(AppError.NotAllowed)
        var grn: Grn? = null
        state.update { s ->
            val n = (s.counters["grns|${input.projectId}"] ?: 0) + 1
            val g = Grn(newId(), input.projectId, n, input.receivedOn, input.challanNo?.trim()?.ifBlank { null },
                input.notes?.trim()?.ifBlank { null }, by.id, lines, clock.instant())
            grn = g
            s.copy(grns = s.grns + (g.id to g), counters = s.counters + ("grns|${input.projectId}" to n))
        }
        return Result.success(grn!!)
    }

    override suspend fun issue(input: NewMaterialIssue): Result<MaterialIssue> {
        io()
        val by = runCatching { me() }.getOrElse { return Result.failure(it) }
        val lines = Validate.lines(input.lines).getOrElse { return Result.failure(it) }
        Validate.notFuture(input.issuedOn, today()).getOrElse { return Result.failure(it) }
        val task = state.value.tasks[input.taskId]
        if (task == null || task.projectId != input.projectId) return Result.failure(AppError.Invalid("task", AppError.Reason.REQUIRED))
        Stock.canIssue(lines, inStock(state.value, input.projectId)).getOrElse { return Result.failure(it) }
        var issue: MaterialIssue? = null
        state.update { s ->
            val n = (s.counters["issues|${input.projectId}"] ?: 0) + 1
            val i = MaterialIssue(newId(), input.projectId, n, task.id, input.issuedOn, by.id, lines, clock.instant())
            issue = i
            s.copy(issues = s.issues + (i.id to i), counters = s.counters + ("issues|${input.projectId}" to n))
        }
        return Result.success(issue!!)
    }

    override suspend fun adjust(input: NewAdjustment): Result<Unit> {
        io()
        runCatching { me() }.getOrElse { return Result.failure(it) }
        Validate.notFuture(input.date, today()).getOrElse { return Result.failure(it) }
        if (state.value.materials[input.materialId] == null) return Result.failure(AppError.NotAllowed)
        val current = inStock(state.value, input.projectId)[input.materialId] ?: 0.0
        val (kind, qty) = when (input.kind) {
            AdjustmentKind.OPENING -> if (input.qty > 0) StockEntryKind.OPENING to input.qty else return notPositive()
            AdjustmentKind.USED -> when {
                !(input.qty > 0) -> return notPositive()
                Stock.canIssue(listOf(app.site.core.model.MaterialLine(input.materialId, input.qty)), mapOf(input.materialId to current)).isFailure ->
                    return Result.failure(AppError.Invalid("qty", AppError.Reason.NOT_ENOUGH_STOCK))
                else -> StockEntryKind.USED to -input.qty
            }
            AdjustmentKind.COUNTED -> if (input.qty < 0) return notPositive()
                else StockEntryKind.CORRECTED to (Stock.correction(current, input.qty) ?: return Result.success(Unit))
        }
        val a = Adjustment(newId(), input.projectId, input.materialId, kind, qty, input.date, input.taskId,
            input.note?.trim()?.ifBlank { null }, clock.instant())
        state.update { it.copy(adjustments = it.adjustments + (a.id to a)) }
        return Result.success(Unit)
    }

    private fun notPositive(): Result<Unit> = Result.failure(AppError.Invalid("qty", AppError.Reason.NOT_POSITIVE))

    /** (materialId, entry) for everything that moved stock on this site. */
    private fun entries(s: State, projectId: String): List<Pair<String, StockEntry>> = buildList {
        s.grns.values.filter { it.projectId == projectId }.forEach { g ->
            g.lines.forEach { l -> add(l.materialId to StockEntry("${g.id}|${l.materialId}", StockEntryKind.RECEIVED, l.qty, g.receivedOn, g.number, note = g.challanNo, createdAt = g.createdAt)) }
        }
        s.issues.values.filter { it.projectId == projectId }.forEach { i ->
            i.lines.forEach { l -> add(l.materialId to StockEntry("${i.id}|${l.materialId}", StockEntryKind.ISSUED, -l.qty, i.issuedOn, i.number, i.taskId, createdAt = i.createdAt)) }
        }
        s.adjustments.values.filter { it.projectId == projectId }.forEach { a ->
            add(a.materialId to StockEntry(a.id, a.kind, a.qty, a.date, taskId = a.taskId, note = a.note, createdAt = a.createdAt))
        }
    }

    private fun inStock(s: State, projectId: String): Map<String, Double> =
        entries(s, projectId).groupBy({ it.first }, { it.second.qty }).mapValues { (_, qs) -> round3(qs.sum()) }

    private fun stockOf(s: State, projectId: String): List<StockItem> =
        entries(s, projectId).groupBy({ it.first }, { it.second }).mapNotNull { (id, es) ->
            s.materials[id]?.let { m -> StockItem(m, round3(es.sumOf { it.qty }), es.maxOf { it.date }) }
        }.sortedBy { it.material.label.lowercase() }

    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0

    // ------------------------------------------------------------------ gallery

    private fun allPhotos(s: State, projectId: String): List<app.site.core.model.Photo> {
        val fromUpdates = s.updates.values.filter { it.projectId == projectId }.flatMap { u ->
            u.photos.mapIndexed { i, ref ->
                val id = "${u.id}#$i"
                app.site.core.model.Photo(id, projectId, ref, u.createdAt, s.photoAlbums[id], u.taskId, u.authorId)
            }
        }
        return (fromUpdates + s.photos.values.filter { it.projectId == projectId }).sortedByDescending { it.takenAt }
    }

    override fun photos(projectId: String): Flow<List<app.site.core.model.Photo>> = state.map { s -> allPhotos(s, projectId) }

    override fun albums(projectId: String): Flow<List<app.site.core.model.Album>> = state.map { s ->
        val photos = allPhotos(s, projectId)
        s.albums.values.filter { it.projectId == projectId }.map { a ->
            val mine = photos.filter { it.albumId == a.id }
            a.copy(photoCount = mine.size, coverRef = mine.firstOrNull()?.ref)
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun addPhotos(projectId: String, uris: List<String>, albumId: String?): Result<Int> {
        io()
        val by = runCatching { me() }.getOrElse { return Result.failure(it) }
        if (state.value.projects[projectId] == null) return Result.failure(AppError.NotAllowed)
        if (albumId != null && state.value.albums[albumId]?.projectId != projectId) return Result.failure(AppError.NotAllowed)
        if (uris.isEmpty()) return Result.failure(AppError.Invalid("photos", AppError.Reason.REQUIRED))
        val now = clock.instant()
        val added = uris.mapIndexed { i, uri ->
            // a millisecond apart keeps the order they were picked in
            app.site.core.model.Photo(newId(), projectId, uri, now.plusMillis(i.toLong()), albumId, null, by.id)
        }
        state.update { st -> st.copy(photos = st.photos + added.associateBy { it.id }) }
        return Result.success(added.size)
    }

    override suspend fun createAlbum(projectId: String, name: String): Result<app.site.core.model.Album> {
        io()
        val n = Validate.albumName(name).getOrElse { return Result.failure(it) }
        if (state.value.projects[projectId] == null) return Result.failure(AppError.NotAllowed)
        val id = app.site.core.model.Album.idFor(projectId, n)
        state.value.albums[id]?.let { return Result.success(it) }
        val a = app.site.core.model.Album(id, projectId, n)
        state.update { it.copy(albums = it.albums + (id to a)) }
        return Result.success(a)
    }

    override suspend fun moveToAlbum(photoIds: List<String>, albumId: String?): Result<Unit> {
        io()
        if (albumId != null && state.value.albums[albumId] == null) return Result.failure(AppError.NotAllowed)
        state.update { s ->
            var photos = s.photos
            var links = s.photoAlbums
            for (id in photoIds) {
                val p = photos[id]
                when {
                    p != null -> photos = photos + (id to p.copy(albumId = albumId))
                    albumId == null -> links = links - id
                    else -> links = links + (id to albumId)
                }
            }
            s.copy(photos = photos, photoAlbums = links)
        }
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
