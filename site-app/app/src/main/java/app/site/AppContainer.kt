package app.site

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import app.site.core.data.ActivityRepository
import app.site.core.data.AttendanceRepository
import app.site.core.data.ProjectRepository
import app.site.core.data.ReportRepository
import app.site.core.data.SessionRepository
import app.site.core.data.TaskRepository
import app.site.core.data.fake.FakeBackend
import app.site.media.PhotoStore
import app.site.report.PdfReportRenderer
import app.site.report.ReportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.time.LocalDate

/**
 * Wiring. Screens only see the repository interfaces, so /replica-backend replaces [FakeBackend]
 * with Room + Supabase here and nowhere else.
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    latencyMs: Long = 250,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend(clock, latencyMs)

    val session: SessionRepository = backend
    val projects: ProjectRepository = backend
    val tasks: TaskRepository = backend
    val attendance: AttendanceRepository = backend
    val reports: ReportRepository = backend
    val activity: ActivityRepository = backend

    val photos = PhotoStore(context)
    val reportService = ReportService(
        projects = projects, tasks = tasks, attendance = attendance, reports = reports,
        people = { backend.people + listOfNotNull(backend.session.value?.user).associateBy { it.id } },
        renderer = PdfReportRenderer(context),
        scope = appScope,
    )

    fun today(): LocalDate = backend.today()
    fun personName(id: String): String =
        (backend.people[id] ?: backend.session.value?.user?.takeIf { it.id == id })?.name.orEmpty()
    fun updateProfileName(name: String) = backend.updateProfileName(name)
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class SiteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
