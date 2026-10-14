package app.site.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.SiteIcon
import coil.request.ImageRequest

/**
 * Image model for a photo reference: a local content:// URI as-is, or "storage:<bucket>/<path>" loaded from
 * Supabase Storage with the user's token. Null while the request is being prepared.
 */
@Composable
fun photoModel(ref: String): Any? {
    if (!ref.startsWith("storage:")) return ref
    val container = LocalContainer.current
    val context = LocalContext.current
    val model by produceState<Any?>(null, ref) {
        val http = container.http ?: return@produceState
        val (bucket, path) = ref.removePrefix("storage:").split("/", limit = 2)
        value = runCatching {
            val (url, headers) = http.authenticatedObject(bucket, path)
            ImageRequest.Builder(context).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
        }.getOrNull()
    }
    return model
}

/** One line under the app bar when the phone is offline, has changes waiting, or the server refused some. */
@Composable
fun SyncBanner() {
    val container = LocalContainer.current
    val status by container.sync.status.collectAsStateWithLifecycle()
    val c = SiteTheme.colors
    val (icon, text) = when {
        status.offline -> R.drawable.ic_wifi_off to stringResource(R.string.sync_offline, status.pending)
        status.rejected > 0 -> R.drawable.ic_circle_alert to stringResource(R.string.sync_rejected, status.rejected)
        status.pending > 0 && !status.syncing -> R.drawable.ic_clock to stringResource(R.string.sync_pending, status.pending)
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(c.surfaceTrack).padding(horizontal = Size.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        SiteIcon(icon, null, tint = c.text, size = 16.dp)
        Text(text, style = SiteTheme.type.xs, color = c.text)
    }
}
