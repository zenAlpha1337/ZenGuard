package com.zenguard.app.core.network

import android.os.Build
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
import java.security.MessageDigest

/**
 * Handles fetching remote proxy subscriptions and measuring outbound node latencies in parallel.
 */
object SubscriptionManager {

    private const val DEFAULT_USER_AGENT = "Happ/2.0.0/android"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5

    private val deviceHwid: String by lazy {
        val raw = "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.HARDWARE}|${Build.MODEL}|${Build.PRODUCT}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Fetches subscription content from a remote HTTP/HTTPS URL, follows redirects,
     * decodes the subscription payload, and returns parsed [OutboundNode] list.
     */
    suspend fun fetchSubscription(
        url: String,
        subscriptionId: String? = null,
        userAgent: String = DEFAULT_USER_AGENT
    ): List<OutboundNode> = withContext(Dispatchers.IO) {
        var currentUrl = url.trim()
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
            throw IllegalArgumentException("Invalid subscription URL: $url")
        }

        var redirects = 0
        var connection: HttpURLConnection? = null
        var responseBody: String

        try {
            while (true) {
                val targetUrl = URL(currentUrl)
                connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("x-hwid", deviceHwid)
                    setRequestProperty("x-device-os", "Android")
                    setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "15")
                    setRequestProperty("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}")
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
                    responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    break
                } else {
                    throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
                }
            }
        } finally {
            connection?.disconnect()
        }

        val parsedNodes = UriParser.parseBatch(responseBody)
        if (subscriptionId != null) {
            parsedNodes.map { it.copy(subscriptionId = subscriptionId) }
        } else {
            parsedNodes
        }
    }

    /**
     * Updates an existing [SubscriptionInfo] by downloading its latest nodes.
     * Returns a pair of updated [SubscriptionInfo] and the fetched [OutboundNode] list.
     */
    suspend fun updateSubscription(
        subscription: SubscriptionInfo
    ): Pair<SubscriptionInfo, List<OutboundNode>> = withContext(Dispatchers.IO) {
        val nodes = fetchSubscription(url = subscription.url, subscriptionId = subscription.id)
        val updatedSub = subscription.copy(
            lastUpdated = System.currentTimeMillis(),
            nodeCount = nodes.size
        )
        Pair(updatedSub, nodes)
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
}
