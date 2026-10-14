package app.site.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.NewWorker
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SiteTextField
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch

/** S22: add a worker once, then mark them every day. */
@Composable
fun AddWorkerScreen(projectId: String, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var skill by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var wage by rememberSaveable { mutableStateOf("") }
    var ot by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val wagePaise = Format.parseRupees(wage)
    val otPaise = Format.parseRupees(ot)
    val wageBad = wage.isNotBlank() && wagePaise == null
    val otBad = ot.isNotBlank() && otPaise == null

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.add_worker_title), onDone) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_add_worker), {
                    busy = true
                    scope.launch {
                        container.attendance.addWorker(
                            NewWorker(projectId, name, skill, phone.ifBlank { null }, wagePaise = wagePaise ?: 0, otRatePaise = otPaise ?: 0),
                        ).onSuccess { onDone() }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = name.isNotBlank() && !wageBad && !otBad)
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_worker_name), required = true,
                error = fieldError(error, "name"))
            SiteTextField(skill, { skill = it }, stringResource(R.string.field_skill), placeholder = stringResource(R.string.field_skill_placeholder))
            SiteTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(14) }, stringResource(R.string.field_phone_optional),
                prefix = "+91 ", keyboardType = KeyboardType.Phone)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                SiteTextField(wage, { wage = it }, stringResource(R.string.field_daily_wage), Modifier.weight(1f), prefix = "₹",
                    keyboardType = KeyboardType.Decimal, error = if (wageBad) stringResource(R.string.err_money) else fieldError(error, "wage"))
                SiteTextField(ot, { ot = it }, stringResource(R.string.field_ot_rate), Modifier.weight(1f), prefix = "₹",
                    suffix = stringResource(R.string.unit_per_hour), keyboardType = KeyboardType.Decimal,
                    error = if (otBad) stringResource(R.string.err_money) else null)
            }
        }
    }
}
