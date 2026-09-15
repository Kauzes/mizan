package dev.kauzes.mizan.merchant.data

import dev.kauzes.mizan.merchant.domain.RefreshOutcome
import dev.kauzes.mizan.merchant.domain.Session
import dev.kauzes.mizan.merchant.domain.SignInOutcome
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The platform's token endpoints, through the gateway.
 *
 * The refresh token travels in the request and response bodies. The console keeps its refresh token in
 * a HttpOnly cookie because a browser page cannot keep a secret (ADR 0035); a phone can, in the Keystore,
 * and identity accepts the token in the body for exactly this kind of client.
 */
class HttpTokenService(
    private val gatewayUrl: String,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) : TokenService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun signIn(email: String, password: String): SignInOutcome = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SignInRequest.serializer(), SignInRequest(email, password))
        try {
            http.newCall(post("/api/v1/tokens", body)).execute().use { response ->
                when {
                    response.isSuccessful -> SignInOutcome.SignedIn(sessionFrom(response.body.string()))
                    response.code == 401 -> SignInOutcome.Refused
                    else -> SignInOutcome.Failed("the platform answered ${response.code}")
                }
            }
        } catch (noAnswer: IOException) {
            SignInOutcome.Unreachable(describe(noAnswer))
        } catch (malformed: IllegalArgumentException) {
            SignInOutcome.Failed("the platform's answer could not be read: ${malformed.message}")
        }
    }

    override suspend fun refresh(refreshToken: String): RefreshOutcome = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        try {
            http.newCall(post("/api/v1/tokens/refresh", body)).execute().use { response ->
                when {
                    response.isSuccessful -> RefreshOutcome.Renewed(sessionFrom(response.body.string()))
                    // A refresh token the platform does not accept, for whatever reason, ends the
                    // session. 400 as well as 401: a token it cannot even read is not one to keep.
                    response.code == 401 || response.code == 400 -> RefreshOutcome.Rejected
                    // Anything else is the platform being unwell, which is not a verdict on the session.
                    else -> RefreshOutcome.Unreachable("the platform answered ${response.code}")
                }
            }
        } catch (noAnswer: IOException) {
            RefreshOutcome.Unreachable(describe(noAnswer))
        }
    }

    override suspend fun signOut(refreshToken: String) {
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
            http.newCall(post("/api/v1/tokens/sign-out", body)).execute().close()
        }
    }

    private fun post(path: String, body: String): Request = Request.Builder()
        .url(gatewayUrl.trimEnd('/') + path)
        .post(body.toRequestBody(JSON))
        .build()

    /** The pair the platform issued, with expiry counted on this phone's clock from when it arrived. */
    private fun sessionFrom(body: String): Session {
        val arrived = now()
        val pair = json.decodeFromString(TokenPair.serializer(), body)
        val claims = AccessTokenClaims.of(pair.accessToken)
        return Session(
            accessToken = pair.accessToken,
            refreshToken = pair.refreshToken,
            accessExpiresAt = arrived + pair.expiresIn * 1_000,
            refreshExpiresAt = arrived + pair.refreshExpiresIn * 1_000,
            merchantId = claims.merchantId,
            userId = claims.userId,
        )
    }

    private fun describe(failure: IOException): String =
        failure.message?.let { "${failure.javaClass.simpleName}: $it" } ?: failure.javaClass.simpleName

    @Serializable
    private data class SignInRequest(val email: String, val password: String) {
        override fun toString() = "SignInRequest(email=$email, password=***)"
    }

    @Serializable
    private data class RefreshRequest(val refreshToken: String) {
        override fun toString() = "RefreshRequest(refreshToken=***)"
    }

    @Serializable
    private data class TokenPair(
        val accessToken: String,
        val tokenType: String = "Bearer",
        val expiresIn: Long,
        val refreshToken: String,
        val refreshExpiresIn: Long,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Who the access token says the caller is.
 *
 * Read, not verified: the phone has no reason to doubt a token it was just handed by the platform over
 * the connection it chose, and the gateway verifies it on every request anyway. What the phone needs
 * from it is which merchant it is acting for, to put in the paths of every later request.
 */
data class AccessTokenClaims(val userId: String, val merchantId: String) {
    companion object {
        fun of(token: String): AccessTokenClaims {
            val parts = token.split('.')
            require(parts.size == 3) { "not a JWT" }
            val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            val claims = Json.parseToJsonElement(payload).jsonObject
            val userId = requireNotNull(claims["sub"]?.jsonPrimitive?.content) { "the token names no user" }
            val merchantId = requireNotNull(claims["merchant"]?.jsonPrimitive?.content) { "the token names no merchant" }
            return AccessTokenClaims(userId, merchantId)
        }
    }
}
