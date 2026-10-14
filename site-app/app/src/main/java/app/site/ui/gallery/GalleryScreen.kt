package app.site.ui.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.Album
import app.site.core.model.Photo
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.EmptyState
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SecondaryButton
import app.site.design.components.SiteFab
import app.site.design.components.SiteIcon
import app.site.design.components.SiteIconButton
import app.site.design.components.SiteTextField
import app.site.design.components.Tabs
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import app.site.ui.photoModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.ZoneId

/** S30: every photo of the site, and albums. With [albumId], one album's photos. */
@Composable
fun GalleryScreen(projectId: String, onBack: () -> Unit, onAlbum: (String) -> Unit, albumId: String? = null) {
    val container = LocalContainer.current
    val photosFlow = remember(projectId) { container.gallery.photos(projectId) }
    val photos by photosFlow.collectAsStateWithLifecycle(initialValue = null)
    val albumsFlow = remember(projectId) { container.gallery.albums(projectId) }
    val albums by albumsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by remember(projectId) { container.tasks.tasks(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val taskNames = tasks.associate { it.id to ((it.number?.let { n -> "#$n " } ?: "") + it.name) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val album = albumId?.let { id -> albums.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            Column {
                AppBarSub(album?.name ?: stringResource(R.string.screen_gallery), onBack)
                if (albumId == null) Tabs(listOf(stringResource(R.string.gallery_photos), stringResource(R.string.gallery_albums)), tab, { tab = it })
            }
        },
        floatingActionButton = {
            if (tab == 0 || albumId != null) SiteFab(stringResource(R.string.action_add_photos), R.drawable.ic_image_plus, { adding = true })
            else SiteFab(stringResource(R.string.action_new_album), R.drawable.ic_folder_plus, { newAlbum = true })
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val all = photos
            when {
                all == null -> LoadingBlock()
                tab == 0 || albumId != null -> {
                    val shown = if (albumId == null) all else all.filter { it.albumId == albumId }
                    if (shown.isEmpty()) EmptyState(R.drawable.ic_images, stringResource(R.string.gallery_empty_title),
                        stringResource(if (albumId == null) R.string.gallery_empty_body else R.string.gallery_album_empty_body),
                        action = stringResource(R.string.action_add_photos), onAction = { adding = true })
                    else PhotoGrid(shown) { viewing = it }
                }
                else -> AlbumList(albums, onAlbum) { newAlbum = true }
            }
        }
    }

    if (adding) AddPhotosSheet(projectId, albumId, onDismiss = { adding = false })
    if (newAlbum) NewAlbumSheet(projectId, onDismiss = { newAlbum = false }, onCreated = { newAlbum = false; onAlbum(it) })
    viewing?.let { p -> PhotoViewer(p, p.taskId?.let { taskNames[it] }, albums, onDismiss = { viewing = null }) }
}

@Composable
private fun PhotoGrid(photos: List<Photo>, onOpen: (Photo) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(photos, key = { it.id }) { p ->
            val label = stringResource(R.string.a11y_photo_taken, Format.short(p.takenAt.atZone(ZoneId.systemDefault()).toLocalDate()))
            Box(
                Modifier.aspectRatio(1f).background(SiteTheme.colors.surfaceTrack)
                    .clickable(role = Role.Image, onClickLabel = label) { onOpen(p) },
            ) {
                AsyncImage(model = photoModel(p.ref), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun AlbumList(albums: List<Album>, onAlbum: (String) -> Unit, onNew: () -> Unit) {
    if (albums.isEmpty()) {
        EmptyState(R.drawable.ic_folder, stringResource(R.string.albums_empty_title), stringResource(R.string.albums_empty_body),
            action = stringResource(R.string.action_new_album), onAction = onNew)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(albums, key = { it.id }) { a ->
            ListRow(onClick = { onAlbum(a.id) }) {
                Box(Modifier.size(56.dp).clip(Radius.md).background(SiteTheme.colors.surfaceTrack), contentAlignment = Alignment.Center) {
                    if (a.coverRef != null) {
                        AsyncImage(model = photoModel(a.coverRef!!), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize())
                    } else SiteIcon(R.drawable.ic_folder, null, tint = SiteTheme.colors.textMuted)
                }
                Column(Modifier.weight(1f).padding(start = Space.s3)) {
                    Text(a.name, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(LocalContext.current.resources.getQuantityString(R.plurals.album_count, a.photoCount, a.photoCount), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                }
            }
        }
    }
}

/** Take a photo with the phone's camera, or pick several from the phone's gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPhotosSheet(projectId: String, albumId: String?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    fun add(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch { container.gallery.addPhotos(projectId, uris, albumId).onSuccess { onDismiss() }.onFailure { error = it } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture
        pendingCapture = null
        if (ok && uri != null) add(listOf(uri))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { picked: List<Uri> ->
        // copied into app storage: picked URIs can lose permission before the upload runs
        scope.launch { add(picked.mapNotNull { container.photos.importPicked(it)?.toString() }) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(Modifier.navigationBarsPadding().padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(stringResource(R.string.action_add_photos), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SecondaryButton(stringResource(R.string.action_take_photo), {
                val uri = container.photos.newCaptureUri()
                pendingCapture = uri.toString()
                camera.launch(uri)
            }, Modifier.fillMaxWidth(), icon = R.drawable.ic_camera)
            SecondaryButton(stringResource(R.string.action_from_gallery), {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, Modifier.fillMaxWidth(), icon = R.drawable.ic_images)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAlbumSheet(projectId: String, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(stringResource(R.string.action_new_album), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_album_name),
                placeholder = stringResource(R.string.field_album_name_placeholder), error = fieldError(error, "name"))
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            PrimaryButton(stringResource(R.string.action_create_album), {
                scope.launch { container.gallery.createAlbum(projectId, name).onSuccess { onCreated(it.id) }.onFailure { error = it } }
            }, large = true, enabled = name.isNotBlank())
        }
    }
}

/** One photo, big, with when it was taken and what it's for; and moving it between albums. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoViewer(p: Photo, taskName: String?, albums: List<Album>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SiteTheme.colors.header)) {
            Row(Modifier.fillMaxWidth().padding(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_close), onDismiss, tint = SiteTheme.colors.onHeader)
                Column(Modifier.weight(1f)) {
                    Text(Format.day(p.takenAt.atZone(ZoneId.systemDefault()).toLocalDate()), style = SiteTheme.type.base,
                        color = SiteTheme.colors.onHeader)
                    Text(taskName?.let { stringResource(R.string.photo_from_task, it) } ?: stringResource(R.string.photo_added),
                        style = SiteTheme.type.sm, color = SiteTheme.colors.onHeaderMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AsyncImage(model = photoModel(p.ref), contentDescription = stringResource(R.string.a11y_photo), contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.navigationBarsPadding().padding(Size.gutter)) {
                if (p.albumId != null) {
                    SecondaryButton(stringResource(R.string.action_remove_from_album), {
                        scope.launch { container.gallery.moveToAlbum(listOf(p.id), null); onDismiss() }
                    }, Modifier.fillMaxWidth(), icon = R.drawable.ic_undo_2)
                } else if (albums.isNotEmpty()) {
                    SecondaryButton(stringResource(R.string.action_move_to_album), { choosing = true }, Modifier.fillMaxWidth(), icon = R.drawable.ic_folder)
                }
            }
        }
        if (choosing) {
            ModalBottomSheet(onDismissRequest = { choosing = false }, containerColor = SiteTheme.colors.bg) {
                LazyColumn(Modifier.navigationBarsPadding()) {
                    item {
                        Text(stringResource(R.string.gallery_pick_album), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2),
                            style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                    }
                    items(albums, key = { it.id }) { a ->
                        ListRow(onClick = { scope.launch { container.gallery.moveToAlbum(listOf(p.id), a.id); choosing = false; onDismiss() } }) {
                            SiteIcon(R.drawable.ic_folder, null, tint = SiteTheme.colors.textMuted)
                            Text(a.name, Modifier.weight(1f).padding(start = Space.s3), style = SiteTheme.type.base, color = SiteTheme.colors.text)
                        }
                    }
                }
            }
        }
    }
}
