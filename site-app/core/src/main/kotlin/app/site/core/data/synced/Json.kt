package app.site.core.data.synced

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Small readers for PostgREST rows. Absent and null read the same. */
internal fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull ?: error("missing $k")
internal fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
internal fun JsonObject.dbl(k: String, default: Double = 0.0): Double = (this[k] as? JsonPrimitive)?.doubleOrNull ?: default
internal fun JsonObject.dblOrNull(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.lng(k: String, default: Long = 0): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: default
internal fun JsonObject.lngOrNull(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(k: String, default: Boolean): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObjectBuilder.putOrNull(k: String, v: String?) = put(k, v?.let { JsonPrimitive(it) } ?: JsonNull)
internal fun JsonObjectBuilder.putOrNull(k: String, v: Number?) = put(k, v?.let { JsonPrimitive(it) } ?: JsonNull)
