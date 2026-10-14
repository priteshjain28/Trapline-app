package app.site.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.site.SiteApplication
import app.site.core.remote.FileStore
import app.site.core.remote.TokenStore
import app.site.core.remote.Tokens
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The session lives in app-private storage, which other apps can't read and which is excluded from backups
 * (allowBackup=false). A stolen refresh token only works until the user signs out or deletes the account.
 */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): Tokens? {
        val access = prefs.getString("access", null) ?: return null
        return Tokens(
            access, prefs.getString("refresh", null) ?: return null, prefs.getLong("expires_at", 0),
            prefs.getString("user_id", null) ?: return null, prefs.getString("phone", null),
        )
    }

    override fun save(tokens: Tokens?) {
        prefs.edit().apply {
            if (tokens == null) clear()
            else putString("access", tokens.accessToken).putString("refresh", tokens.refreshToken)
                .putLong("expires_at", tokens.expiresAt).putString("user_id", tokens.userId).putString("phone", tokens.phone)
        }.apply()
    }
}

/** Photos are content:// URIs from our FileProvider; reports are file paths. */
class AndroidFileStore(private val context: Context) : FileStore {
    override fun read(uri: String): ByteArray? = runCatching {
        if (uri.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        else File(uri).takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    override fun saveReport(id: String, bytes: ByteArray): String =
        File(File(context.filesDir, "reports").apply { mkdirs() }, "$id.pdf").also { it.writeBytes(bytes) }.absolutePath
}

/** Background sync every 15 minutes when there's a network. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SiteApplication).container
        container.sync.syncNow()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
