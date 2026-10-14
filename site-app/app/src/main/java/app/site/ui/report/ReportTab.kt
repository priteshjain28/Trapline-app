package app.site.ui.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.site.LocalContainer
import app.site.R
import app.site.core.model.NewReport
import app.site.core.model.Project
import app.site.core.model.ReportParts
import app.site.core.model.ReportType
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.CheckRow
import app.site.design.components.ChoiceCard
import app.site.design.components.DateField
import app.site.design.components.Divider
import app.site.design.components.FilterChip
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.TextAction
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch

private enum class Period(val label: Int, val days: Long?) {
    TODAY(R.string.period_today, 0), WEEK(R.string.period_7_days, 6), FORTNIGHT(R.string.period_15_days, 14), CUSTOM(R.string.period_custom, null)
}

/** S06: build a report for any period, pick what goes in it, and share it. */
@Composable
fun ReportTab(project: Project, onReports: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val today = container.today()
    var type by rememberSaveable { mutableStateOf(ReportType.DETAILED) }
    var period by rememberSaveable { mutableStateOf(Period.TODAY) }
    var from by rememberSaveable { mutableStateOf(today) }
    var to by rememberSaveable { mutableStateOf(today) }
    var updates by rememberSaveable { mutableStateOf(true) }
    var photos by rememberSaveable { mutableStateOf(true) }
    var attendance by rememberSaveable { mutableStateOf(true) }
    var wages by rememberSaveable { mutableStateOf(false) }
    var material by rememberSaveable { mutableStateOf(true) }
    var issues by rememberSaveable { mutableStateOf(true) }
    var payments by rememberSaveable { mutableStateOf(false) }
    val canSeeMoney by remember(project.id) { container.money.canSeeMoney(project.id) }.collectAsStateWithLifecycle(initialValue = false)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    val range = when (val d = period.days) {
        null -> from to to
        else -> today.minusDays(d) to today
    }
    // photos ride on work done, so they don't count on their own
    val nothingPicked = !updates && !attendance && !material && !issues && !(payments && canSeeMoney)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Size.gutter),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Space.s3), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.report_title), Modifier.weight(1f).semantics { heading() },
                    style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                TextAction(stringResource(R.string.action_past_reports), onReports, icon = R.drawable.ic_file_text)
            }
            generalError(error)?.let { InfoBanner(it, Modifier.padding(top = Space.s2), tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }

            SectionLabel(stringResource(R.string.report_type_label))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                ChoiceCard(stringResource(R.string.report_type_detailed), stringResource(R.string.report_type_detailed_body),
                    type == ReportType.DETAILED, { type = ReportType.DETAILED })
                ChoiceCard(stringResource(R.string.report_type_summary), stringResource(R.string.report_type_summary_body),
                    type == ReportType.SUMMARY, { type = ReportType.SUMMARY }, badge = stringResource(R.string.badge_new))
            }

            SectionLabel(stringResource(R.string.report_period_label))
            // chips wrap into a scroll row instead of running off-screen
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Period.entries.forEach { p -> FilterChip(stringResource(p.label), period == p, { period = p; error = null }) }
            }
            if (period == Period.CUSTOM) {
                Row(Modifier.padding(top = Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    DateField(stringResource(R.string.field_from), from, { from = it; error = null }, Modifier.weight(1f), max = today)
                    DateField(stringResource(R.string.field_to), to, { to = it; error = null }, Modifier.weight(1f), min = from, max = today,
                        error = fieldError(error, "to"))
                }
            }

            SectionLabel(stringResource(R.string.report_parts_label))
            CheckRow(stringResource(R.string.part_updates), updates, { updates = it }, description = stringResource(R.string.part_updates_body))
            CheckRow(stringResource(R.string.part_photos), photos, { photos = it }, description = stringResource(R.string.part_photos_body),
                enabled = updates)
            CheckRow(stringResource(R.string.part_attendance), attendance, { attendance = it }, description = stringResource(R.string.part_attendance_body))
            CheckRow(stringResource(R.string.part_wages), wages && attendance, { wages = it }, description = stringResource(R.string.part_wages_body),
                enabled = attendance)
            CheckRow(stringResource(R.string.part_material), material, { material = it }, description = stringResource(R.string.part_material_body))
            CheckRow(stringResource(R.string.part_issues), issues, { issues = it }, description = stringResource(R.string.part_issues_body))
            if (canSeeMoney) {
                CheckRow(stringResource(R.string.part_payments), payments, { payments = it }, description = stringResource(R.string.part_payments_body))
            }
            Text(stringResource(R.string.report_more_parts_later), Modifier.padding(vertical = Space.s3),
                style = SiteTheme.type.xs, color = SiteTheme.colors.textMuted)
        }
        Divider()
        Box(Modifier.padding(Size.gutter)) {
            PrimaryButton(stringResource(R.string.action_create_report), {
                busy = true
                scope.launch {
                    val parts = ReportParts(updates, photos && updates, attendance, wages && attendance, material, issues, payments && canSeeMoney)
                    container.reportService.create(NewReport(project.id, type, range.first, range.second, parts))
                        .onSuccess { error = null; onReports() }
                        .onFailure { error = it }
                    busy = false
                }
            }, large = true, loading = busy, enabled = !nothingPicked)
        }
    }
}
