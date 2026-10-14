package app.site.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.site.LocalContainer
import app.site.R
import app.site.core.data.fake.FakeBackend
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SiteIcon
import app.site.design.components.SiteTextField
import app.site.design.components.TextAction
import app.site.design.components.VSpace
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch

private enum class Step { PHONE, CODE, NAME }

/** S01: phone number, then a 6-digit code, then (first time) your name. */
@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = SiteTheme.colors

    var step by rememberSaveable { mutableStateOf(Step.PHONE) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    Column(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Size.gutter, vertical = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        SiteIcon(R.drawable.ic_hard_hat, null, tint = c.accent, size = 40.dp)
        Text(
            stringResource(
                when (step) {
                    Step.PHONE -> R.string.signin_title
                    Step.CODE -> R.string.signin_code_title
                    Step.NAME -> R.string.signin_name_title
                },
            ),
            Modifier.semantics { heading() },
            style = SiteTheme.type.xl,
            color = c.text,
        )
        Text(
            when (step) {
                Step.PHONE -> stringResource(R.string.signin_body)
                Step.CODE -> stringResource(R.string.signin_code_body, phone)
                Step.NAME -> stringResource(R.string.signin_name_body)
            },
            style = SiteTheme.type.sm,
            color = c.textMuted,
        )
        generalError(error)?.let { InfoBanner(it, tone = app.site.design.components.Tone.DANGER, icon = R.drawable.ic_circle_alert) }

        when (step) {
            Step.PHONE -> {
                SiteTextField(
                    phone, { phone = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(14); error = null },
                    label = stringResource(R.string.field_phone), prefix = "+91 ",
                    placeholder = stringResource(R.string.field_phone_placeholder),
                    keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done,
                    error = fieldError(error, "phone"),
                )
                PrimaryButton(stringResource(R.string.action_send_code), {
                    busy = true
                    scope.launch {
                        container.session.requestOtp(phone)
                            .onSuccess { step = Step.CODE; error = null }
                            .onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = phone.count { it.isDigit() } >= 10)
            }
            Step.CODE -> {
                InfoBanner(stringResource(R.string.signin_demo_code, FakeBackend.DEMO_OTP))
                SiteTextField(
                    code, { code = it.filter { ch -> ch.isDigit() }.take(6); error = null },
                    label = stringResource(R.string.field_code), keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done, error = fieldError(error, "code"),
                )
                PrimaryButton(stringResource(R.string.action_verify), {
                    busy = true
                    scope.launch {
                        container.session.verifyOtp(phone, code)
                            .onSuccess { s -> if (s.user.name.isBlank()) step = Step.NAME else onSignedIn() }
                            .onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = code.length == 6)
                TextAction(stringResource(R.string.action_change_number), { step = Step.PHONE; code = ""; error = null })
            }
            Step.NAME -> {
                SiteTextField(
                    name, { name = it.take(60) }, label = stringResource(R.string.field_your_name),
                    placeholder = stringResource(R.string.field_your_name_placeholder), imeAction = ImeAction.Done, required = true,
                )
                PrimaryButton(stringResource(R.string.action_continue), {
                    container.updateProfileName(name)
                    onSignedIn()
                }, large = true, enabled = name.isNotBlank())
            }
        }
        VSpace(Space.s4)
    }
}

