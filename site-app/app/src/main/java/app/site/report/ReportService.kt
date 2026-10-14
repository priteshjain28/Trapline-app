package app.site.report

import app.site.core.data.AttendanceRepository
import app.site.core.data.ProjectRepository
import app.site.core.data.ReportRepository
import app.site.core.data.TaskRepository
import app.site.core.logic.ReportBuilder
import app.site.core.model.NewReport
import app.site.core.model.Report
import app.site.core.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Queues a report and renders it in the background. Today the renderer runs on the phone;
 * with the backend, the server-side worker renders and this only queues.
 */
class ReportService(
    private val projects: ProjectRepository,
    private val tasks: TaskRepository,
    private val attendance: AttendanceRepository,
    private val reports: ReportRepository,
    private val people: () -> Map<String, User>,
    private val renderer: PdfReportRenderer,
    private val scope: CoroutineScope,
) {
    suspend fun create(input: NewReport): Result<Report> {
        val report = reports.request(input).getOrElse { return Result.failure(it) }
        scope.launch { render(report) }
        return Result.success(report)
    }

    fun retry(report: Report) {
        scope.launch { render(report) }
    }

    private suspend fun render(r: Report) {
        try {
            val project = projects.project(r.projectId).first() ?: error("project gone")
            val content = ReportBuilder.build(
                project, r.type, r.from, r.to, r.parts,
                tasks.tasks(r.projectId).first(),
                tasks.updatesInRange(r.projectId, r.from, r.to).first(),
                attendance.workers(r.projectId).first(),
                attendance.range(r.projectId, r.from, r.to).first(),
                people(),
            )
            val file = renderer.render(r.id, content)
            reports.markReady(r.id, file.absolutePath)
        } catch (e: Exception) {
            reports.markFailed(r.id)
        }
    }
}
