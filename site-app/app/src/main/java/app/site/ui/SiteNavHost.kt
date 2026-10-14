package app.site.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.site.LocalContainer
import kotlinx.coroutines.launch
import app.site.ui.attendance.AddWorkerScreen
import app.site.ui.attendance.AttendanceSettingsScreen
import app.site.ui.auth.SignInScreen
import app.site.ui.gallery.GalleryScreen
import app.site.ui.team.TeamScreen
import app.site.ui.material.AddMaterialScreen
import app.site.ui.material.IssueScreen
import app.site.ui.material.MaterialDetailScreen
import app.site.ui.material.MaterialNav
import app.site.ui.material.ReceiveScreen
import app.site.ui.more.FeedScreen
import app.site.ui.more.PlannedScreen
import app.site.ui.more.ProfileScreen
import app.site.ui.project.ProjectShell
import app.site.ui.projects.CreateProjectScreen
import app.site.ui.projects.ProjectsScreen
import app.site.ui.report.ReportsScreen
import app.site.ui.tasks.CreateTaskScreen
import app.site.ui.tasks.TaskDetailScreen

object Routes {
    const val SIGN_IN = "signin"
    const val PROJECTS = "projects"
    const val NEW_PROJECT = "projects/new"
    const val PROJECT = "project/{pid}?tab={tab}"
    const val NEW_TASK = "project/{pid}/tasks/new"
    const val TASK = "project/{pid}/tasks/{tid}"
    const val NEW_WORKER = "project/{pid}/workers/new"
    const val REPORTS = "project/{pid}/reports"
    const val FEED = "project/{pid}/feed"
    const val ATTENDANCE_SETTINGS = "project/{pid}/settings/attendance"
    const val PROFILE = "profile"
    const val PLANNED = "planned/{sid}"
    const val ADD_MATERIAL = "project/{pid}/material/new"
    const val RECEIVE = "project/{pid}/material/receive"
    const val ISSUE = "project/{pid}/material/issue"
    const val MATERIAL = "project/{pid}/stock/{mid}"
    const val GALLERY = "project/{pid}/gallery"
    const val TEAM = "project/{pid}/team"
    const val COMPANY = "company"
    const val ISSUES = "project/{pid}/issues"
    const val EXPENSES = "project/{pid}/expenses"
    const val NEW_ISSUE = "project/{pid}/issues/new"
    const val ISSUE = "project/{pid}/issue/{iid}"
    const val ALBUM = "project/{pid}/albums/{aid}"

    fun project(pid: String, tab: Int = 0) = "project/$pid?tab=$tab"
    fun newTask(pid: String) = "project/$pid/tasks/new"
    fun task(pid: String, tid: String) = "project/$pid/tasks/$tid"
    fun newWorker(pid: String) = "project/$pid/workers/new"
    fun reports(pid: String) = "project/$pid/reports"
    fun feed(pid: String) = "project/$pid/feed"
    fun attendanceSettings(pid: String) = "project/$pid/settings/attendance"
    fun planned(p: Planned) = "planned/${p.id}"
    fun gallery(pid: String) = "project/$pid/gallery"
    fun album(pid: String, aid: String) = "project/$pid/albums/$aid"
    fun material(pid: String, nav: MaterialNav) = when (nav) {
        MaterialNav.AddMaterial -> "project/$pid/material/new"
        MaterialNav.Receive -> "project/$pid/material/receive"
        MaterialNav.Issue -> "project/$pid/material/issue"
        is MaterialNav.Detail -> "project/$pid/stock/${nav.materialId}"
    }
}

/** Tab indexes inside a project (S05). */
object ProjectTab {
    const val REPORT = 0
    const val TASKS = 1
    const val MATERIAL = 2
    const val ATTENDANCE = 3
    const val MORE = 4
}

