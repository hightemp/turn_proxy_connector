package com.hightemp.turn_proxy_connector.credentials

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Fetches TURN credentials from Yandex Telemost.
 *
 * Port of the Go reference implementation `getYandexCreds` from
 * tmp/vk-turn-proxy/client/main.go.
 *
 * Flow:
 * 1. GET conference info from cloud-api.yandex.ru
 * 2. Connect to media server via WebSocket (OkHttp)
 * 3. Send "hello" message
 * 4. Receive serverHello with ICE servers (TURN credentials)
 */
open class YandexCredentialFetcher(private val link: String) : CredentialFetcher {

    /**
     * Conference data extracted from the Yandex API response.
     */
    data class ConferenceData(
        val roomId: String,
        val peerId: String,
        val wssUrl: String,
        val credentials: String
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
        private const val TELEMOST_CONF_HOST = "cloud-api.yandex.ru"
        private const val WS_TIMEOUT_SECONDS = 15L

        /**
         * Extract the link ID from a Telemost URL.
         * "https://telemost.yandex.ru/j/123456789" -> "123456789"
         */
        fun extractLinkId(link: String): String {
            val cleaned = if (link.contains("/j/")) {
                link.substringAfter("/j/")
            } else {
                link
            }
            val idx = cleaned.indexOfFirst { it == '?' || it == '#' || it == '/' }
            return if (idx > 0) cleaned.substring(0, idx) else cleaned
        }

        /**
         * Parse conference response JSON.
         */
        fun parseConferenceResponse(json: String): ConferenceData? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val roomId = obj.get("room_id")?.asString ?: return null
                val peerId = obj.get("peer_id")?.asString ?: return null
                val clientConfig = obj.getAsJsonObject("client_configuration") ?: return null
                val wssUrl = clientConfig.get("media_server_url")?.asString ?: return null
                val credentials = obj.get("credentials")?.asString ?: return null

                ConferenceData(roomId, peerId, wssUrl, credentials)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse TURN credentials from WebSocket serverHello response.
         * Skips STUN-only servers and TCP transport URLs.
         */
        fun parseTurnFromServerHello(json: String): TurnCredentials? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val serverHello = obj.getAsJsonObject("serverHello") ?: return null
                val rtcConfig = serverHello.getAsJsonObject("rtcConfiguration") ?: return null
                val iceServers = rtcConfig.getAsJsonArray("iceServers") ?: return null

                for (server in iceServers) {
                    val serverObj = server.asJsonObject
                    val username = serverObj.get("username")?.asString ?: continue
                    val credential = serverObj.get("credential")?.asString ?: continue

                    val urlsElement = serverObj.get("urls") ?: continue
                    val urls: List<String> = when {
                        urlsElement.isJsonArray -> urlsElement.asJsonArray.map { it.asString }
                        urlsElement.isJsonPrimitive -> listOf(urlsElement.asString)
                        else -> continue
                    }

                    for (url in urls) {
                        if (!url.startsWith("turn:") && !url.startsWith("turns:")) continue
                        if (url.contains("transport=tcp")) continue

                        val clean = url.split("?")[0]
                        val address = clean.removePrefix("turn:").removePrefix("turns:")
                        return TurnCredentials(username, credential, address)
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Build the "hello" WebSocket message for Yandex Telemost.
         */
        fun buildHelloMessage(data: ConferenceData): String {
            val uid = UUID.randomUUID().toString()
            val sdkInitId = UUID.randomUUID().toString()
            val msg = mapOf(
                "uid" to uid,
                "hello" to mapOf(
                    "participantMeta" to mapOf(
                        "name" to "Guest",
                        "role" to "SPEAKER",
                        "description" to "",
                        "sendAudio" to false,
                        "sendVideo" to false
                    ),
                    "participantAttributes" to mapOf(
                        "name" to "Guest",
                        "role" to "SPEAKER",
                        "description" to ""
                    ),
                    "sendAudio" to false,
                    "sendVideo" to false,
                    "sendSharing" to false,
                    "participantId" to data.peerId,
                    "roomId" to data.roomId,
                    "serviceName" to "telemost",
                    "credentials" to data.credentials,
                    "sdkInfo" to mapOf(
                        "implementation" to "browser",
                        "version" to "5.15.0",
                        "userAgent" to USER_AGENT,
                        "hwConcurrency" to 4
                    ),
                    "sdkInitializationId" to sdkInitId,
                    "disablePublisher" to false,
                    "disableSubscriber" to false,
                    "disableSubscriberAudio" to false,
                    "capabilitiesOffer" to mapOf(
                        "offerAnswerMode" to listOf("SEPARATE"),
                        "initialSubscriberOffer" to listOf("ON_HELLO"),
                        "slotsMode" to listOf("FROM_CONTROLLER"),
                        "simulcastMode" to listOf("DISABLED"),
                        "selfVadStatus" to listOf("FROM_SERVER"),
                        "dataChannelSharing" to listOf("TO_RTP"),
                        "videoEncoderConfig" to listOf("NO_CONFIG"),
                        "dataChannelVideoCodec" to listOf("VP8"),
                        "bandwidthLimitationReason" to listOf("BANDWIDTH_REASON_DISABLED"),
                        "sdkDefaultDeviceManagement" to listOf("SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED"),
                        "joinOrderLayout" to listOf("JOIN_ORDER_LAYOUT_DISABLED"),
                        "pinLayout" to listOf("PIN_LAYOUT_DISABLED"),
                        "sendSelfViewVideoSlot" to listOf("SEND_SELF_VIEW_VIDEO_SLOT_DISABLED"),
                        "serverLayoutTransition" to listOf("SERVER_LAYOUT_TRANSITION_DISABLED"),
                        "sdkPublisherOptimizeBitrate" to listOf("SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED"),
                        "sdkNetworkLostDetection" to listOf("SDK_NETWORK_LOST_DETECTION_DISABLED"),
                        "sdkNetworkPathMonitor" to listOf("SDK_NETWORK_PATH_MONITOR_DISABLED"),
                        "publisherVp9" to listOf("PUBLISH_VP9_DISABLED"),
                        "svcMode" to listOf("SVC_MODE_DISABLED"),
                        "subscriberOfferAsyncAck" to listOf("SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED"),
                        "svcModes" to listOf("FALSE"),
                        "reportTelemetryModes" to listOf("TRUE"),
                        "keepDefaultDevicesModes" to listOf("TRUE")
                    )
                )
            )
            return Gson().toJson(msg)
        }
    }

    override fun fetch(): TurnCredentials? {
        return try {
            val linkId = extractLinkId(link)
            fetchCredentials(linkId)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCredentials(linkId: String): TurnCredentials? {
        // Step 1: Get conference info
        val encodedLink = "https%3A%2F%2Ftelemost.yandex.ru%2Fj%2F$linkId"
        val confUrl = "https://$TELEMOST_CONF_HOST/telemost_front/v2/telemost/conferences/$encodedLink/connection?next_gen_media_platform_allowed=false"

        val confJson = doGet(confUrl)
        val confData = parseConferenceResponse(confJson) ?: return null

        // Step 2-4: WebSocket connection to media server
        return fetchCredsViaWebSocket(confData)
    }

    /**
     * Connect to the Yandex media server via WebSocket, send hello,
     * and receive serverHello containing TURN credentials.
     *
     * Protocol (from Go reference):
     *   1. Dial wss://... with Origin + User-Agent headers
     *   2. Send JSON hello message
     *   3. Read messages in loop:
     *      - "ack" messages → skip
     *      - "serverHello" with rtcConfiguration.iceServers → extract TURN creds
     *   4. Return TurnCredentials or null on timeout (15s)
     */
    internal fun fetchCredsViaWebSocket(confData: ConferenceData): TurnCredentials? {
        val client = createWebSocketClient()

        val request = Request.Builder()
            .url(confData.wssUrl)
            .header("Origin", "https://telemost.yandex.ru")
            .header("User-Agent", USER_AGENT)
            .build()

        val result = CompletableFuture<TurnCredentials?>()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildHelloMessage(confData)
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Skip ack messages
                try {
                    val obj = JsonParser.parseString(text).asJsonObject
                    if (obj.has("ack")) return
                } catch (_: Exception) {}

                // Try to parse TURN credentials from serverHello
                val creds = parseTurnFromServerHello(text)
                if (creds != null) {
                    result.complete(creds)
                    webSocket.close(1000, "done")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // If we haven't received creds yet, complete with null
                result.complete(null)
            }
        })

        return try {
            result.get(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            try { ws.close(1000, "done") } catch (_: Exception) {}
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * Create an OkHttpClient for the WebSocket connection.
     * Extracted to allow overriding in tests.
     */
    internal open fun createWebSocketClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun doGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Referer", "https://telemost.yandex.ru/")
        conn.setRequestProperty("Origin", "https://telemost.yandex.ru")
        conn.setRequestProperty("Client-Instance-Id", UUID.randomUUID().toString())
        conn.connectTimeout = 20000
        conn.readTimeout = 20000

        val reader = BufferedReader(InputStreamReader(
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        ))
        return reader.use { it.readText() }
    }
}
