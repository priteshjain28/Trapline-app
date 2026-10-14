package app.site.core.data.synced

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.site.core.data.ActivityRepository
import app.site.core.data.AppError
import app.site.core.data.AttendanceRepository
import app.site.core.data.PeopleRepository
import app.site.core.data.ProjectRepository
import app.site.core.data.ReportRepository
import app.site.core.data.SessionRepository
import app.site.core.data.SyncRepository
import app.site.core.data.SyncStatus
import app.site.core.data.TaskRepository
import app.site.core.local.SiteDb
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
import app.site.core.model.Plan
import app.site.core.model.Project
import app.site.core.model.ProjectStatus
import app.site.core.model.Report
import app.site.core.model.ReportParts
import app.site.core.model.ReportStatus
import app.site.core.model.ReportType
import app.site.core.model.Session
import app.site.core.model.Task
import app.site.core.model.TaskStatus
import app.site.core.model.TaskUpdate
import app.site.core.model.User
import app.site.core.model.WageType
import app.site.core.model.Worker
import app.site.core.remote.AuthApi
import app.site.core.remote.FileStore
import app.site.core.remote.RemoteApi
import app.site.core.remote.RemoteError
import app.site.core.remote.TokenStore
import app.site.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import app.site.core.local.Activity as ActivityRow
import app.site.core.local.Attendance as AttendanceRow
import app.site.core.local.Media as MediaRow
import app.site.core.local.Person as PersonRow
import app.site.core.local.Project as ProjectRow
import app.site.core.local.Report as ReportRow
import app.site.core.local.Task as TaskRow
import app.site.core.local.Task_update as UpdateRow
import app.site.core.local.Worker as WorkerRow
import app.site.core.local.Grn as GrnRow
import app.site.core.local.Album as AlbumRow
import app.site.core.local.Invite as InviteRow
import app.site.core.local.Org_member as OrgMemberRow
import app.site.core.local.Project_member as ProjectMemberRow
import app.site.core.model.Member
import app.site.core.model.PendingInvite
import app.site.core.model.ProjectRole
import app.site.core.model.Team
import app.site.core.model.Album
import app.site.core.model.Photo
import app.site.core.local.Grn_line as GrnLineRow
import app.site.core.local.Material as MaterialRow
import app.site.core.local.Material_issue as IssueRow
import app.site.core.local.Material_issue_line as IssueLineRow
import app.site.core.local.Movements as MovementRow
import app.site.core.local.Stock_adjustment as AdjustmentRow
import app.site.core.logic.Stock
import app.site.core.model.AdjustmentKind
import app.site.core.model.Grn
import app.site.core.model.Material
import app.site.core.model.MaterialIssue
import app.site.core.model.MaterialLine
import app.site.core.model.NewAdjustment
import app.site.core.model.NewGrn
import app.site.core.model.NewMaterial
import app.site.core.model.NewMaterialIssue
import app.site.core.model.StockEntry
import app.site.core.model.StockEntryKind
import app.site.core.model.StockItem

/**
 * The real data layer: the phone's database first, Supabase behind it.
 * Every write lands locally and in the outbox in one transaction, then sync runs in the background,
 * so the app works the same with or without signal. Screens see exactly the interfaces the fake used.
 */
