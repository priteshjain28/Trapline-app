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
import app.site.ui.attendance.AddWorkerScreen
import app.site.ui.attendance.AttendanceSettingsScreen
import app.site.ui.auth.SignInScreen
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

    fun project(pid: String, tab: Int = 0) = "project/$pid?tab=$tab"
    fun newTask(pid: String) = "project/$pid/tasks/new"
    fun task(pid: String, tid: String) = "project/$pid/tasks/$tid"
    fun newWorker(pid: String) = "project/$pid/workers/new"
    fun reports(pid: String) = "project/$pid/reports"
    fun feed(pid: String) = "project/$pid/feed"
    fun attendanceSettings(pid: String) = "project/$pid/settings/attendance"
    fun planned(p: Planned) = "planned/${p.id}"
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
                onPlanned = { nav.navigate(Routes.planned(it)) },
            )
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
        composable(Routes.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PLANNED, listOf(navArgument("sid") { type = NavType.StringType })) { e ->
            PlannedScreen(Planned.byId(e.arguments?.getString("sid")), onBack = { nav.popBackStack() })
        }
    }
}
