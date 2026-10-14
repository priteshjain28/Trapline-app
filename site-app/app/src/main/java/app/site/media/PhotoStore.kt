package app.site.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Photos live in the app's own storage first (works offline), shared through our FileProvider.
 * Upload to Supabase Storage comes with /replica-backend; the URIs stored on updates stay valid until then.
 */
class PhotoStore(private val context: Context) {
    private val authority = "${context.packageName}.files"
    private fun dir(name: String) = File(context.filesDir, name).apply { mkdirs() }

    /** A file the camera app writes into. */
    fun newCaptureUri(): Uri = FileProvider.getUriForFile(context, authority, File(dir("photos"), "${UUID.randomUUID()}.jpg"))

    /** Gallery picks are copied in, because access to the original can be revoked later. */
    suspend fun importPicked(source: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(dir("photos"), "${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(source)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, authority, target)
        }.getOrNull()
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    fun shareIntent(file: File, mime: String, subject: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(file))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun viewIntent(file: File, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
