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
    PeopleRepository, SyncRepository {

    private val q = db.siteQueries

    val engine = SyncEngine(
        db, api, files, scope, clock,
        tables = pullSpecs(),
        tombstoneHandlers = mapOf(
            "projects" to q::deleteProject, "tasks" to q::deleteTask, "task_updates" to q::deleteUpdate,
            "media" to q::deleteMedia, "workers" to q::deleteWorker, "attendance" to q::deleteAttendance,
            "reports" to q::deleteReport, "activity_events" to q::deleteActivity,
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
        val profile = api.select("profiles", mapOf("select" to "id,name,phone", "id" to "eq.$userId")).firstOrNull()?.jsonObject
        val membership = api.select("org_members", mapOf(
            "select" to "role,organisations(id,name,plan)", "user_id" to "eq.$userId", "order" to "created_at.asc", "limit" to "1",
        )).firstOrNull()?.jsonObject
        val user = User(userId, profile?.strOrNull("name").orEmpty(), profile?.strOrNull("phone").orEmpty())
        val orgJson = membership?.get("organisations")?.jsonObject
        val org = orgJson?.let { Organisation(it.str("id"), it.str("name"), planOf(it.strOrNull("plan"))) }
        val role = membership?.strOrNull("role")?.let { r -> OrgRole.entries.firstOrNull { it.name.equals(r, true) } }
        val s = Session(user, org, role)
        q.kvPut("session", buildJsonObject {
            put("user_id", user.id); put("name", user.name); put("phone", user.phone)
            putOrNull("org_id", org?.id); putOrNull("org_name", org?.name); putOrNull("org_plan", org?.plan?.name); putOrNull("org_role", role?.name)
        }.toString())
        q.upsertPerson(PersonRow(user.id, user.name, user.phone))
        _session.value = s
        return s
    }

    private fun loadCachedSession(): Session? {
        if (tokens.load() == null) return null
        val o = q.kvGet("session").executeAsOneOrNull()?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } ?: return null
        val org = o.strOrNull("org_id")?.let { Organisation(it, o.strOrNull("org_name").orEmpty(), planOf(o.strOrNull("org_plan"))) }
        return Session(User(o.str("user_id"), o.strOrNull("name").orEmpty(), o.strOrNull("phone").orEmpty()), org,
            o.strOrNull("org_role")?.let { r -> OrgRole.entries.firstOrNull { it.name == r } })
    }

    private fun planOf(s: String?): Plan = Plan.entries.firstOrNull { it.name.equals(s, true) } ?: Plan.FREE

    // ================================================================ people

    override fun people(): Flow<Map<String, User>> =
        q.people().asFlow().mapToList(io).map { rows -> rows.associate { it.id to User(it.id, it.name, it.phone.orEmpty()) } }

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
        val row = ProjectRow(newId(), org.id, v.name, "active", v.address, v.startDate?.toString(), v.endDate?.toString(), 0.0, 8.0, 1)
        db.transaction {
            q.upsertProject(row)
            engine.enqueueUpsert("projects", row.id, buildJsonObject {
                put("id", row.id); put("org_id", row.org_id); put("name", row.name); putOrNull("address", row.address)
                putOrNull("start_date", row.start_date); putOrNull("end_date", row.end_date); put("created_by", s.user.id)
            })
        }
        syncNow()
        project(row)
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
                q.upsertMedia(MediaRow(mediaId, t.project_id, row.id, uri, null, now()))
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
        put("task_updates", p.taskUpdates); put("photos", p.photos); put("attendance", p.attendance); put("wages", p.wages)
    }

    private fun report(r: ReportRow): Report {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(r.parts).jsonObject
        return Report(
            r.id, r.project_id, if (r.type == "summary") ReportType.SUMMARY else ReportType.DETAILED,
            LocalDate.parse(r.from_date), LocalDate.parse(r.to_date),
            ReportParts(parts.bool("task_updates", true), parts.bool("photos", true), parts.bool("attendance", true), parts.bool("wages", false)),
            ReportStatus.entries.firstOrNull { it.name.equals(r.status, true) } ?: ReportStatus.QUEUED,
            SyncEngine.parseInstant(r.created_at), SyncEngine.parseInstant(r.expires_at), r.local_file,
        )
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
        SyncEngine.PullSpec("profiles", "id,name,phone,updated_at", { o ->
            q.upsertPerson(PersonRow(o.str("id"), o.strOrNull("name").orEmpty(), o.strOrNull("phone")))
        }, { it.str("id") }),
        SyncEngine.PullSpec("projects", "id,org_id,name,status,address,start_date,end_date,progress_pct,updated_at", { o ->
            val old = q.project(o.str("id")).executeAsOneOrNull()
            q.upsertProject(ProjectRow(o.str("id"), o.str("org_id"), o.str("name"), o.str("status"), o.strOrNull("address"),
                o.strOrNull("start_date"), o.strOrNull("end_date"), o.dbl("progress_pct"), old?.shift_hours ?: 8.0, old?.attendance_remarks ?: 1))
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
        SyncEngine.PullSpec("media", "id,project_id,task_update_id,storage_path,taken_at,updated_at", { o ->
            val old = q.mediaForUpdate(o.strOrNull("task_update_id")).executeAsList().firstOrNull { it.id == o.str("id") }
            q.upsertMedia(MediaRow(o.str("id"), o.str("project_id"), o.strOrNull("task_update_id"), old?.local_uri,
                o.strOrNull("storage_path"), o.str("taken_at")))
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