@Composable
fun SiteNavHost(nav: NavHostController = rememberNavController()) {
    val container = LocalContainer.current
    val session by container.session.session.collectAsStateWithLifecycle()
    val start = remember { if (container.session.session.value == null) Routes.SIGN_IN else Routes.PROJECTS }

    // Signed out anywhere -> back to sign in with an empty back stack.
    LaunchedEffect(session == null) {
        if (session == null && nav.currentDestination?.route != Routes.SIGN_IN) {
            nav.navigate(Routes.SIGN_IN) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    val pid = navArgument("pid") { type = NavType.StringType }
    NavHost(nav, startDestination = start) {
        composable(Routes.SIGN_IN) {
            SignInScreen(onSignedIn = { nav.navigate(Routes.PROJECTS) { popUpTo(nav.graph.id) { inclusive = true } } })
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpen = { nav.navigate(Routes.project(it)) },
                onNew = { nav.navigate(Routes.NEW_PROJECT) },
                onProfile = { nav.navigate(Routes.PROFILE) },
                onPlanned = { nav.navigate(Routes.planned(it)) },
            )
        }
        composable(Routes.NEW_PROJECT) {
            CreateProjectScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate(Routes.project(id)) { popUpTo(Routes.PROJECTS) } },
            )
        }
        composable(Routes.PROJECT, listOf(pid, navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            ProjectShell(
                projectId = projectId,
                initialTab = e.arguments?.getInt("tab") ?: 0,
                onClose = { nav.popBackStack(Routes.PROJECTS, inclusive = false) },
                onSwitch = { other -> nav.navigate(Routes.project(other)) { popUpTo(Routes.PROJECTS) } },
                onNewTask = { nav.navigate(Routes.newTask(projectId)) },
                onTask = { nav.navigate(Routes.task(projectId, it)) },
                onNewWorker = { nav.navigate(Routes.newWorker(projectId)) },
                onReports = { nav.navigate(Routes.reports(projectId)) },
                onFeed = { nav.navigate(Routes.feed(projectId)) },
                onAttendanceSettings = { nav.navigate(Routes.attendanceSettings(projectId)) },
                onPlanned = {
                    when (it) {
                        Planned.GALLERY -> nav.navigate(Routes.gallery(projectId))
                        Planned.MEMBERS -> nav.navigate("project/$projectId/team")
                        Planned.ISSUES -> nav.navigate("project/$projectId/issues")
                        Planned.EXPENSES -> nav.navigate("project/$projectId/expenses")
                        Planned.CAMERA -> nav.navigate("project/$projectId/camera")
                        else -> nav.navigate(Routes.planned(it))
                    }
                },
                onMaterial = { nav.navigate(Routes.material(projectId, it)) },
            )
        }
        composable("project/{pid}/camera", listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            app.site.ui.camera.InAppCamera(
                onDone = { uris ->
                    // the app's scope: this screen leaves before the photos are saved
                    container.appScope.launch { container.gallery.addPhotos(projectId, uris) }
                    nav.navigate(Routes.gallery(projectId)) { popUpTo(Routes.PROJECT) }
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(Routes.EXPENSES, listOf(pid)) { e ->
            app.site.ui.money.ExpensesScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.ISSUES, listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            app.site.ui.issues.IssuesScreen(projectId, onBack = { nav.popBackStack() },
                onNew = { nav.navigate("project/$projectId/issues/new") }, onOpen = { nav.navigate("project/$projectId/issue/$it") })
        }
        composable(Routes.NEW_ISSUE, listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            app.site.ui.issues.NewIssueScreen(projectId, onBack = { nav.popBackStack() }, onCreated = {
                nav.navigate("project/$projectId/issue/$it") { popUpTo(Routes.ISSUES) }
            })
        }
        composable(Routes.ISSUE, listOf(pid, navArgument("iid") { type = NavType.StringType })) { e ->
            app.site.ui.issues.IssueDetailScreen(e.arguments?.getString("iid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.TEAM, listOf(pid)) { e ->
            TeamScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.GALLERY, listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            GalleryScreen(projectId, onBack = { nav.popBackStack() }, onAlbum = { nav.navigate(Routes.album(projectId, it)) })
        }
        composable(Routes.ALBUM, listOf(pid, navArgument("aid") { type = NavType.StringType })) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            GalleryScreen(projectId, onBack = { nav.popBackStack() }, onAlbum = {}, albumId = e.arguments?.getString("aid"))
        }
        composable(Routes.ADD_MATERIAL, listOf(pid)) { e ->
            AddMaterialScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable(Routes.RECEIVE, listOf(pid)) { e ->
            ReceiveScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable(Routes.ISSUE, listOf(pid)) { e ->
            IssueScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable(Routes.MATERIAL, listOf(pid, navArgument("mid") { type = NavType.StringType })) { e ->
            MaterialDetailScreen(e.arguments?.getString("pid").orEmpty(), e.arguments?.getString("mid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.NEW_TASK, listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            CreateTaskScreen(projectId, onBack = { nav.popBackStack() }, onCreated = { tid ->
                nav.navigate(Routes.task(projectId, tid)) { popUpTo(Routes.PROJECT) }
            })
        }
        composable(Routes.TASK, listOf(pid, navArgument("tid") { type = NavType.StringType })) { e ->
            TaskDetailScreen(e.arguments?.getString("tid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.NEW_WORKER, listOf(pid)) { e ->
            AddWorkerScreen(e.arguments?.getString("pid").orEmpty(), onDone = { nav.popBackStack() })
        }
        composable(Routes.REPORTS, listOf(pid)) { e ->
            ReportsScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.FEED, listOf(pid)) { e ->
            val projectId = e.arguments?.getString("pid").orEmpty()
            FeedScreen(projectId, onBack = { nav.popBackStack() }, onTask = { nav.navigate(Routes.task(projectId, it)) })
        }
        composable(Routes.ATTENDANCE_SETTINGS, listOf(pid)) { e ->
            AttendanceSettingsScreen(e.arguments?.getString("pid").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }, onCompany = { nav.navigate(Routes.COMPANY) }) }
        composable(Routes.COMPANY) { app.site.ui.more.CompanyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PLANNED, listOf(navArgument("sid") { type = NavType.StringType })) { e ->
            PlannedScreen(Planned.byId(e.arguments?.getString("sid")), onBack = { nav.popBackStack() })
        }
    }
}
