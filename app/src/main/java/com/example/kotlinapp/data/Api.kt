package com.example.kotlinapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RawResponse(val status: Int, val body: JsonObject, val retryAfter: Int?) {
    val success get() = body["success"]?.jsonPrimitive?.booleanOrNull == true
    val data: JsonElement? get() = body["data"]?.takeIf { it !is JsonNull }
    val message get() = body["message"]?.jsonPrimitive?.contentOrNull ?: ""
    val errorCode get() = (body["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
    val retryable get() = (body["error"] as? JsonObject)?.get("retryable")?.jsonPrimitive?.booleanOrNull ?: false
}

/** Klien HTTP tipis. Timeout wajar (connect 5s, read 10s). Retry TIDAK otomatis di sini: diputuskan pemanggil. */
class Api(private val settings: AppPrefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    @Throws(IOException::class)
    suspend fun call(method: String, path: String, body: JsonObject? = null, idemKey: String? = null): RawResponse =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(settings.baseUrl + path)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer demo-${settings.userId}")
                .apply { if (idemKey != null) header("Idempotency-Key", idemKey) }
                .method(method, if (method == "GET") null else (body ?: JsonObject(emptyMap())).toString().toRequestBody(json))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse {
                    JsonObject(mapOf("success" to JsonPrimitive(false), "message" to JsonPrimitive("Respons server tidak dikenali.")))
                }
                RawResponse(r.code, obj, r.header("Retry-After")?.toIntOrNull())
            }
        }
}
