package app.site

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.site.design.SiteAppTheme
import app.site.ui.ProjectTab
import app.site.ui.attendance.AddWorkerScreen
import app.site.ui.attendance.AttendanceSettingsScreen
import app.site.ui.auth.SignInScreen
import app.site.ui.more.FeedScreen
import app.site.ui.more.ProfileScreen
import app.site.ui.project.ProjectShell
import app.site.ui.projects.CreateProjectScreen
import app.site.ui.projects.ProjectsScreen
import app.site.ui.report.ReportsScreen
import app.site.ui.tasks.CreateTaskScreen
import app.site.ui.tasks.TaskDetailScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * One image per built screen, at the reference phone's size (1344 px wide, density 3),
 * for /replica-diff. Record with: gradle :app:recordRoborazziDebug
 * Images land in app/build/clone-screens/ and CI copies them to replica/clone-screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 448x903dp: the app area of the reference phone (1344x2992 at 3x, minus its 63dp status bar and 31dp gesture bar),
// so clone and original screenshots cover the same viewport for /replica-diff
@Config(sdk = [34], qualifiers = "w448dp-h903dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
    private lateinit var container: AppContainer
    private lateinit var towerId: String
    private lateinit var brickworkId: String
    private lateinit var officeId: String
    private lateinit var cementId: String
    private lateinit var office: app.site.core.model.Project

    @Before fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext(), clock, latencyMs = 0, forceDemo = true)
        runBlocking {
            container.session.verifyOtp("9876543210", "123456").getOrThrow()
            container.session.completeProfile("Anita Rao", null).getOrThrow()
            towerId = container.projects.projects().first().first { it.name == "Green Park Tower B" }.id
            brickworkId = container.tasks.tasks(towerId).first().first { it.number == 2 }.id
            officeId = container.projects.projects().first().first { it.name == "Site office renovation" }.id
            cementId = container.materials.catalogue().first().first { it.name == "Cement" }.id
            office = container.projects.projects().first().first { it.id == officeId }
        }
    }

    private fun shot(id: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SiteAppTheme(dark = false) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/clone-screens/$id.png")
    }

    private fun shell(tab: Int) = @Composable {
        ProjectShell(towerId, tab, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun s01_signIn() = shot("S01") {
        // a fresh, signed-out container
        SignInScreen(onSignedIn = {})
    }

    @Test fun s04_projects() = shot("S04") { ProjectsScreen({}, {}, {}, {}) }
    @Test fun s03_newProject() = shot("S03") { CreateProjectScreen({}, {}) }
    @Test fun s06_report() = shot("S06") { shell(ProjectTab.REPORT)() }
    @Test fun s07_reports() = shot("S07") { ReportsScreen(towerId, {}) }
    @Test fun s08_tasks() = shot("S08") { shell(ProjectTab.TASKS)() }
    // same state as the reference screenshot: a project with no tasks yet
    @Test fun s08_tasksEmpty() = shot("S08-empty") { ProjectShell(officeId, ProjectTab.TASKS, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun s09_task() = shot("S09") { TaskDetailScreen(brickworkId, {}) }
    @Test fun s10_newTask() = shot("S10") { CreateTaskScreen(towerId, {}, {}) }
    @Test fun s15_stock() = shot("S15") { shell(ProjectTab.MATERIAL)() }
    // same state as the reference: a site with no deliveries yet
    @Test fun s14_receivedEmpty() = shot("S14-empty") {
        app.site.ui.material.MaterialTab(office, {}, startOnReceived = true)
    }
    @Test fun s14_recordDelivery() = shot("S14-new") { app.site.ui.material.ReceiveScreen(towerId, {}, {}) }
    @Test fun s16_materialHistory() = shot("S16") { app.site.ui.material.MaterialDetailScreen(towerId, cementId, {}) }
    @Test fun s30_gallery() = shot("S30") { app.site.ui.gallery.GalleryScreen(towerId, {}, {}) }
    @Test fun s36_team() = shot("S36") { app.site.ui.team.TeamScreen(towerId, {}) }
    @Test fun s19_issue() = shot("S19-new") { app.site.ui.material.IssueScreen(towerId, {}, {}) }
    @Test fun s21_attendance() = shot("S21") { shell(ProjectTab.ATTENDANCE)() }
    @Test fun s22_addWorker() = shot("S22") { AddWorkerScreen(towerId, {}) }
    @Test fun s24_attendanceSettings() = shot("S24") { AttendanceSettingsScreen(towerId, {}) }
    @Test fun s25_more() = shot("S25") { shell(ProjectTab.MORE)() }
    @Test fun s27_feed() = shot("S27") { FeedScreen(towerId, {}, {}) }
    @Test fun s28_profile() = shot("S28") { ProfileScreen({}) }
}
