package app.site.ui.attendance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.Divider
import app.site.design.components.LoadingBlock
import app.site.design.components.SiteIconButton
import app.site.design.components.SwitchRow
import kotlinx.coroutines.launch

/** S24: per-site attendance options. Saves on every change. */
@Composable
fun AttendanceSettingsScreen(projectId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val flow = remember(projectId) { container.projects.project(projectId) }
    val project by flow.collectAsStateWithLifecycle(initialValue = null)
    val snack = remember { SnackbarHostState() }
    val saved = stringResource(R.string.toast_saved)
    val c = SiteTheme.colors

    fun save(hours: Double, remarks: Boolean) = scope.launch {
        container.projects.updateSettings(projectId, hours, remarks).onSuccess { snack.showSnackbar(saved) }
    }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.attendance_settings_title), onBack, overline = project?.name) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = c.bg,
    ) { padding ->
        val p = project
        if (p == null) { LoadingBlock(Modifier.padding(padding)); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(padding)) {
            SwitchRow(stringResource(R.string.setting_remarks_title), stringResource(R.string.setting_remarks_body), p.attendanceRemarks,
                { save(p.shiftHours, it) })
            Divider(Modifier.padding(start = Size.gutter))
            Row(Modifier.fillMaxWidth().padding(horizontal = Size.gutter, vertical = Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setting_shift_title), style = SiteTheme.type.base, color = c.text)
                    Text(stringResource(R.string.setting_shift_body), style = SiteTheme.type.sm, color = c.textMuted)
                }
                SiteIconButton(R.drawable.ic_chevron_left, stringResource(R.string.a11y_shorter_shift),
                    { save((p.shiftHours - 0.5).coerceAtLeast(1.0), p.attendanceRemarks) }, enabled = p.shiftHours > 1.0)
                Text(stringResource(R.string.setting_shift_value, Format.qty(p.shiftHours)),
                    style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
                SiteIconButton(R.drawable.ic_chevron_right, stringResource(R.string.a11y_longer_shift),
                    { save((p.shiftHours + 0.5).coerceAtMost(16.0), p.attendanceRemarks) }, enabled = p.shiftHours < 16.0)
            }
            Divider(Modifier.padding(start = Size.gutter))
        }
    }
}
