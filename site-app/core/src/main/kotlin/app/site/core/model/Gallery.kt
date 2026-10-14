package app.site.core.model

import java.time.Instant
import java.util.UUID

/**
 * Site photos (S30). Mirrors media and albums in supabase/migrations/20261014000000_init.sql. A photo is either added
 * straight to the gallery or comes with a work update; both show here.
 */
data class Photo(
    val id: String,
    val projectId: String,
    /** A local content URI until uploaded on this phone; "storage:project-media/<path>" for photos from the server. */
    val ref: String,
    val takenAt: Instant,
    val albumId: String? = null,
    /** Set when the photo came with a work update on that task. */
    val taskId: String? = null,
    val uploadedBy: String? = null,
)

data class Album(val id: String, val projectId: String, val name: String, val photoCount: Int = 0, val coverRef: String? = null) {
    companion object {
        /** An album typed on two phones with the same name gets one id, like the server's (project, name) key. */
        fun idFor(projectId: String, name: String): String =
            UUID.nameUUIDFromBytes("album|$projectId|${name.trim().lowercase().replace(Regex("\\s+"), " ")}".toByteArray()).toString()
    }
}
