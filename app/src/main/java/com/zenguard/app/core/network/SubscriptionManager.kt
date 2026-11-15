package com.zenguard.app.core.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.zenguard.app.core.config.OutboundNode
import com.zenguard.app.core.config.SubscriptionInfo
import com.zenguard.app.core.config.UriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Metadata extracted from subscription HTTP response headers (Remnawave / Happ compatible).
 */
data class SubscriptionMetadata(
    val userInfo: String? = null,
    val profileTitle: String? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireTimestamp: Long? = null
) {
    companion object {
        fun parse(userInfo: String?, profileTitle: String?): SubscriptionMetadata {
            var upload: Long? = null
            var download: Long? = null
            var total: Long? = null
            var expire: Long? = null

            if (!userInfo.isNullOrBlank()) {
                val parts = userInfo.split(";")
                for (part in parts) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        val key = kv[0].trim().lowercase()
                        val value = kv[1].trim().toLongOrNull()
                        when (key) {
                            "upload" -> upload = value
                            "download" -> download = value
                            "total" -> total = value
                            "expire" -> expire = value
                        }
                    }
                }
            }

            return SubscriptionMetadata(
                userInfo = userInfo,
                profileTitle = profileTitle,
                uploadBytes = upload,
                downloadBytes = download,
                totalBytes = total,
                expireTimestamp = expire
            )
        }
    }
}

/**
 * Result of fetching a subscription, containing both outbound nodes and optional headers metadata.
 */
data class SubscriptionResult(
    val nodes: List<OutboundNode>,
    val userInfo: String? = null,
    val profileTitle: String? = null,
    val metadata: SubscriptionMetadata? = null
)

/**
 * Handles fetching remote proxy subscriptions and measuring outbound node latencies in parallel.
 * Supports Remnawave / Telegram bot device registration headers (Happ compatible User-Agent, x-hwid).
 */
object SubscriptionManager {

    private const val TAG = "SubscriptionManager"
    const val DEFAULT_USER_AGENT = "Happ/4.2.1"
    private const val PREFS_NAME = "zenguard_subscription_prefs"
    private const val KEY_HWID = "x_hwid"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5

    @Volatile
    var lastUserInfo: String? = null
        private set

    @Volatile
    var lastProfileTitle: String? = null
        private set

    @Volatile
    var lastMetadata: SubscriptionMetadata? = null
        private set

