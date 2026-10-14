package app.site

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.site.core.data.ActivityRepository
import app.site.core.data.AttendanceRepository
import app.site.core.data.PeopleRepository
import app.site.core.data.ProjectRepository
import app.site.core.data.ReportRepository
import app.site.core.data.SessionRepository
import app.site.core.data.SyncRepository
import app.site.core.data.TaskRepository
import app.site.core.data.fake.FakeBackend
import app.site.core.data.synced.SyncedBackend
import app.site.core.local.SiteDb
import app.site.core.remote.SupabaseHttp
import app.site.data.AndroidFileStore
import app.site.data.PrefsTokenStore
import app.site.data.SyncWorker
import app.site.media.PhotoStore
import app.site.report.PdfReportRenderer
import app.site.report.ReportService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate

/**
 * Wiring. With Supabase keys in BuildConfig the app runs on the real, offline-first data layer;
 * without them it runs the built-in demo (sample data, code 123456). Screens can't tell the difference.
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    latencyMs: Long = 250,
    forceDemo: Boolean = false,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val demo: Boolean = forceDemo || BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()

    /** Only in real mode: used to load photos stored on the server. */
    val http: SupabaseHttp?

    val session: SessionRepository
    val projects: ProjectRepository
    val tasks: TaskRepository
    val attendance: AttendanceRepository
    val reports: ReportRepository
    val activity: ActivityRepository
    val people: PeopleRepository
    val sync: SyncRepository
    val materials: app.site.core.data.MaterialRepository
    val gallery: app.site.core.data.GalleryRepository

    init {
        if (demo) {
            val fake = FakeBackend(clock, latencyMs)
            http = null
            session = fake; projects = fake; tasks = fake; attendance = fake; reports = fake; activity = fake; people = fake; sync = fake; materials = fake; gallery = fake
        } else {
            val tokens = PrefsTokenStore(context)
            val client = HttpClient(OkHttp) {
                install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
            }
            val api = SupabaseHttp(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, client, tokens, clock)
            val db = SiteDb(AndroidSqliteDriver(SiteDb.Schema, context, "site.db"))
            val real = SyncedBackend(db, api, api, tokens, AndroidFileStore(context), appScope, clock)
            http = api
            session = real; projects = real; tasks = real; attendance = real; reports = real; activity = real; people = real; sync = real; materials = real; gallery = real
        }
    }

    val photos = PhotoStore(context)
    val reportService = ReportService(
        projects = projects, tasks = tasks, attendance = attendance, reports = reports,
        people = { people.people().first() },
        renderer = PdfReportRenderer(context),
        scope = appScope,
    )

    fun today(): LocalDate = LocalDate.now(clock)
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class SiteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (!container.demo) {
            // sync whenever the app comes to the front, and every 15 minutes in the background with a network
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.sync.syncNow()
            })
            SyncWorker.schedule(this)
        }
    }
}
