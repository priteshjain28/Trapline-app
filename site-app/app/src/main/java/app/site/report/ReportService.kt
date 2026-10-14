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
 * Queues a report and renders it on the phone in the background. Android's text engine shapes Indian scripts
 * correctly, so no server-side PDF worker is needed; the finished PDF uploads with the next sync.
 * Photos taken on other phones are drawn as placeholders until they've been viewed (and cached) here.
 */
class ReportService(
    private val projects: ProjectRepository,
    private val tasks: TaskRepository,
    private val attendance: AttendanceRepository,
    private val reports: ReportRepository,
    private val people: suspend () -> Map<String, User>,
    private val materials: app.site.core.data.MaterialRepository,
    private val issues: app.site.core.data.IssueRepository,
    private val money: app.site.core.data.MoneyRepository,
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
                if (r.parts.material) materials.stock(r.projectId).first().map { it to materials.history(r.projectId, it.material.id).first() }
                else emptyList(),
                if (r.parts.issues) issues.siteIssues(r.projectId).first() else emptyList(),
                // money only for people who may see it, whatever the report asked for
                if (r.parts.payments && money.canSeeMoney(r.projectId).first()) {
                    val names = attendance.workers(r.projectId).first().associate { it.id to it.name }
                    money.payables(r.projectId).first().map { p ->
                        Triple(p, money.payments(p.id).first(), p.title ?: names[p.workerId].orEmpty())
                    }
                } else emptyList(),
            )
            val file = renderer.render(r.id, content)
            reports.markReady(r.id, file.absolutePath)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ReportService", "report ${r.id} failed", e)
            reports.markFailed(r.id)
        }
    }
}
