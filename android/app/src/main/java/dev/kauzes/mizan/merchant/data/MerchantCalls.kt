package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.AccessOutcome
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Everything under `/api/v1/merchants/{the signed in merchant}`, with a token and one reading of what an
 * answer means.
 *
 * One place, because every caller needs the same four things and getting any of them subtly different
 * would be a bug nobody sees: the token fetched (and renewed) through [SessionManager], a 401 ending the
 * session exactly once, a 4xx read as a refusal with the platform's own problem code, and anything else —
 * including no answer at all — read as "no usable answer", which is not the same as "no".
 */
class MerchantCalls(
    private val gatewayUrl: String,
    private val http: OkHttpClient,
    private val sessions: SessionManager,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** GET [path], relative to this merchant. */
    suspend fun <T> get(path: String, parse: (String) -> T): ApiResult<T> =
        send(path, body = null, key = null, parse = parse)

    /** POST [body] to [path], with an idempotency key when the endpoint takes one. */
    suspend fun <T> post(path: String, body: String?, key: String? = null, parse: (String) -> T): ApiResult<T> =
        send(path, body = body ?: "", key = key, parse = parse)

    private suspend fun <T> send(path: String, body: String?, key: String?, parse: (String) -> T): ApiResult<T> {
        val merchantId = sessions.current.value?.merchantId ?: return ApiResult.SignedOut
        val token = when (val access = sessions.accessToken()) {
            is AccessOutcome.Valid -> access.token
            AccessOutcome.SignedOut -> return ApiResult.SignedOut
            is AccessOutcome.Unreachable -> return ApiResult.Unavailable(null, null, access.because)
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${gatewayUrl.trimEnd('/')}/api/v1/merchants/$merchantId$path")
                .header("Authorization", "Bearer $token")
                .apply {
                    key?.let { header("Idempotency-Key", it) }
                    if (body == null) get() else post(body.toRequestBody(JSON))
                }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful -> ApiResult.Ok(parse(text))
                        response.code == 401 -> {
                            sessions.signOut()
                            ApiResult.SignedOut
                        }
                        response.code in 400..499 -> problem(text).let { ApiResult.Refused(response.code, it.first, it.second) }
                        else -> problem(text).let {
                            ApiResult.Unavailable(response.code, it.first, it.second ?: "the platform answered ${response.code}")
                        }
                    }
                }
            } catch (noAnswer: IOException) {
                ApiResult.Unavailable(null, null, noAnswer.message ?: noAnswer.javaClass.simpleName)
            }
        }
    }

    /** The platform's problem detail: its code and its sentence, when it sent one. */
    private fun problem(text: String): Pair<String?, String?> = runCatching {
        val body: JsonObject = json.parseToJsonElement(text).jsonObject
        body["code"]?.jsonPrimitive?.content to body["detail"]?.jsonPrimitive?.content
    }.getOrDefault(null to null)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
