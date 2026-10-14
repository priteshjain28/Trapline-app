package app.site.ui.more

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.data.AppError
import app.site.core.model.ActivityEvent
import app.site.core.model.ActivityVerb
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.Avatar
import app.site.design.components.EmptyState
import app.site.design.components.PrimaryButton
import app.site.design.components.SecondaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.SegmentedControl
import app.site.design.components.SiteCard
import app.site.design.components.SiteIcon
import app.site.design.components.SiteTextField
import app.site.design.components.Tabs
import app.site.design.components.TextAction
import kotlinx.coroutines.launch
import app.site.ui.Planned
import java.time.Duration
import java.time.Instant

private data class MoreItem(@DrawableRes val icon: Int, val label: Int, val onClick: () -> Unit)

/** S25: everything that isn't a main tab. Unbuilt items open an honest placeholder. */
@Composable
fun MoreTab(onReports: () -> Unit, onFeed: () -> Unit, onAttendanceSettings: () -> Unit, onPlanned: (Planned) -> Unit) {
    val groups = listOf(
        R.string.more_money to listOf(MoreItem(R.drawable.ic_wallet, R.string.screen_expenses) { onPlanned(Planned.EXPENSES) }),
        R.string.more_site to listOf(
            MoreItem(R.drawable.ic_triangle_alert, R.string.screen_issues) { onPlanned(Planned.ISSUES) },
            MoreItem(R.drawable.ic_newspaper, R.string.screen_feed, onFeed),
            MoreItem(R.drawable.ic_file_text, R.string.reports_title, onReports),
            MoreItem(R.drawable.ic_images, R.string.screen_gallery) { onPlanned(Planned.GALLERY) },
            MoreItem(R.drawable.ic_folder, R.string.screen_files) { onPlanned(Planned.FILES) },
            MoreItem(R.drawable.ic_camera, R.string.screen_camera) { onPlanned(Planned.CAMERA) },
        ),
        R.string.more_settings to listOf(
            MoreItem(R.drawable.ic_building_2, R.string.screen_site_profile) { onPlanned(Planned.SITE_PROFILE) },
            MoreItem(R.drawable.ic_clock, R.string.attendance_settings_title, onAttendanceSettings),
            MoreItem(R.drawable.ic_users, R.string.screen_members) { onPlanned(Planned.MEMBERS) },
        ),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Size.gutter, vertical = Space.s2)) {
        groups.forEach { (title, items) ->
            SectionLabel(stringResource(title))
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { item -> MoreTile(item, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MoreTile(item: MoreItem, modifier: Modifier) {
    val c = SiteTheme.colors
    Column(
        modifier.clip(Radius.md).clickable(role = Role.Button, onClick = item.onClick).padding(vertical = Space.s2).heightIn(min = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp, 48.dp).clip(Radius.md).background(c.surface), contentAlignment = Alignment.Center) {
            SiteIcon(item.icon, null, tint = c.text)
        }
        Text(stringResource(item.label), Modifier.padding(top = Space.s1), style = SiteTheme.type.xs, color = c.text,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Material tab: the structure is in place; the screens come in phase 2 (see build-log.md). */
/** S27: what happened on this site, newest first. */
@Composable
fun FeedScreen(projectId: String, onBack: () -> Unit, onTask: (String) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(projectId) { container.activity.feed(projectId) }
    val events by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val now = container.clock.instant()
    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.screen_feed), onBack) },
        containerColor = SiteTheme.colors.surface,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (events.isEmpty()) item {
                EmptyState(R.drawable.ic_newspaper, stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
            }
            items(events, key = { it.id }) { e -> FeedItem(e, now, onTask) }
        }
    }
}

@Composable
private fun FeedItem(e: ActivityEvent, now: Instant, onTask: (String) -> Unit) {
    val c = SiteTheme.colors
    val who = e.actorName.ifBlank { stringResource(R.string.feed_someone) }
    when (e.verb) {
        ActivityVerb.CREATED_PROJECT, ActivityVerb.JOINED -> Text(
            if (e.verb == ActivityVerb.JOINED) stringResource(R.string.feed_joined, who, e.summary)
            else stringResource(R.string.feed_created_project, e.summary),
            Modifier.fillMaxWidth().padding(vertical = Space.s2), style = SiteTheme.type.sm, color = c.textMuted, textAlign = TextAlign.Center,
        )
        else -> SiteCard {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Avatar(who)
                    Column(Modifier.weight(1f)) {
                        val verb = when (e.verb) {
                            ActivityVerb.CREATED_TASK -> R.string.feed_created_task
                            ActivityVerb.UPDATED_PROGRESS -> R.string.feed_updated_progress
                            ActivityVerb.RAISED_ISSUE -> R.string.feed_raised_issue
                            else -> R.string.feed_closed_issue
                        }
                        Text(stringResource(verb, who), style = SiteTheme.type.base, color = c.text)
                        Text(ago(e.createdAt, now), style = SiteTheme.type.xs, color = c.textMuted)
                    }
                }
                Text(e.summary, style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.text)
                val taskId = e.entityId
                if (taskId != null && (e.verb == ActivityVerb.CREATED_TASK || e.verb == ActivityVerb.UPDATED_PROGRESS)) {
                    SecondaryButton(stringResource(R.string.action_open_task), { onTask(taskId) }, small = true)
                }
            }
        }
    }
}