    private val fallbackHwid: String by lazy {
        val raw = "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.HARDWARE}|${Build.MODEL}|${Build.PRODUCT}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Backward-compatible property returning the hardware identifier fallback.
     */
    val deviceHwid: String
        get() = getOrCreateHwid(null)

    /**
     * Returns a persistent 16-char hex hardware identifier (HWID).
     *
     * When [context] is provided:
     * - Returns cached HWID from [SharedPreferences] if present.
     * - Derives a persistent 16-char hex HWID from [Settings.Secure.ANDROID_ID].
     * - Caches the derived HWID in [SharedPreferences].
     *
     * When [context] is null (or on failure):
     * - Falls back to a deterministic 16-char hex hash of the device's [Build] fingerprint.
     */
    fun getOrCreateHwid(context: Context? = null): String {
        if (context == null) {
            return fallbackHwid
        }

        return try {
            val appContext = context.applicationContext ?: context
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cached = prefs.getString(KEY_HWID, null)
            if (!cached.isNullOrBlank()) {
                return cached
            }

            val androidId = try {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }

            val hwid = if (!androidId.isNullOrBlank()) {
                val trimmed = androidId.trim()
                if (trimmed.length == 16 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    trimmed.lowercase()
                } else {
                    val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
                    digest.take(8).joinToString("") { "%02x".format(it) }
                }
            } else {
                fallbackHwid
            }

            prefs.edit().putString(KEY_HWID, hwid).apply()
            hwid
        } catch (e: Exception) {
            Log.w(TAG, "Failed to derive persistent HWID, falling back to Build fingerprint", e)
            fallbackHwid
        }
    }

    /**
     * Fetches subscription content from a remote HTTP/HTTPS URL, follows redirects,
     * decodes the subscription payload, extracts subscription headers, and returns [SubscriptionResult].
     */
    suspend fun fetchSubscriptionDetailed(
        url: String,
        subscriptionId: String? = null,
        userAgent: String = DEFAULT_USER_AGENT,
        context: Context? = null
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        var currentUrl = url.trim()
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
            throw IllegalArgumentException("Invalid subscription URL: $url")
        }

        val hwid = getOrCreateHwid(context)
        var redirects = 0
        var connection: HttpURLConnection? = null
        var responseBody: String
        var rawUserInfo: String? = null
        var rawProfileTitle: String? = null

        try {
            while (true) {
                val targetUrl = URL(currentUrl)
                connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("x-hwid", hwid)
                    setRequestProperty("x-device-os", "Android")
                    setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "15")
                    setRequestProperty("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}")
                }

                val hUserInfo = getHeader(connection, "subscription-userinfo")
                if (!hUserInfo.isNullOrBlank()) {
                    rawUserInfo = hUserInfo
                }
                val hTitle = getHeader(connection, "profile-title")
                if (!hTitle.isNullOrBlank()) {
                    rawProfileTitle = hTitle
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val redirectLocation = connection.getHeaderField("Location")
                        ?: throw IOException("HTTP redirect without Location header ($responseCode)")

                    currentUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        URL(targetUrl, redirectLocation).toString()
                    }

                    connection.disconnect()
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw IOException("Too many redirects: $redirects")
                    }
                    continue
                } else if (responseCode in 200..299) {
                    val finalUserInfo = getHeader(connection, "subscription-userinfo")
                    if (!finalUserInfo.isNullOrBlank()) {
                        rawUserInfo = finalUserInfo
                    }
                    val finalTitle = getHeader(connection, "profile-title")
                    if (!finalTitle.isNullOrBlank()) {
                        rawProfileTitle = finalTitle
                    }

                    responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    break
                } else {
                    throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
                }
            }
        } finally {
            connection?.disconnect()
        }

        val decodedTitle = rawProfileTitle?.let { decodeProfileTitle(it) }

        if (!rawUserInfo.isNullOrBlank()) {
            Log.i(TAG, "subscription-userinfo: $rawUserInfo")
        }
        if (!decodedTitle.isNullOrBlank()) {
            Log.i(TAG, "profile-title: $decodedTitle")
        }

        val metadata = SubscriptionMetadata.parse(rawUserInfo, decodedTitle)
        lastUserInfo = rawUserInfo
        lastProfileTitle = decodedTitle
        lastMetadata = metadata

        val parsedNodes = UriParser.parseBatch(responseBody)
        val finalNodes = if (subscriptionId != null) {
            parsedNodes.map { it.copy(subscriptionId = subscriptionId) }
        } else {
            parsedNodes
        }

        SubscriptionResult(
            nodes = finalNodes,
            userInfo = rawUserInfo,
            profileTitle = decodedTitle,
            metadata = metadata
        )
    }

    /**
     * Fetches subscription content from a remote HTTP/HTTPS URL, follows redirects,
     * decodes the subscription payload, and returns parsed [OutboundNode] list.
     * Keeps backward compatibility for existing callers.
     */
    suspend fun fetchSubscription(
        url: String,
        subscriptionId: String? = null,
        userAgent: String = DEFAULT_USER_AGENT,
        context: Context? = null
    ): List<OutboundNode> = fetchSubscriptionDetailed(
        url = url,
        subscriptionId = subscriptionId,
        userAgent = userAgent,
        context = context
    ).nodes

    /**
     * Updates an existing [SubscriptionInfo] by downloading its latest nodes.
     * Returns a pair of updated [SubscriptionInfo] and the fetched [OutboundNode] list.
     */
    suspend fun updateSubscription(
        subscription: SubscriptionInfo,
        context: Context? = null
    ): Pair<SubscriptionInfo, List<OutboundNode>> = withContext(Dispatchers.IO) {
        val result = fetchSubscriptionDetailed(
            url = subscription.url,
            subscriptionId = subscription.id,
            context = context
        )
        val updatedSub = subscription.copy(
            name = if (subscription.name == "Subscription" && !result.profileTitle.isNullOrBlank()) {
                result.profileTitle
            } else {
                subscription.name
            },
            lastUpdated = System.currentTimeMillis(),
            nodeCount = result.nodes.size
        )
        Pair(updatedSub, result.nodes)
    }

    /**
     * Parallel TCP socket latency tester.
     * Measures TCP handshake latency for all nodes concurrently using [Semaphore] (up to 8 concurrent tests).
     *
     * @param nodes List of nodes to measure.
     * @param timeoutMs Timeout in milliseconds for each connection attempt (default 2500ms).
     * @return Updated list of nodes with [OutboundNode.latencyMs] set (elapsed ms or -1 if unreachable).
     */
    suspend fun measureLatency(
        nodes: List<OutboundNode>,
        timeoutMs: Int = 2500
    ): List<OutboundNode> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(8)
        coroutineScope {
            nodes.map { node ->
                async {
                    semaphore.withPermit {
                        val latency = testTcpLatency(node.server, node.port, timeoutMs)
                        node.copy(latencyMs = latency)
                    }
                }
            }.map { it.await() }
        }
    }

    /**
     * Connects to [server]:[port] via TCP socket and returns elapsed milliseconds,
     * or -1L if unreachable / timed out.
     */
    fun testTcpLatency(server: String, port: Int, timeoutMs: Int = 2500): Long {
        if (server.isBlank() || port <= 0 || port > 65535) {
            return -1L
        }

        return try {
            val socket = Socket()
            val startTime = System.currentTimeMillis()
            socket.connect(InetSocketAddress(server, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - startTime
            try {
                socket.close()
            } catch (_: Exception) {}
            if (elapsed >= 0) elapsed else 0L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun getHeader(connection: HttpURLConnection, name: String): String? {
        val field = connection.getHeaderField(name)
        if (!field.isNullOrBlank()) return field
        return connection.headerFields.entries
            .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
            ?.value?.firstOrNull { !it.isNullOrBlank() }
    }

    private fun decodeProfileTitle(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("\"") && str.endsWith("\"") && str.length >= 2) {
            str = str.substring(1, str.length - 1).trim()
        }
        return try {
            if (str.startsWith("base64:", ignoreCase = true)) {
                val b64 = str.substring(7).trim()
                val bytes = try {
                    java.util.Base64.getDecoder().decode(b64)
                } catch (_: Throwable) {
                    try {
                        java.util.Base64.getUrlDecoder().decode(b64)
                    } catch (_: Throwable) {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    }
                }
                String(bytes, Charsets.UTF_8).trim()
            } else if (str.startsWith("UTF-8''", ignoreCase = true)) {
                val payload = str.substring(7).trim()
                URLDecoder.decode(payload, "UTF-8").trim()
            } else if (str.contains("%")) {
                URLDecoder.decode(str, "UTF-8").trim()
            } else {
                str
            }
        } catch (_: Exception) {
            str
        }
    }
}