class SyncedBackend(
    private val db: SiteDb,
    private val api: RemoteApi,
    private val auth: AuthApi,
    private val tokens: TokenStore,
    private val files: FileStore,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SessionRepository, ProjectRepository, TaskRepository, AttendanceRepository, ReportRepository, ActivityRepository,
    PeopleRepository, SyncRepository, app.site.core.data.MaterialRepository, app.site.core.data.GalleryRepository, app.site.core.data.TeamRepository {

    private val q = db.siteQueries

    val engine = SyncEngine(
        db, api, files, scope, clock,
        tables = pullSpecs(),
        tombstoneHandlers = mapOf(
            "projects" to q::deleteProject, "tasks" to q::deleteTask, "task_updates" to q::deleteUpdate,
            "media" to q::deleteMedia, "workers" to q::deleteWorker, "attendance" to q::deleteAttendance,
            "reports" to q::deleteReport, "activity_events" to q::deleteActivity,
            "materials" to q::deleteMaterial, "grns" to q::deleteGrn, "grn_lines" to q::deleteGrnLine,
            "material_issues" to q::deleteIssue, "material_issue_lines" to q::deleteIssueLine,
            "stock_adjustments" to q::deleteAdjustment, "albums" to q::deleteAlbum, "project_members" to q::deleteProjectMember,
        ),
        restore = ::restore,
    )

    private val _session = MutableStateFlow(loadCachedSession())
    override val session: StateFlow<Session?> = _session.asStateFlow()
    override val status: StateFlow<SyncStatus> get() = engine.status

    fun today(): LocalDate = LocalDate.now(clock)
    private fun now(): String = clock.instant().toString()
    private fun me(): User = _session.value?.user ?: throw AppError.NotAllowed

    override fun syncNow() { if (tokens.load() != null) engine.requestSync() }

    // ================================================================ session

    override suspend fun requestOtp(phone: String): Result<Unit> = call {
        auth.requestOtp(Validate.phone(phone).getOrThrow())
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<Session> = call {
        val p = Validate.phone(phone).getOrThrow()
        val t = try {
            auth.verifyOtp(p, code.trim())
        } catch (e: RemoteError.Rejected) {
            throw AppError.Invalid("code", AppError.Reason.WRONG_CODE)
        }
        api.rpc("accept_invites")
        loadSession(t.userId).also { syncNow() }
    }

    override suspend fun completeProfile(name: String, orgName: String?): Result<Session> = call {
        val current = _session.value ?: throw AppError.NotAllowed
        if (name.isBlank()) throw AppError.Invalid("name", AppError.Reason.REQUIRED)
        api.update("profiles", mapOf("id" to "eq.${current.user.id}"), buildJsonObject { put("name", name.trim()) })
        if (current.org == null) {
            api.rpc("create_organisation", buildJsonObject { put("p_name", orgName?.trim().orEmpty().ifBlank { "${name.trim()}'s sites" }) })
        }
        loadSession(current.user.id).also { syncNow() }
    }

    override suspend fun deleteAccount(): Result<Unit> = call {
        try {
            api.function("delete-account", buildJsonObject {})
        } catch (e: RemoteError.Rejected) {
            if (e.message.orEmpty().contains("owner_must_transfer")) throw AppError.OwnerMustTransfer
            throw e
        }
        signOut()
    }

    override fun signOut() {
        tokens.save(null)
        q.wipe()
        _session.value = null
    }

    /** Who the user is and which organisation they're in, from the server; cached for offline launches. */
    private suspend fun loadSession(userId: String): Session {
        val profile = api.select("profiles", mapOf("select" to "id,name,phone,email", "id" to "eq.$userId")).firstOrNull()?.jsonObject
        val membership = api.select("org_members", mapOf(
            "select" to "role,organisations(id,name,plan,logo_path)", "user_id" to "eq.$userId", "order" to "created_at.asc", "limit" to "1",
        )).firstOrNull()?.jsonObject
        val user = User(userId, profile?.strOrNull("name").orEmpty(), profile?.strOrNull("phone").orEmpty(), profile?.strOrNull("email"))
        val orgJson = membership?.get("organisations")?.jsonObject
        // a logo picked on this phone keeps showing from the phone's own copy
        val localLogo = q.kvGet("org_logo_local").executeAsOneOrNull()
        val org = orgJson?.let {
            Organisation(it.str("id"), it.str("name"), planOf(it.strOrNull("plan")),
                localLogo ?: it.strOrNull("logo_path")?.let { p -> "storage:org-assets/$p" })
        }
        val role = membership?.strOrNull("role")?.let { r -> OrgRole.entries.firstOrNull { it.name.equals(r, true) } }
        val s = Session(user, org, role)
        q.upsertPerson(PersonRow(user.id, user.name, user.phone, user.email))
        saveSession(s)
        return s
    }

    /** Remembers the session for offline launches and tells the screens. */
    private fun saveSession(s: Session) {
        q.kvPut("session", buildJsonObject {
            put("user_id", s.user.id); put("name", s.user.name); put("phone", s.user.phone); putOrNull("email", s.user.email)
            putOrNull("org_id", s.org?.id); putOrNull("org_name", s.org?.name); putOrNull("org_plan", s.org?.plan?.name)
            putOrNull("org_logo", s.org?.logoRef); putOrNull("org_role", s.orgRole?.name)
        }.toString())
        _session.value = s
    }

    private fun loadCachedSession(): Session? {
        if (tokens.load() == null) return null
        val o = q.kvGet("session").executeAsOneOrNull()?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } ?: return null
        val org = o.strOrNull("org_id")?.let { Organisation(it, o.strOrNull("org_name").orEmpty(), planOf(o.strOrNull("org_plan")), o.strOrNull("org_logo")) }
        return Session(User(o.str("user_id"), o.strOrNull("name").orEmpty(), o.strOrNull("phone").orEmpty(), o.strOrNull("email")), org,
            o.strOrNull("org_role")?.let { r -> OrgRole.entries.firstOrNull { it.name == r } })
    }

    override suspend fun updateProfile(name: String, email: String?): Result<Session> = call {
        val current = _session.value ?: throw AppError.NotAllowed
        if (name.isBlank()) throw AppError.Invalid("name", AppError.Reason.REQUIRED)
        val e = Validate.email(email).getOrThrow()
        val user = current.user.copy(name = name.trim(), email = e)
        db.transaction {
            q.upsertPerson(PersonRow(user.id, user.name, user.phone, user.email))
            engine.enqueueUpdate("profiles", user.id, mapOf("id" to "eq.${user.id}"), buildJsonObject {
                put("name", user.name); putOrNull("email", user.email)
            })
        }
        val next = current.copy(user = user)
        saveSession(next)
        syncNow()
        next
    }

    override suspend fun updateOrganisation(name: String, logoUri: String?): Result<Session> = call {
        val current = _session.value ?: throw AppError.NotAllowed
        val org = current.org ?: throw AppError.NotAllowed
        if (current.orgRole != OrgRole.OWNER && current.orgRole != OrgRole.ADMIN) throw AppError.NotAllowed
        val n = name.trim()
        if (n.isEmpty()) throw AppError.Invalid("name", AppError.Reason.REQUIRED)
        if (n.length > Validate.NAME_MAX) throw AppError.Invalid("name", AppError.Reason.TOO_LONG)
        val path = logoUri?.let { "${org.id}/logo-${newId()}.jpg" }
        db.transaction {
            if (logoUri != null && path != null) {
                q.kvPut("org_logo_local", logoUri)
                engine.enqueueUpload(org.id, "org-assets", path, logoUri, "image/jpeg")
            }
            engine.enqueueUpdate("organisations", org.id, mapOf("id" to "eq.${org.id}"), buildJsonObject {
                put("name", n); if (path != null) put("logo_path", path)
            })
        }
        val next = current.copy(org = org.copy(name = n, logoRef = logoUri ?: org.logoRef))
        saveSession(next)
        syncNow()
        next
    }

    private fun planOf(s: String?): Plan = Plan.entries.firstOrNull { it.name.equals(s, true) } ?: Plan.FREE

    // ================================================================ people

    override fun people(): Flow<Map<String, User>> =
        q.people().asFlow().mapToList(io).map { rows -> rows.associate { it.id to User(it.id, it.name, it.phone.orEmpty(), it.email) } }

    private fun personName(id: String?): String = id?.let { q.person(it).executeAsOneOrNull()?.name }.orEmpty()

    // ================================================================ projects

    override fun projects(): Flow<List<Project>> = q.projects().asFlow().mapToList(io).map { it.map(::project) }
    override fun project(id: String): Flow<Project?> = q.project(id).asFlow().mapToOneOrNull(io).map { it?.let(::project) }

    override suspend fun create(input: NewProject): Result<Project> = call {
        val s = _session.value ?: throw AppError.NotAllowed
        val org = s.org ?: throw AppError.NotAllowed
        val v = Validate.project(input).getOrThrow()
        // the server enforces the plan too; checking here avoids a project that appears and then vanishes
        val active = q.projects().executeAsList().count { it.org_id == org.id && it.status != "archived" }
        if (org.plan == Plan.FREE && active >= FREE_PROJECTS) throw AppError.PlanLimit("projects")
        val id = newId()
        val imagePath = v.imageUri?.let { "$id/cover-${newId()}.jpg" }
        val row = ProjectRow(id, org.id, v.name, "active", v.address, v.startDate?.toString(), v.endDate?.toString(), 0.0, 8.0, 1,
            imagePath, v.imageUri)
        db.transaction {
            q.upsertProject(row)
            engine.enqueueUpsert("projects", row.id, buildJsonObject {
                put("id", row.id); put("org_id", row.org_id); put("name", row.name); putOrNull("address", row.address)
                putOrNull("start_date", row.start_date); putOrNull("end_date", row.end_date); put("created_by", s.user.id)
            })
            // the project first (storage checks it exists), then the photo, then the row pointing at it
            if (v.imageUri != null && imagePath != null) enqueueCover(row.id, imagePath, v.imageUri)
        }
        syncNow()
        project(row)
    }

    override suspend fun setImage(projectId: String, uri: String): Result<Unit> = call {
        q.project(projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val path = "$projectId/cover-${newId()}.jpg"
        db.transaction {
            q.setProjectImage(path, uri, projectId)
            enqueueCover(projectId, path, uri)
        }
        syncNow()
    }

    private fun enqueueCover(projectId: String, path: String, uri: String) {
        engine.enqueueUpload(projectId, "project-media", path, uri, "image/jpeg")
        engine.enqueueUpdate("projects", projectId, mapOf("id" to "eq.$projectId"), buildJsonObject { put("image_path", path) })
    }

    override suspend fun updateSettings(projectId: String, shiftHours: Double, attendanceRemarks: Boolean): Result<Unit> = call {
        if (shiftHours <= 0 || shiftHours > 24) throw AppError.Invalid("shiftHours", AppError.Reason.NOT_POSITIVE)
        db.transaction {
            q.updateSettings(shiftHours, if (attendanceRemarks) 1 else 0, projectId)
            engine.enqueueUpsert("project_settings", projectId, buildJsonObject {
                put("project_id", projectId); put("shift_hours", shiftHours); put("attendance_remarks", attendanceRemarks)
            }, onConflict = "project_id")
        }
        syncNow()
    }

    private fun project(r: ProjectRow) = Project(
        r.id, r.org_id, r.name, ProjectStatus.entries.firstOrNull { it.name.equals(r.status, true) } ?: ProjectStatus.ACTIVE,
        r.address, r.start_date?.let(LocalDate::parse), r.end_date?.let(LocalDate::parse), r.progress_pct.toInt(), r.shift_hours,
        r.attendance_remarks != 0L,
        r.image_local ?: r.image_path?.let { "storage:project-media/$it" },
    )

    // ================================================================ tasks

    override fun tasks(projectId: String): Flow<List<Task>> = q.tasks(projectId).asFlow().mapToList(io).map { it.map(::task) }
    override fun task(id: String): Flow<Task?> = q.task(id).asFlow().mapToOneOrNull(io).map { it?.let(::task) }

    override fun updates(taskId: String): Flow<List<TaskUpdate>> =
        // the second query only signals "photos changed" (an upload finished, a pull brought a storage path)
        combine(q.updatesForTask(taskId).asFlow().mapToList(io), q.mediaForProject("").asFlow().mapToList(io)) { rows, _ ->
            rows.map(::update)
        }

    override fun updatesInRange(projectId: String, from: LocalDate, to: LocalDate): Flow<List<TaskUpdate>> =
        q.updatesInRange(projectId, from.toString(), to.toString()).asFlow().mapToList(io).map { it.map(::update) }

    override suspend fun create(input: NewTask): Result<Task> = call {
        val user = me()
        val v = Validate.task(input).getOrThrow()
        q.project(v.projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val row = TaskRow(newId(), v.projectId, null, v.name, v.category, v.unit, v.plannedQty, 0.0, 0.0,
            v.startDate?.toString(), v.dueDate?.toString(), v.assigneeId, "not_started", null)
        db.transaction {
            q.upsertTask(row)
            q.updateProjectProgress(v.projectId)
            engine.enqueueUpsert("tasks", row.id, buildJsonObject {
                put("id", row.id); put("project_id", row.project_id); put("name", row.name); put("category", row.category)
                putOrNull("unit", row.unit); putOrNull("planned_qty", row.planned_qty); putOrNull("start_date", row.start_date)
                putOrNull("due_date", row.due_date); putOrNull("assignee_id", row.assignee_id); put("created_by", user.id)
            })
        }
        syncNow()
        task(row)
    }

    override suspend fun addUpdate(input: NewTaskUpdate): Result<TaskUpdate> = call {
        val user = me()
        val v = Validate.taskUpdate(input).getOrThrow()
        val t = q.task(v.taskId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val row = UpdateRow(newId(), t.id, t.project_id, user.id, v.workDate.toString(), v.qtyDone, v.progressPct?.toDouble(), v.remarks, now())
        db.transaction {
            q.upsertUpdate(row)
            engine.enqueueUpsert("task_updates", row.id, buildJsonObject {
                put("id", row.id); put("task_id", row.task_id); put("project_id", row.project_id); put("author_id", user.id)
                put("work_date", row.work_date); putOrNull("qty_done", row.qty_done); putOrNull("progress_pct", v.progressPct)
                putOrNull("remarks", row.remarks)
            })
            for (uri in v.photos) {
                val mediaId = newId()
                val path = "${t.project_id}/$mediaId.jpg"
                q.upsertMedia(MediaRow(mediaId, t.project_id, row.id, uri, null, now(), null, user.id))
                // the file first, then the row that points at it
                engine.enqueueUpload(mediaId, "project-media", path, uri, "image/jpeg")
                engine.enqueueUpsert("media", mediaId, buildJsonObject {
                    put("id", mediaId); put("project_id", t.project_id); put("task_update_id", row.id); put("kind", "photo")
                    put("storage_path", path); put("taken_at", now()); put("uploaded_by", user.id)
                })
            }
            // show the server's roll-up straight away; the pulled task row confirms it
            val updated = Progress.fromUpdates(task(t), q.updatesForTask(t.id).executeAsList().map(::update))
            q.updateTaskProgress(updated.doneQty, updated.progressPct.toDouble(), status(updated.status), t.id)
            q.updateProjectProgress(t.project_id)
        }
        syncNow()
        update(row)
    }

    private fun task(r: TaskRow) = Task(
        id = r.id, projectId = r.project_id, number = r.number?.toInt(), name = r.name, category = r.category, unit = r.unit,
        plannedQty = r.planned_qty, doneQty = r.done_qty, progressPct = r.progress_pct.toInt(),
        startDate = r.start_date?.let(LocalDate::parse), dueDate = r.due_date?.let(LocalDate::parse), assigneeId = r.assignee_id,
        status = TaskStatus.entries.firstOrNull { it.name.equals(r.status, true) } ?: TaskStatus.NOT_STARTED,
        hasWorkOrder = r.work_order_id != null,
    )

    private fun status(s: TaskStatus) = s.name.lowercase()

    private fun update(r: UpdateRow): TaskUpdate {
        val photos = q.mediaForUpdate(r.id).executeAsList().map { m -> m.local_uri ?: "storage:project-media/${m.storage_path}" }
        return TaskUpdate(r.id, r.task_id, r.project_id, r.author_id.orEmpty(), LocalDate.parse(r.work_date), r.qty_done,
            r.progress_pct?.toInt(), r.remarks, photos, SyncEngine.parseInstant(r.created_at))
    }

    // ================================================================ attendance

    override fun workers(projectId: String): Flow<List<Worker>> = q.workers(projectId).asFlow().mapToList(io).map { it.map(::worker) }

    override fun day(projectId: String, date: LocalDate): Flow<List<Attendance>> =
        q.attendanceOn(projectId, date.toString()).asFlow().mapToList(io).map { it.map(::attendance) }

    override fun range(projectId: String, from: LocalDate, to: LocalDate): Flow<List<Attendance>> =
        q.attendanceBetween(projectId, from.toString(), to.toString()).asFlow().mapToList(io).map { it.map(::attendance) }

    override suspend fun addWorker(input: NewWorker): Result<Worker> = call {
        val v = Validate.worker(input).getOrThrow()
        val p = q.project(v.projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val row = WorkerRow(newId(), p.org_id, p.id, v.name, v.skill, v.phone, v.wageType.name.lowercase(), v.wagePaise, v.otRatePaise, 1)
        db.transaction {
            q.upsertWorker(row)
            engine.enqueueUpsert("workers", row.id, buildJsonObject {
                put("id", row.id); put("org_id", row.org_id); put("project_id", row.project_id); put("name", row.name)
                putOrNull("skill", row.skill); putOrNull("phone", row.phone); put("wage_type", row.wage_type)
                put("wage_paise", row.wage_paise); put("ot_rate_paise", row.ot_rate_paise)
            })
        }
        syncNow()
        worker(row)
    }

    override suspend fun mark(
        projectId: String, workerId: String, date: LocalDate, status: AttendanceStatus, otHours: Double, remarks: String?,
    ): Result<Unit> = call {
        val user = me()
        if (date.isAfter(today())) throw AppError.Invalid("date", AppError.Reason.END_BEFORE_START)
        if (otHours < 0 || otHours > 16) throw AppError.Invalid("otHours", AppError.Reason.NOT_POSITIVE)
        val note = remarks?.trim()?.ifBlank { null }
        val existing = q.attendanceOne(projectId, workerId, date.toString()).executeAsOneOrNull()
        val key = attendanceKey(projectId, workerId, date.toString())
        db.transaction {
            q.upsertAttendance(AttendanceRow(existing?.id ?: newId(), projectId, workerId, date.toString(), status.name.lowercase(), otHours, note))
            // no id: phones merge on (project, worker, day), and the pulled row brings the server's id
            engine.enqueueUpsert("attendance", key, buildJsonObject {
                put("project_id", projectId); put("worker_id", workerId); put("work_date", date.toString())
                put("status", status.name.lowercase()); put("ot_hours", otHours); putOrNull("remarks", note); put("marked_by", user.id)
            }, onConflict = "project_id,worker_id,work_date")
        }
        syncNow()
    }

    override suspend fun clear(projectId: String, workerId: String, date: LocalDate): Result<Unit> = call {
        db.transaction {
            q.clearAttendance(projectId, workerId, date.toString())
            engine.enqueueDelete("attendance", attendanceKey(projectId, workerId, date.toString()), mapOf(
                "project_id" to "eq.$projectId", "worker_id" to "eq.$workerId", "work_date" to "eq.$date",
            ))
        }
        syncNow()
    }

    private fun attendanceKey(p: String, w: String, d: String) = "$p|$w|$d"

    private fun worker(r: WorkerRow) = Worker(r.id, r.project_id, r.name, r.skill, r.phone,
        WageType.entries.firstOrNull { it.name.equals(r.wage_type, true) } ?: WageType.DAILY, r.wage_paise, r.ot_rate_paise, r.active != 0L)

    private fun attendance(r: AttendanceRow) = Attendance(r.project_id, r.worker_id, LocalDate.parse(r.work_date),
        AttendanceStatus.entries.firstOrNull { it.name.equals(r.status, true) } ?: AttendanceStatus.PRESENT, r.ot_hours, r.remarks)

    // ================================================================ reports

    override fun reports(projectId: String): Flow<List<Report>> =
        q.reports(projectId, now()).asFlow().mapToList(io).map { it.map(::report) }

    override fun report(id: String): Flow<Report?> = q.report(id).asFlow().mapToOneOrNull(io).map { it?.let(::report) }

    override suspend fun request(input: NewReport): Result<Report> = call {
        val user = me()
        Validate.range(input.from, input.to).getOrThrow()
        val created = clock.instant()
        val parts = partsJson(input.parts)
        val row = ReportRow(newId(), input.projectId, input.type.name.lowercase(), input.from.toString(), input.to.toString(),
            parts.toString(), "queued", null, null, created.toString(), created.plus(REPORT_LIFETIME).toString())
        db.transaction {
            q.upsertReport(row)
            engine.enqueueUpsert("reports", row.id, buildJsonObject {
                put("id", row.id); put("project_id", row.project_id); put("type", row.type); put("from_date", row.from_date)
                put("to_date", row.to_date); put("parts", parts); put("status", "queued"); put("created_by", user.id)
            })
        }
        syncNow()
        report(row)
    }

    override suspend fun markReady(id: String, filePath: String): Result<Unit> = call {
        val r = q.report(id).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val path = "${r.project_id}/$id.pdf"
        db.transaction {
            q.setReportFile("ready", path, filePath, id)
            engine.enqueueUpload(id, "reports", path, filePath, "application/pdf")
            engine.enqueueUpdate("reports", id, mapOf("id" to "eq.$id"), buildJsonObject { put("status", "ready"); put("storage_path", path) })
        }
        syncNow()
    }

    override suspend fun markFailed(id: String): Result<Unit> = call {
        val r = q.report(id).executeAsOneOrNull() ?: throw AppError.NotAllowed
        db.transaction {
            q.setReportFile("failed", r.storage_path, r.local_file, id)
            engine.enqueueUpdate("reports", id, mapOf("id" to "eq.$id"), buildJsonObject { put("status", "failed") })
        }
        syncNow()
    }

    override suspend fun fetchFile(id: String): Result<String> = call {
        val r = q.report(id).executeAsOneOrNull() ?: throw AppError.NotAllowed
        r.local_file?.takeIf { File(it).exists() }?.let { return@call it }
        val path = r.storage_path ?: throw AppError.Unknown("not ready")
        val local = files.saveReport(id, api.download("reports", path))
        q.setReportFile(r.status, r.storage_path, local, id)
        local
    }

    private fun partsJson(p: ReportParts) = buildJsonObject {
        put("task_updates", p.taskUpdates); put("photos", p.photos); put("attendance", p.attendance); put("wages", p.wages); put("material", p.material)
    }

    private fun report(r: ReportRow): Report {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(r.parts).jsonObject
        return Report(
            r.id, r.project_id, if (r.type == "summary") ReportType.SUMMARY else ReportType.DETAILED,
            LocalDate.parse(r.from_date), LocalDate.parse(r.to_date),
            ReportParts(parts.bool("task_updates", true), parts.bool("photos", true), parts.bool("attendance", true), parts.bool("wages", false), parts.bool("material", false)),
            ReportStatus.entries.firstOrNull { it.name.equals(r.status, true) } ?: ReportStatus.QUEUED,
            SyncEngine.parseInstant(r.created_at), SyncEngine.parseInstant(r.expires_at), r.local_file,
        )
    }

    // ================================================================ material

    override fun catalogue(): Flow<List<Material>> = q.catalogue().asFlow().mapToList(io).map { it.map(::material) }

    override fun stock(projectId: String): Flow<List<StockItem>> =
        combine(q.movements(projectId).asFlow().mapToList(io), q.catalogue().asFlow().mapToList(io)) { moves, cat ->
            val byId = cat.associateBy { it.id }
            moves.groupBy { it.material_id }.mapNotNull { (id, ms) ->
                byId[id]?.let { StockItem(material(it), round3(ms.sumOf { m -> m.qty }), ms.maxOf { m -> m.day }.let(LocalDate::parse)) }
            }.sortedBy { it.material.label.lowercase() }
        }

    override fun history(projectId: String, materialId: String): Flow<List<StockEntry>> =
        q.movements(projectId).asFlow().mapToList(io).map { rows -> rows.filter { it.material_id == materialId }.map(::entry) }

    // the movements query only signals "a line changed"; lines are read per document
    override fun grns(projectId: String): Flow<List<Grn>> =
        combine(q.grns(projectId).asFlow().mapToList(io), q.movements(projectId).asFlow().mapToList(io)) { rows, _ -> rows.map(::grn) }

    override fun issues(projectId: String): Flow<List<MaterialIssue>> =
        combine(q.issues(projectId).asFlow().mapToList(io), q.movements(projectId).asFlow().mapToList(io)) { rows, _ -> rows.map(::issue) }

    override suspend fun addMaterial(input: NewMaterial): Result<Material> = call {
        val org = _session.value?.org ?: throw AppError.NotAllowed
        val v = Validate.material(input).getOrThrow()
        val id = Material.idFor(org.id, v.name, v.spec, v.uom)
        q.material(id).executeAsOneOrNull()?.let { return@call material(it) }
        val row = MaterialRow(id, org.id, v.name, v.spec, v.uom, v.category)
        db.transaction {
            q.upsertMaterial(row)
            // the id comes from the name, so the same material added on another phone merges into this row
            engine.enqueueUpsert("materials", id, buildJsonObject {
                put("id", id); put("org_id", org.id); put("name", v.name); put("spec", v.spec); put("uom", v.uom); putOrNull("category", v.category)
            })
        }
        syncNow()
        material(row)
    }

    override suspend fun receive(input: NewGrn): Result<Grn> = call {
        val user = me()
        val lines = Validate.lines(input.lines).getOrThrow()
        Validate.notFuture(input.receivedOn, today()).getOrThrow()
        q.project(input.projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        if (lines.any { q.material(it.materialId).executeAsOneOrNull() == null }) throw AppError.NotAllowed
        val row = GrnRow(newId(), input.projectId, null, input.receivedOn.toString(), input.challanNo?.trim()?.ifBlank { null },
            input.notes?.trim()?.ifBlank { null }, user.id, now())
        db.transaction {
            q.upsertGrn(row)
            engine.enqueueUpsert("grns", row.id, buildJsonObject {
                put("id", row.id); put("project_id", row.project_id); put("received_on", row.received_on)
                putOrNull("challan_no", row.challan_no); putOrNull("notes", row.notes); put("received_by", user.id)
            })
            // header first, then its lines: the outbox sends in order
            for (l in lines) {
                val line = GrnLineRow(newId(), row.id, l.materialId, l.qty)
                q.upsertGrnLine(line)
                engine.enqueueUpsert("grn_lines", line.id, buildJsonObject {
                    put("id", line.id); put("grn_id", row.id); put("material_id", l.materialId); put("qty", l.qty)
                })
            }
        }
        syncNow()
        grn(row)
    }

    override suspend fun issue(input: NewMaterialIssue): Result<MaterialIssue> = call {
        val user = me()
        val lines = Validate.lines(input.lines).getOrThrow()
        Validate.notFuture(input.issuedOn, today()).getOrThrow()
        val task = q.task(input.taskId).executeAsOneOrNull()
        if (task == null || task.project_id != input.projectId) throw AppError.Invalid("task", AppError.Reason.REQUIRED)
        Stock.canIssue(lines, inStock(input.projectId)).getOrThrow()
        val row = IssueRow(newId(), input.projectId, null, task.id, null, input.issuedOn.toString(), user.id, now())
        db.transaction {
            q.upsertIssue(row)
            engine.enqueueUpsert("material_issues", row.id, buildJsonObject {
                put("id", row.id); put("project_id", row.project_id); put("task_id", task.id); put("issued_on", row.issued_on)
                put("issued_by", user.id)
            })
            for (l in lines) {
                val line = IssueLineRow(newId(), row.id, l.materialId, l.qty)
                q.upsertIssueLine(line)
                engine.enqueueUpsert("material_issue_lines", line.id, buildJsonObject {
                    put("id", line.id); put("material_issue_id", row.id); put("material_id", l.materialId); put("qty", l.qty)
                })
            }
        }
        syncNow()
        issue(row)
    }

    override suspend fun adjust(input: NewAdjustment): Result<Unit> = call {
        val user = me()
        Validate.notFuture(input.date, today()).getOrThrow()
        q.material(input.materialId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val current = inStock(input.projectId)[input.materialId] ?: 0.0
        val notPositive = AppError.Invalid("qty", AppError.Reason.NOT_POSITIVE)
        val (kind, qty) = when (input.kind) {
            AdjustmentKind.OPENING -> if (input.qty > 0) "opening" to input.qty else throw notPositive
            AdjustmentKind.USED -> {
                if (!(input.qty > 0)) throw notPositive
                Stock.canIssue(listOf(MaterialLine(input.materialId, input.qty)), mapOf(input.materialId to current)).getOrThrow()
                "consumption" to -input.qty
            }
            AdjustmentKind.COUNTED -> {
                if (input.qty < 0 || input.qty.isNaN()) throw notPositive
                "correction" to (Stock.correction(current, input.qty) ?: return@call)
            }
        }
        val row = AdjustmentRow(newId(), input.projectId, null, input.materialId, kind, qty, input.taskId, input.date.toString(),
            input.note?.trim()?.ifBlank { null }, now())
        db.transaction {
            q.upsertAdjustment(row)
            engine.enqueueUpsert("stock_adjustments", row.id, buildJsonObject {
                put("id", row.id); put("project_id", row.project_id); put("material_id", row.material_id); put("kind", kind)
                put("qty", qty); putOrNull("task_id", row.task_id); put("adjusted_on", row.adjusted_on); putOrNull("note", row.note)
                put("created_by", user.id)
            })
        }
        syncNow()
    }

    private fun inStock(projectId: String): Map<String, Double> =
        q.movements(projectId).executeAsList().groupBy { it.material_id }.mapValues { (_, ms) -> round3(ms.sumOf { it.qty }) }

    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0

    private fun material(r: MaterialRow) = Material(r.id, r.org_id, r.name, r.spec, r.uom, r.category)

    private fun grn(r: GrnRow) = Grn(r.id, r.project_id, r.number?.toInt(), LocalDate.parse(r.received_on), r.challan_no, r.notes,
        r.received_by, q.grnLines(r.id).executeAsList().map { MaterialLine(it.material_id, it.qty) }, SyncEngine.parseInstant(r.created_at))

    private fun issue(r: IssueRow) = MaterialIssue(r.id, r.project_id, r.number?.toInt(), r.task_id.orEmpty(), LocalDate.parse(r.issued_on),
        r.issued_by, q.issueLines(r.id).executeAsList().map { MaterialLine(it.material_id, it.qty) }, SyncEngine.parseInstant(r.created_at))

    private fun entry(m: MovementRow) = StockEntry(
        m.entry_id,
        when (m.kind) {
            "received" -> StockEntryKind.RECEIVED
            "issued" -> StockEntryKind.ISSUED
            "opening" -> StockEntryKind.OPENING
            "consumption" -> StockEntryKind.USED
            else -> StockEntryKind.CORRECTED
        },
        m.qty, LocalDate.parse(m.day), m.number?.toInt(), m.task_id, m.note, SyncEngine.parseInstant(m.created_at),
    )

    // ================================================================ gallery

    override fun photos(projectId: String): Flow<List<Photo>> = q.photos(projectId).asFlow().mapToList(io).map { rows ->
        rows.map { m ->
            Photo(m.id, m.project_id, m.local_uri ?: "storage:project-media/${m.storage_path}", SyncEngine.parseInstant(m.taken_at),
                m.album_id, m.task_id, m.uploaded_by)
        }
    }

    override fun albums(projectId: String): Flow<List<Album>> =
        combine(q.albums(projectId).asFlow().mapToList(io), photos(projectId)) { albums, photos ->
            albums.map { a ->
                val mine = photos.filter { it.albumId == a.id }
                Album(a.id, a.project_id, a.name, mine.size, mine.firstOrNull()?.ref)
            }
        }

    override suspend fun addPhotos(projectId: String, uris: List<String>, albumId: String?): Result<Int> = call {
        val user = me()
        q.project(projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        if (albumId != null && q.album(albumId).executeAsOneOrNull()?.project_id != projectId) throw AppError.NotAllowed
        if (uris.isEmpty()) throw AppError.Invalid("photos", AppError.Reason.REQUIRED)
        val start = clock.instant()
        db.transaction {
            uris.forEachIndexed { i, uri ->
                val id = newId()
                val path = "$projectId/$id.jpg"
                val taken = start.plusMillis(i.toLong()).toString()
                q.upsertMedia(MediaRow(id, projectId, null, uri, null, taken, albumId, user.id))
                // the file first, then the row that points at it
                engine.enqueueUpload(id, "project-media", path, uri, "image/jpeg")
                engine.enqueueUpsert("media", id, buildJsonObject {
                    put("id", id); put("project_id", projectId); put("kind", "photo"); put("storage_path", path)
                    put("taken_at", taken); putOrNull("album_id", albumId); put("uploaded_by", user.id)
                })
            }
        }
        syncNow()
        uris.size
    }

    override suspend fun createAlbum(projectId: String, name: String): Result<Album> = call {
        val n = Validate.albumName(name).getOrThrow()
        q.project(projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val id = Album.idFor(projectId, n)
        q.album(id).executeAsOneOrNull()?.let { return@call Album(it.id, it.project_id, it.name) }
        db.transaction {
            q.upsertAlbum(AlbumRow(id, projectId, n))
            engine.enqueueUpsert("albums", id, buildJsonObject { put("id", id); put("project_id", projectId); put("name", n) })
        }
        syncNow()
        Album(id, projectId, n)
    }

    override suspend fun moveToAlbum(photoIds: List<String>, albumId: String?): Result<Unit> = call {
        if (albumId != null) q.album(albumId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        db.transaction {
            for (id in photoIds) {
                q.mediaOne(id).executeAsOneOrNull() ?: continue
                q.setMediaAlbum(albumId, id)
                engine.enqueueUpdate("media", id, mapOf("id" to "eq.$id"), buildJsonObject { putOrNull("album_id", albumId) })
            }
        }
        syncNow()
    }

    // ================================================================ team

    private fun role(s: String?): ProjectRole? = ProjectRole.entries.firstOrNull { it.name.equals(s, true) }
    private fun orgRole(s: String?): OrgRole? = OrgRole.entries.firstOrNull { it.name.equals(s, true) }

    override fun team(projectId: String): Flow<Team> {
        val orgId = q.project(projectId).executeAsOneOrNull()?.org_id ?: _session.value?.org?.id.orEmpty()
        return combine(
            q.people().asFlow().mapToList(io),
            q.orgMembers(orgId).asFlow().mapToList(io),
            q.projectMembers(projectId).asFlow().mapToList(io),
            q.pendingInvites(projectId).asFlow().mapToList(io),
            _session,
        ) { people, orgMembers, siteMembers, invites, session ->
            val byId = people.associateBy { it.id }
            val company = orgMembers.mapNotNull { m ->
                val r = orgRole(m.role)
                if (r != OrgRole.OWNER && r != OrgRole.ADMIN) return@mapNotNull null
                val p = byId[m.user_id]
                Member(m.user_id, p?.name.orEmpty(), p?.phone.orEmpty(), ProjectRole.MANAGER, r)
            }.sortedBy { it.orgRole }
            val site = siteMembers.filter { m -> company.none { it.userId == m.user_id } }.map { m ->
                val p = byId[m.user_id]
                Member(m.user_id, p?.name.orEmpty(), p?.phone.orEmpty(), role(m.role) ?: ProjectRole.VIEWER)
            }.sortedWith(compareBy({ it.role.ordinal }, { it.name.lowercase() }))
            val me = session?.user?.id
            val canManage = session?.orgRole == OrgRole.OWNER || session?.orgRole == OrgRole.ADMIN ||
                siteMembers.any { it.user_id == me && role(it.role) == ProjectRole.MANAGER }
            Team(company + site, invites.map { PendingInvite(it.id, it.phone, role(it.project_role), SyncEngine.parseInstant(it.created_at)) }, canManage)
        }
    }

    override suspend fun invite(projectId: String, phone: String, role: ProjectRole): Result<Boolean> = call {
        val p = Validate.phone(phone).getOrThrow()
        val project = q.project(projectId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        val member = q.people().executeAsList().firstOrNull { it.phone == p }
        if (member != null && (q.projectMemberOne(projectId, member.id).executeAsOneOrNull() != null ||
                q.orgMembers(project.org_id).executeAsList().any { it.user_id == member.id && orgRole(it.role) in setOf(OrgRole.OWNER, OrgRole.ADMIN) })) {
            throw AppError.Invalid("phone", AppError.Reason.ALREADY_MEMBER)
        }
        // the server checks who may invite, sends the SMS and rate-limits; it needs a connection
        val res = try {
            api.function("invite", buildJsonObject {
                put("phone", p); put("org_id", project.org_id); put("project_id", projectId)
                put("org_role", "member"); put("project_role", role.name.lowercase())
            }).jsonObject
        } catch (e: RemoteError.Rejected) {
            throw if (e.code == "bad_phone") AppError.Invalid("phone", AppError.Reason.BAD_PHONE) else e
        }
        res.strOrNull("invite_id")?.let { id -> q.upsertInvite(InviteRow(id, project.org_id, projectId, p, role.name.lowercase(), null, now())) }
        syncNow()
        res.bool("sms_sent", false)
    }

    override suspend fun setRole(projectId: String, userId: String, role: ProjectRole): Result<Unit> = call {
        val row = q.projectMemberOne(projectId, userId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        db.transaction {
            q.setProjectMemberRole(role.name.lowercase(), row.id)
            engine.enqueueUpdate("project_members", row.id, mapOf("id" to "eq.${row.id}"), buildJsonObject { put("role", role.name.lowercase()) })
        }
        syncNow()
    }

    override suspend fun remove(projectId: String, userId: String): Result<Unit> = call {
        val row = q.projectMemberOne(projectId, userId).executeAsOneOrNull() ?: throw AppError.NotAllowed
        db.transaction {
            q.deleteProjectMember(row.id)
            engine.enqueueDelete("project_members", row.id, mapOf("id" to "eq.${row.id}"))
        }
        syncNow()
    }

    // ================================================================ feed

    override fun feed(projectId: String): Flow<List<ActivityEvent>> =
        q.activity(projectId).asFlow().mapToList(io).map { rows ->
            rows.map { a ->
                ActivityEvent(a.id, a.project_id, a.actor_id, personName(a.actor_id),
                    ActivityVerb.entries.firstOrNull { it.name.equals(a.verb, true) } ?: ActivityVerb.UPDATED_PROGRESS,
                    a.entity_id, a.summary, SyncEngine.parseInstant(a.created_at))
            }
        }

    // ================================================================ pulling: server rows -> local rows

    private fun pullSpecs(): List<SyncEngine.PullSpec> = listOf(
        SyncEngine.PullSpec("profiles", "id,name,phone,email,updated_at", { o ->
            q.upsertPerson(PersonRow(o.str("id"), o.strOrNull("name").orEmpty(), o.strOrNull("phone"), o.strOrNull("email")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("projects", "id,org_id,name,status,address,start_date,end_date,progress_pct,image_path,updated_at", { o ->
            val old = q.project(o.str("id")).executeAsOneOrNull()
            val path = o.strOrNull("image_path")
            q.upsertProject(ProjectRow(o.str("id"), o.str("org_id"), o.str("name"), o.str("status"), o.strOrNull("address"),
                o.strOrNull("start_date"), o.strOrNull("end_date"), o.dbl("progress_pct"), old?.shift_hours ?: 8.0, old?.attendance_remarks ?: 1,
                // keep this phone's own copy while the path is the one it uploaded
                path, old?.image_local?.takeIf { old.image_path == path }))
        }, { it.str("id") }),
        SyncEngine.PullSpec("project_settings", "project_id,shift_hours,attendance_remarks,updated_at", { o ->
            q.updateSettings(o.dbl("shift_hours", 8.0), if (o.bool("attendance_remarks", true)) 1 else 0, o.str("project_id"))
        }, { it.str("project_id") }),
        SyncEngine.PullSpec("tasks", "id,project_id,number,name,category,unit,planned_qty,done_qty,progress_pct,start_date,due_date,assignee_id,status,work_order_id,updated_at", { o ->
            q.upsertTask(TaskRow(o.str("id"), o.str("project_id"), o.lngOrNull("number"), o.str("name"), o.strOrNull("category") ?: "General",
                o.strOrNull("unit"), o.dblOrNull("planned_qty"), o.dbl("done_qty"), o.dbl("progress_pct"), o.strOrNull("start_date"),
                o.strOrNull("due_date"), o.strOrNull("assignee_id"), o.str("status"), o.strOrNull("work_order_id")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("task_updates", "id,task_id,project_id,author_id,work_date,qty_done,progress_pct,remarks,created_at,updated_at", { o ->
            q.upsertUpdate(UpdateRow(o.str("id"), o.str("task_id"), o.str("project_id"), o.strOrNull("author_id"), o.str("work_date"),
                o.dblOrNull("qty_done"), o.dblOrNull("progress_pct"), o.strOrNull("remarks"), o.str("created_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("media", "id,project_id,task_update_id,storage_path,taken_at,album_id,uploaded_by,updated_at", { o ->
            // keep this phone's own file: it shows without a download
            val old = q.mediaOne(o.str("id")).executeAsOneOrNull()
            q.upsertMedia(MediaRow(o.str("id"), o.str("project_id"), o.strOrNull("task_update_id"), old?.local_uri,
                o.strOrNull("storage_path"), o.str("taken_at"), o.strOrNull("album_id"), o.strOrNull("uploaded_by")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("org_members", "id,org_id,user_id,role,updated_at", { o ->
            q.upsertOrgMember(OrgMemberRow(o.str("id"), o.str("org_id"), o.str("user_id"), o.str("role")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("project_members", "id,project_id,user_id,role,updated_at", { o ->
            q.upsertProjectMember(ProjectMemberRow(o.str("id"), o.str("project_id"), o.str("user_id"), o.str("role")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("invites", "id,org_id,project_id,phone,project_role,accepted_at,created_at,updated_at", { o ->
            q.upsertInvite(InviteRow(o.str("id"), o.str("org_id"), o.strOrNull("project_id"), o.str("phone"), o.strOrNull("project_role"),
                o.strOrNull("accepted_at"), o.str("created_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("albums", "id,project_id,name,updated_at", { o ->
            q.upsertAlbum(AlbumRow(o.str("id"), o.str("project_id"), o.str("name")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("workers", "id,org_id,project_id,name,skill,phone,wage_type,wage_paise,ot_rate_paise,active,updated_at", { o ->
            q.upsertWorker(WorkerRow(o.str("id"), o.str("org_id"), o.str("project_id"), o.str("name"), o.strOrNull("skill"),
                o.strOrNull("phone"), o.str("wage_type"), o.lng("wage_paise"), o.lng("ot_rate_paise"), if (o.bool("active", true)) 1 else 0))
        }, { it.str("id") }),
        SyncEngine.PullSpec("attendance", "id,project_id,worker_id,work_date,status,ot_hours,remarks,updated_at", { o ->
            // replaces the local row for the same worker and day, taking the server's id
            q.clearAttendance(o.str("project_id"), o.str("worker_id"), o.str("work_date"))
            q.upsertAttendance(AttendanceRow(o.str("id"), o.str("project_id"), o.str("worker_id"), o.str("work_date"), o.str("status"),
                o.dbl("ot_hours"), o.strOrNull("remarks")))
        }, { attendanceKey(it.str("project_id"), it.str("worker_id"), it.str("work_date")) }),
        SyncEngine.PullSpec("reports", "id,project_id,type,from_date,to_date,parts,status,storage_path,created_at,expires_at,updated_at", { o ->
            val old = q.report(o.str("id")).executeAsOneOrNull()
            q.upsertReport(ReportRow(o.str("id"), o.str("project_id"), o.str("type"), o.str("from_date"), o.str("to_date"),
                o["parts"]?.toString() ?: "{}", o.str("status"), o.strOrNull("storage_path"), old?.local_file, o.str("created_at"), o.str("expires_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("materials", "id,org_id,name,spec,uom,category,updated_at", { o ->
            q.upsertMaterial(MaterialRow(o.str("id"), o.str("org_id"), o.str("name"), o.strOrNull("spec").orEmpty(), o.str("uom"), o.strOrNull("category")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("grns", "id,project_id,number,received_on,challan_no,notes,received_by,created_at,updated_at", { o ->
            q.upsertGrn(GrnRow(o.str("id"), o.str("project_id"), o.lngOrNull("number"), o.str("received_on"), o.strOrNull("challan_no"),
                o.strOrNull("notes"), o.strOrNull("received_by"), o.str("created_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("grn_lines", "id,grn_id,material_id,qty,updated_at", { o ->
            q.upsertGrnLine(GrnLineRow(o.str("id"), o.str("grn_id"), o.str("material_id"), o.dbl("qty")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("material_issues", "id,project_id,number,task_id,subcontractor_id,issued_on,issued_by,created_at,updated_at", { o ->
            q.upsertIssue(IssueRow(o.str("id"), o.str("project_id"), o.lngOrNull("number"), o.strOrNull("task_id"), o.strOrNull("subcontractor_id"),
                o.str("issued_on"), o.strOrNull("issued_by"), o.str("created_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("material_issue_lines", "id,material_issue_id,material_id,qty,updated_at", { o ->
            q.upsertIssueLine(IssueLineRow(o.str("id"), o.str("material_issue_id"), o.str("material_id"), o.dbl("qty")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("stock_adjustments", "id,project_id,subcontractor_id,material_id,kind,qty,task_id,adjusted_on,note,created_at,updated_at", { o ->
            q.upsertAdjustment(AdjustmentRow(o.str("id"), o.str("project_id"), o.strOrNull("subcontractor_id"), o.str("material_id"), o.str("kind"),
                o.dbl("qty"), o.strOrNull("task_id"), o.str("adjusted_on"), o.strOrNull("note"), o.str("created_at")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("activity_events", "id,project_id,actor_id,verb,entity_id,summary,created_at,updated_at", { o ->
            q.upsertActivity(ActivityRow(o.str("id"), o.str("project_id"), o.strOrNull("actor_id"), o.str("verb"), o.strOrNull("entity_id"),
                o.strOrNull("summary").orEmpty(), o.str("created_at")))
        }, { it.str("id") }),
    )

    /** After the server refuses a change: take its version of the row back, or drop the row if it never existed there. */
    private suspend fun restore(table: String, rowId: String) {
        val spec = pullSpecs().firstOrNull { it.table == table } ?: return
        val filter = if (table == "attendance") {
            val (p, w, d) = rowId.split("|")
            mapOf("project_id" to "eq.$p", "worker_id" to "eq.$w", "work_date" to "eq.$d")
        } else mapOf((if (table == "project_settings") "project_id" else "id") to "eq.$rowId")
        val rows = try { api.select(table, filter + ("select" to spec.columns)) } catch (e: RemoteError) { return }
        db.transaction {
            if (rows.isEmpty()) {
                when (table) {
                    "attendance" -> rowId.split("|").let { (p, w, d) -> q.clearAttendance(p, w, d) }
                    "project_settings" -> {}
                    else -> engineDeleteHandlers[table]?.invoke(rowId)
                }
            } else rows.forEach { spec.apply(it.jsonObject) }
            if (table == "task_updates" || table == "tasks") rows.firstOrNull()?.jsonObject?.strOrNull("project_id")?.let { q.updateProjectProgress(it) }
        }
    }

    private val engineDeleteHandlers: Map<String, (String) -> Unit> = mapOf(
        "projects" to q::deleteProject, "tasks" to q::deleteTask, "task_updates" to q::deleteUpdate, "media" to q::deleteMedia,
        "workers" to q::deleteWorker, "reports" to q::deleteReport,
        "materials" to q::deleteMaterial, "grns" to q::deleteGrn, "grn_lines" to q::deleteGrnLine, "material_issues" to q::deleteIssue,
        "material_issue_lines" to q::deleteIssueLine, "stock_adjustments" to q::deleteAdjustment, "albums" to q::deleteAlbum,
        "project_members" to q::deleteProjectMember, "org_members" to q::deleteOrgMember, "invites" to q::deleteInvite,
    )

    // ================================================================ errors

    /** Runs a repository call and turns every failure into an [AppError] the screens can word. */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: AppError) {
        Result.failure(e)
    } catch (e: RemoteError) {
        Result.failure(appError(e))
    }

    private fun appError(e: RemoteError): AppError = when (e) {
        is RemoteError.Offline -> AppError.Offline
        is RemoteError.Unauthorized -> { signOut(); AppError.NotAllowed }
        is RemoteError.Forbidden -> AppError.NotAllowed
        is RemoteError.Rejected -> when {
            e.message.orEmpty().startsWith("plan_limit:") -> AppError.PlanLimit(e.message!!.removePrefix("plan_limit:"))
            else -> AppError.Unknown(e.code)
        }
        is RemoteError.Server -> AppError.Unknown("server")
    }

    companion object {
        const val FREE_PROJECTS = 3
        val REPORT_LIFETIME: Duration = Duration.ofDays(90)
    }
}
