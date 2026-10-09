package shop

import kotlinx.serialization.json.*
import java.time.Instant

fun toEl(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Instant -> JsonPrimitive(v.toString())
    is List<*> -> JsonArray(v.map(::toEl))
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toEl(it.value) })
    else -> JsonPrimitive(v.toString())
}

fun obj(vararg p: Pair<String, Any?>): JsonObject = JsonObject(p.associate { it.first to toEl(it.second) })

/** Envelope standar docs/api.md §2. */
fun envelope(data: Any?, message: String = "OK") =
    obj("success" to true, "data" to data, "message" to message, "error" to null)

fun errorEnvelope(code: String, message: String, retryable: Boolean = false, retryAfterSec: Int? = null) =
    obj(
        "success" to false, "data" to null, "message" to message,
        "error" to obj("code" to code, "retryable" to retryable, "retry_after_sec" to retryAfterSec),
    )

fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
