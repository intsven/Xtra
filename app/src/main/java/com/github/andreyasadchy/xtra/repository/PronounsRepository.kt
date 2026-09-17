package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class PronounsRepository(
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    private val client by lazy {
        okHttpClient.value.newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
            authenticator(Authenticator.NONE)
            proxyAuthenticator(Authenticator.NONE)
            cookieJar(CookieJar.NO_COOKIES)
            cache(null)
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            callTimeout(5, TimeUnit.SECONDS)
            connectTimeout(3, TimeUnit.SECONDS)
            readTimeout(3, TimeUnit.SECONDS)
        }.build()
    }
    private val mutex = Mutex()
    private val users = LinkedHashMap<String, CachedPronouns>(256, 0.75f, true)
    private var definitions: Map<String, Pronoun> = emptyMap()
    private var definitionsExpireAt = 0L
    private var retryAt = 0L

    suspend fun getPronouns(login: String): String? {
        val normalizedLogin = login.lowercase(Locale.ROOT)
        if (!normalizedLogin.matches(Regex("[a-z0-9_]{1,25}"))) {
            return null
        }
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(12_000L) {
                mutex.withLock {
                    val now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
                    users[normalizedLogin]?.takeIf { now < it.expiresAt }?.let {
                        return@withLock it.text
                    }
                    if (now < retryAt) {
                        return@withLock null
                    }
                    val text = try {
                        val user = request("users/$normalizedLogin", 8_192, true)?.let {
                            json.decodeFromString<PronounsUser>(it)
                        }
                        if (user == null) {
                            null
                        } else {
                            if (!user.login.equals(normalizedLogin, true)) {
                                throw IOException("Unexpected pronouns user")
                            }
                            if (now >= definitionsExpireAt) {
                                definitions = json.decodeFromString<Map<String, Pronoun>>(request("pronouns", 65_536)!!).also {
                                    if (it.isEmpty() || it.size > 256) {
                                        throw IOException("Invalid pronouns catalog")
                                    }
                                }
                                definitionsExpireAt = now + TimeUnit.HOURS.toMillis(24)
                            }
                            formatPronouns(user, definitions)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        retryAt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + TimeUnit.MINUTES.toMillis(1)
                        return@withLock null
                    }
                    currentCoroutineContext().ensureActive()
                    users[normalizedLogin] = CachedPronouns(text, now + TimeUnit.MINUTES.toMillis(15))
                    if (users.size > 256) {
                        users.entries.iterator().apply {
                            next()
                            remove()
                        }
                    }
                    text
                }
            }
        }
    }

    private suspend fun request(path: String, maxBytes: Long, allowMissing: Boolean = false): String? {
        val request = Request.Builder()
            .url("https://api.pronouns.alejo.io/v1/$path")
            .build()
        return client.newCall(request).executeAsync().use { response ->
            if (allowMissing && response.code == 404) {
                return@use null
            }
            if (!response.isSuccessful) {
                throw IOException("Pronouns request failed")
            }
            val source = response.body.source()
            if (source.request(maxBytes + 1)) {
                throw IOException("Pronouns response too large")
            }
            currentCoroutineContext().ensureActive()
            source.readUtf8()
        }
    }

    private fun formatPronouns(user: PronounsUser, pronouns: Map<String, Pronoun>): String? {
        val primary = pronouns[user.pronounId] ?: return null
        if (primary.subject.isBlank() || primary.subject.length > 64) {
            return null
        }
        if (primary.singular) {
            return primary.subject
        }
        val secondary = pronouns[user.altPronounId]?.subject ?: primary.objectForm
        if (secondary.isBlank() || secondary.length > 64) {
            return null
        }
        return "${primary.subject}/$secondary"
    }

    private data class CachedPronouns(val text: String?, val expiresAt: Long)

    @Serializable
    private data class PronounsUser(
        @SerialName("channel_login") val login: String,
        @SerialName("pronoun_id") val pronounId: String,
        @SerialName("alt_pronoun_id") val altPronounId: String? = null,
    )

    @Serializable
    private data class Pronoun(
        val subject: String,
        @SerialName("object") val objectForm: String,
        val singular: Boolean,
    )
}
