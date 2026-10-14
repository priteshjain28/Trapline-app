package app.site.ui.tasks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.NewTaskUpdate
import app.site.core.model.Task
import app.site.core.model.TaskStatus
import app.site.core.model.TaskUpdate
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.DateField
import app.site.design.components.EmptyState
import app.site.design.components.InfoBanner
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.ProgressRing
import app.site.design.components.SecondaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.SiteCard
import app.site.design.components.SiteIconButton
import app.site.design.components.SiteTextField
import app.site.design.components.StatusBadge
import app.site.design.components.Tone
import app.site.ui.generalError
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** S09: one task. Log today's work (quantity or %, remarks, photos) and see what was done before. */
@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val taskFlow = remember(taskId) { container.tasks.task(taskId) }
    val task by taskFlow.collectAsStateWithLifecycle(initialValue = null)
    val updatesFlow = remember(taskId) { container.tasks.updates(taskId) }
    val updates by updatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val peopleFlow = remember { container.people.people() }
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val snack = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.toast_update_saved)

    val today = container.today()
    var workDate by rememberSaveable { mutableStateOf(today) }
    var amount by rememberSaveable { mutableStateOf("") }
    var remarks by rememberSaveable { mutableStateOf("") }
    var photos by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) photos = photos + uri
        pendingCapture = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { picked: List<Uri> ->
        scope.launch {
            val copied = picked.mapNotNull { container.photos.importPicked(it)?.toString() }
            photos = (photos + copied).take(10)
        }
    }

    val t = task
    Scaffold(
        topBar = {
            AppBarSub(
                title = t?.let { (it.number?.let { n -> "#$n " } ?: "") + it.name }.orEmpty(),
                onBack = onBack,
                overline = stringResource(R.string.task_overline),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        if (t == null) {
            LoadingBlock(Modifier.padding(padding))
            return@Scaffold
        }
        val byQty = t.plannedQty != null
        val parsed = Format.parseQty(amount)
        val amountBad = amount.isNotBlank() && (parsed == null || (!byQty && parsed > 100))
        val canSave = !busy && !amountBad && (parsed != null || remarks.isNotBlank() || photos.isNotEmpty())

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Size.gutter)) {
            item { TaskSummary(t, today) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    SectionLabel(stringResource(R.string.task_log_title))
                    generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
                    DateField(stringResource(R.string.field_work_date), workDate, { workDate = it }, max = today)
                    SiteTextField(
                        amount, { amount = it; error = null },
                        label = stringResource(if (byQty) R.string.field_qty_done else R.string.field_progress_pct),
                        keyboardType = KeyboardType.Decimal,
                        suffix = if (byQty) t.unit else "%",
                        helper = if (byQty) stringResource(R.string.task_qty_helper, Format.qty(t.doneQty), Format.qty(t.plannedQty ?: 0.0), t.unit.orEmpty())
                        else stringResource(R.string.task_pct_helper),
                        error = if (amountBad) stringResource(R.string.err_not_positive) else null,
                    )
                    SiteTextField(remarks, { remarks = it }, stringResource(R.string.field_remarks),
                        placeholder = stringResource(R.string.field_remarks_placeholder), singleLine = false, minLines = 2)
                    PhotoStrip(photos, onRemove = { u -> photos = photos - u })
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        SecondaryButton(stringResource(R.string.action_take_photo), {
                            val uri = container.photos.newCaptureUri()
                            pendingCapture = uri.toString()
                            camera.launch(uri)
                        }, icon = R.drawable.ic_camera)
                        SecondaryButton(stringResource(R.string.action_from_gallery), {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, icon = R.drawable.ic_images)
                    }
                    PrimaryButton(stringResource(R.string.action_save_update), {
                        busy = true
                        scope.launch {
                            val input = NewTaskUpdate(
                                taskId = t.id, workDate = workDate,
                                qtyDone = if (byQty) parsed else null,
                                progressPct = if (!byQty) parsed?.toInt() else null,
                                remarks = remarks, photos = photos,
                            )
                            container.tasks.addUpdate(input)
                                .onSuccess { amount = ""; remarks = ""; photos = emptyList(); error = null; snack.showSnackbar(savedText) }
                                .onFailure { error = it }
                            busy = false
                        }
                    }, large = true, loading = busy, enabled = canSave)
                    SectionLabel(stringResource(R.string.task_history_title))
                }
            }
            if (updates.isEmpty()) item {
                EmptyState(R.drawable.ic_clock, stringResource(R.string.task_history_empty_title), stringResource(R.string.task_history_empty_body))
            }
            items(updates, key = { it.id }) { u -> UpdateRow(u, t, people[u.authorId]?.name.orEmpty()) }
        }
    }
}

@Composable
private fun TaskSummary(t: Task, today: java.time.LocalDate) {
    val c = SiteTheme.colors
    SiteCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                ProgressRing(t.progressPct, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text("${t.progressPct}%", style = SiteTheme.type.lg, color = c.text)
                    val line = if (t.plannedQty != null) stringResource(R.string.task_qty_of, Format.qty(t.doneQty), Format.qty(t.plannedQty!!), t.unit.orEmpty())
                    else stringResource(R.string.task_no_qty)
                    Text(line, style = SiteTheme.type.sm, color = c.textMuted)
                }
                when {
                    t.status == TaskStatus.COMPLETED -> StatusBadge(stringResource(R.string.status_done), Tone.SUCCESS)
                    t.isDelayed(today) -> StatusBadge(stringResource(R.string.status_delayed), Tone.DANGER)
                    t.status == TaskStatus.IN_PROGRESS -> StatusBadge(stringResource(R.string.filter_in_progress), Tone.INFO)
                    else -> StatusBadge(stringResource(R.string.status_not_started), Tone.NEUTRAL)
                }
            }
            val dates = listOfNotNull(
                t.startDate?.let { stringResource(R.string.task_starts, Format.short(it)) },
                t.dueDate?.let { stringResource(R.string.task_due, Format.short(it)) },
            )
            Text((listOf(t.category) + dates).joinToString(" · "), style = SiteTheme.type.sm, color = c.textMuted)
        }
    }
}

@Composable
private fun PhotoStrip(photos: List<String>, onRemove: ((String) -> Unit)?) {
    if (photos.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        photos.forEach { uri ->
            Box(Modifier.size(Size.thumb).clip(Radius.md).background(SiteTheme.colors.surfaceTrack)) {
                AsyncImage(model = app.site.ui.photoModel(uri), contentDescription = stringResource(R.string.a11y_photo), contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                if (onRemove != null) {
                    SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_remove_photo), { onRemove(uri) },
                        Modifier.align(Alignment.TopEnd).size(32.dp).clip(Radius.pill).background(SiteTheme.colors.bg.copy(alpha = 0.85f)))
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(u: TaskUpdate, t: Task, author: String) {
    val c = SiteTheme.colors
    Column {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        val what = when {
            u.qtyDone != null -> "${Format.qty(u.qtyDone!!)} ${t.unit.orEmpty()}".trim()
            u.progressPct != null -> "${u.progressPct}%"
            else -> null
        }
        Text(listOfNotNull(Format.day(u.workDate), what).joinToString(" · "),
            style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
        if (author.isNotBlank()) Text(author, style = SiteTheme.type.xs, color = c.textMuted)
        u.remarks?.let { Text(it, style = SiteTheme.type.sm, color = c.text) }
        PhotoStrip(u.photos, onRemove = null)
    }
    app.site.design.components.Divider()
    }
}