@Composable
private fun ago(then: Instant, now: Instant): String {
    val d = Duration.between(then, now)
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.ago_now)
        d.toHours() < 1 -> stringResource(R.string.ago_minutes, d.toMinutes())
        d.toDays() < 1 -> stringResource(R.string.ago_hours, d.toHours())
        else -> stringResource(R.string.ago_days, d.toDays())
    }
}

/** S28: your name and number, and sign out. */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val session by container.session.session.collectAsStateWithLifecycle()
    val c = SiteTheme.colors
    var name by rememberSaveable(session?.user?.id) { mutableStateOf(session?.user?.name.orEmpty()) }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { AppBarSub(stringResource(R.string.profile_title), onBack) }, containerColor = c.bg) { padding ->
        val s = session ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(s.user.name.ifBlank { "?" }, size = Size.avatarLg)
            SiteTextField(name, { name = it.take(60) }, stringResource(R.string.field_your_name), required = true)
            PrimaryButton(stringResource(R.string.action_save), { scope.launch { container.session.completeProfile(name, null) } },
                Modifier.fillMaxWidth(), enabled = name.isNotBlank() && name.trim() != s.user.name)
            ProfileLine(stringResource(R.string.field_phone), s.user.phone, verified = true)
            ProfileLine(stringResource(R.string.profile_org), s.org?.name.orEmpty())
            ProfileLine(stringResource(R.string.profile_role), stringResource(R.string.role_owner))
            SecondaryButton(stringResource(R.string.action_sign_out), { container.session.signOut() }, Modifier.fillMaxWidth(),
                icon = R.drawable.ic_log_out)
            TextAction(stringResource(R.string.action_delete_account), { confirmDelete = true })
            deleteError?.let { Text(it, style = SiteTheme.type.sm, color = c.danger) }
        }
    }
    if (confirmDelete) {
        val ownerMustTransfer = stringResource(R.string.err_owner_must_transfer)
        val generic = stringResource(R.string.err_unknown)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = { Text(stringResource(R.string.delete_account_body)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        container.session.deleteAccount().onFailure {
                            deleteError = if (it is AppError.OwnerMustTransfer) ownerMustTransfer else generic
                        }
                    }
                }) { Text(stringResource(R.string.action_delete_account), color = c.danger) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProfileLine(label: String, value: String, verified: Boolean = false) {
    val c = SiteTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = SiteTheme.type.sm, color = c.textMuted)
        Text(value, style = SiteTheme.type.base, color = c.text)
        if (verified) SiteIcon(R.drawable.ic_check, stringResource(R.string.a11y_verified), Modifier.padding(start = Space.s1), tint = c.success,
            size = Size.iconSm)
    }
}

/** Placeholder for screens on the roadmap. Never a dead tap. */
@Composable
fun PlannedScreen(planned: Planned?, onBack: () -> Unit) {
    Scaffold(
        topBar = { AppBarSub(planned?.let { stringResource(it.title) }.orEmpty(), onBack) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        EmptyState(R.drawable.ic_hard_hat, stringResource(R.string.planned_title), stringResource(R.string.planned_body),
            Modifier.padding(padding))
    }
}
