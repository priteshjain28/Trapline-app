package app.site.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.site.LocalContainer
import app.site.R
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.EmptyState
import app.site.design.components.PrimaryButton
import app.site.design.components.SiteIconButton
import coil.compose.AsyncImage

/**
 * S32: the in-app camera. Take several photos in a row without leaving the app; they're saved in the app's own
 * storage straight away (so they survive a crash or no signal) and handed back as URIs when you tap Done.
 * Without camera permission it says why and offers the phone's own gallery-free fallback via [onUseSystemCamera].
 */
@Composable
fun InAppCamera(onDone: (List<String>) -> Unit, onCancel: () -> Unit, onUseSystemCamera: (() -> Unit)? = null) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(SiteTheme.colors.header)) {
            if (granted) CameraBody(onDone, onCancel)
            else Column(Modifier.fillMaxSize().background(SiteTheme.colors.bg).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.padding(Space.s1)) { SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_close), onCancel) }
                EmptyState(R.drawable.ic_camera, stringResource(R.string.camera_permission_title),
                    stringResource(if (asked) R.string.camera_permission_denied else R.string.camera_permission_body),
                    action = stringResource(R.string.action_allow_camera), onAction = { ask.launch(Manifest.permission.CAMERA) })
                if (onUseSystemCamera != null) {
                    Box(Modifier.fillMaxWidth().padding(Size.gutter), contentAlignment = Alignment.Center) {
                        app.site.design.components.TextAction(stringResource(R.string.action_use_phone_camera), onUseSystemCamera)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraBody(onDone: (List<String>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val container = LocalContainer.current
    var back by rememberSaveable { mutableStateOf(true) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var taken by rememberSaveable { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    // (re)bind when the lens changes; unbind when the camera closes
    DisposableEffect(back) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, selector, preview, capture)
            }.onFailure { failed = true }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        // top: close, flash, flip
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_close), onCancel, tint = SiteTheme.colors.onHeader)
            Box(Modifier.weight(1f))
            SiteIconButton(if (flash) R.drawable.ic_zap else R.drawable.ic_zap_off,
                stringResource(if (flash) R.string.a11y_flash_on else R.string.a11y_flash_off), { flash = !flash }, tint = SiteTheme.colors.onHeader)
            SiteIconButton(R.drawable.ic_switch_camera, stringResource(R.string.a11y_switch_camera), { back = !back }, tint = SiteTheme.colors.onHeader)
        }
        // bottom: what's been taken, shutter, done
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SiteTheme.colors.header.copy(alpha = 0.6f))
                .navigationBarsPadding().padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            if (failed) Text(stringResource(R.string.camera_failed), style = SiteTheme.type.sm, color = SiteTheme.colors.onHeader)
            if (taken.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    taken.forEach { uri ->
                        AsyncImage(model = uri, contentDescription = stringResource(R.string.a11y_photo), contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(Radius.md))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                // the shutter: a ring with a dot, 72dp
                Box(
                    Modifier.size(72.dp).clip(CircleShape).border(4.dp, SiteTheme.colors.onHeader, CircleShape)
                        .clickable(enabled = !busy, role = Role.Button, onClickLabel = stringResource(R.string.a11y_take_photo)) {
                            busy = true
                            val (file, uri) = container.photos.newCaptureFile()
                            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { taken = taken + uri.toString(); busy = false }
                                    override fun onError(e: ImageCaptureException) { failed = true; busy = false }
                                })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(if (busy) SiteTheme.colors.onHeaderMuted else SiteTheme.colors.onHeader))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (taken.isNotEmpty()) {
                        PrimaryButton(stringResource(R.string.camera_done, taken.size), { onDone(taken) })
                    }
                }
            }
        }
    }
}
