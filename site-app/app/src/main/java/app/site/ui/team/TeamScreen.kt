package app.site.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.model.Member
import app.site.core.model.ProjectRole
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.Avatar
import app.site.design.components.ChoiceCard
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SecondaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.SiteFab
import app.site.design.components.SiteIcon
import app.site.design.components.SiteTextField
import app.site.design.components.StatusBadge
import app.site.design.components.Tone
import app.site.ui.body
import app.site.ui.fieldError
import app.site.ui.generalError
import app.site.ui.label
import kotlinx.coroutines.launch

/** S36: who works on this site and in what role; invite by phone number. */
@Composable
fun TeamScreen(projectId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val flow = remember(projectId) { container.team.team(projectId) }
    val team by flow.collectAsStateWithLifecycle(initialValue = null)
    val me = container.session.session.collectAsStateWithLifecycle().value?.user?.id
    var inviting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.team_title), onBack) },
        floatingActionButton = {
            if (team?.canManage == true) SiteFab(stringResource(R.string.action_invite), R.drawable.ic_plus, { inviting = true })
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        val t = team
        if (t == null) { Box(Modifier.padding(padding)) { LoadingBlock() }; return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            notice?.let { n -> item { InfoBanner(stringResource(n), Modifier.padding(Size.gutter), tone = Tone.SUCCESS, icon = R.drawable.ic_check) } }
            if (!t.canManage) item { InfoBanner(stringResource(R.string.team_cant_manage), Modifier.padding(Size.gutter)) }
            val company = t.members.filter { it.fromCompany }
            val site = t.members.filter { !it.fromCompany }
            if (company.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.team_company), Modifier.padding(horizontal = Size.gutter)) }
                items(company, key = { "c" + it.userId }) { m -> MemberRow(m, m.userId == me, onClick = null) }
            }
            item { SectionLabel(stringResource(R.string.team_site), Modifier.padding(horizontal = Size.gutter)) }
            if (site.isEmpty()) item {
                Text(stringResource(R.string.team_only_you), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2),
                    style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            }
            items(site, key = { "s" + it.userId }) { m -> MemberRow(m, m.userId == me, onClick = if (t.canManage) ({ editing = m }) else null) }
            if (t.invites.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.team_invited), Modifier.padding(horizontal = Size.gutter)) }
                items(t.invites, key = { it.id }) { inv ->
                    ListRow {
                        SiteIcon(R.drawable.ic_message_square, null, tint = SiteTheme.colors.textMuted)
                        Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
                            Text(inv.phone, style = SiteTheme.type.base, color = SiteTheme.colors.text)
                            Text(stringResource(R.string.team_invited_waiting), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                        }
                        inv.role?.let { StatusBadge(stringResource(it.label()), Tone.NEUTRAL) }
                    }
                }
            }
        }
    }

    if (inviting) InviteSheet(projectId, onDismiss = { inviting = false }, onSent = { sms ->
        inviting = false
        notice = if (sms) R.string.invite_sent_sms else R.string.invite_saved
    })
    editing?.let { m -> MemberSheet(projectId, m, onDismiss = { editing = null }) }
}

@Composable
private fun MemberRow(m: Member, isMe: Boolean, onClick: (() -> Unit)?) {
    val c = SiteTheme.colors
    ListRow(onClick = onClick) {
        Avatar(m.name.ifBlank { m.phone })
        Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
            Text(m.name.ifBlank { m.phone }, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.phone, style = SiteTheme.type.sm, color = c.textMuted)
        }
        val role = m.orgRole?.takeIf { m.fromCompany }?.let { stringResource(it.label()) } ?: stringResource(m.role.label())
        StatusBadge(role, if (m.role == ProjectRole.MANAGER) Tone.INFO else Tone.NEUTRAL)
    }
}

@Composable
private fun RolePicker(selected: ProjectRole, onSelect: (ProjectRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        ProjectRole.entries.forEach { r ->
            ChoiceCard(stringResource(r.label()), stringResource(r.body()), r == selected, { onSelect(r) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteSheet(projectId: String, onDismiss: () -> Unit, onSent: (Boolean) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(ProjectRole.ENGINEER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(
            Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.action_invite), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(phone, { phone = it; error = null }, stringResource(R.string.field_their_phone), keyboardType = KeyboardType.Phone,
                prefix = "+91", error = fieldError(error, "phone"))
            SectionLabel(stringResource(R.string.field_role))
            RolePicker(role) { role = it }
            PrimaryButton(stringResource(R.string.action_send_invite), {
                busy = true
                scope.launch {
                    container.team.invite(projectId, phone, role).onSuccess(onSent).onFailure { error = it }
                    busy = false
                }
            }, large = true, loading = busy, enabled = phone.count { it.isDigit() } >= 10)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSheet(projectId: String, m: Member, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Throwable?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(m.name.ifBlank { m.phone }, style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SectionLabel(stringResource(R.string.team_change_role))
            RolePicker(m.role) { r -> scope.launch { container.team.setRole(projectId, m.userId, r).onSuccess { onDismiss() }.onFailure { error = it } } }
            SecondaryButton(stringResource(R.string.action_remove_from_site), {
                scope.launch { container.team.remove(projectId, m.userId).onSuccess { onDismiss() }.onFailure { error = it } }
            }, Modifier.fillMaxWidth(), icon = R.drawable.ic_x)
        }
    }
}
