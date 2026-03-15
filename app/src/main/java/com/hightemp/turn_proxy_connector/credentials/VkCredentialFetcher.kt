package com.hightemp.turn_proxy_connector.credentials

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Fetches TURN credentials from VK Calls API.
 *
 * Port of the Go reference implementation `getVkCreds` from
 * tmp/vk-turn-proxy/client/main.go.
 *
 * Flow:
 * 1. Get anonymous token from login.vk.ru
 * 2. Get payload from calls.getAnonymousAccessTokenPayload
 * 3. Get messages token from login.vk.ru with payload
 * 4. Get anonymous call token from calls.getAnonymousToken
 * 5. Get OK session key from calls.okcdn.ru
 * 6. Join conversation and extract TURN credentials
 */
class VkCredentialFetcher(private val link: String) : CredentialFetcher {

    companion object {
        private const val CLIENT_SECRET = "QbYic1K3lEV5kTGiqlq2"
        private const val CLIENT_ID = "6287487"
        private const val APP_ID = "6287487"
        private const val OK_APP_KEY = "CGMMEJLGDIHBABABA"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

        /**
         * Extract the link ID from a VK call URL.
         * "https://vk.com/call/join/abc123?x=y" -> "abc123"
         */
        fun extractLinkId(link: String): String {
            val cleaned = if (link.contains("join/")) {
                link.substringAfter("join/")
            } else {
                link
            }
            // Strip query params and fragments
            val idx = cleaned.indexOfFirst { it == '?' || it == '#' || it == '/' }
            return if (idx > 0) cleaned.substring(0, idx) else cleaned
        }

        /**
         * Parse a TURN URL, removing turn:/turns: prefix and query params.
         * "turn:192.168.1.1:3478?transport=udp" -> "192.168.1.1:3478"
         */
        fun parseTurnUrl(url: String): String {
            val clean = url.split("?")[0]
            return clean
                .removePrefix("turn:")
                .removePrefix("turns:")
        }

        /**
         * Parse access_token from anonymous token response.
         * {"data":{"access_token":"xxx"}} -> "xxx"
         */
        fun parseTokenFromAnonResponse(json: String): String? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                obj.getAsJsonObject("data")?.get("access_token")?.asString
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse payload from API response.
         * {"response":{"payload":"xxx"}} -> "xxx"
         */
        fun parsePayloadFromApiResponse(json: String): String? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                obj.getAsJsonObject("response")?.get("payload")?.asString
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse TURN credentials from VK join conversation response.
         */
        fun parseTurnCredsFromJoinResponse(json: String): TurnCredentials? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val turnServer = obj.getAsJsonObject("turn_server") ?: return null
                val username = turnServer.get("username")?.asString ?: return null
                val credential = turnServer.get("credential")?.asString ?: return null
                val urls = turnServer.getAsJsonArray("urls")
                if (urls == null || urls.size() == 0) return null

                val url = urls[0].asString
                val address = parseTurnUrl(url)

                TurnCredentials(username, credential, address)
            } catch (_: Exception) {
                null
            }
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
        // Step 1: Get first anonymous token
        val data1 = "client_secret=$CLIENT_SECRET&client_id=$CLIENT_ID&scopes=audio_anonymous%2Cvideo_anonymous%2Cphotos_anonymous%2Cprofile_anonymous&isApiOauthAnonymEnabled=false&version=1&app_id=$APP_ID"
        val resp1 = doPost("https://login.vk.ru/?act=get_anonym_token", data1)
        val token1 = parseTokenFromAnonResponse(resp1) ?: return null

        // Step 2: Get payload
        val data2 = "access_token=$token1"
        val resp2 = doPost("https://api.vk.ru/method/calls.getAnonymousAccessTokenPayload?v=5.264&client_id=$CLIENT_ID", data2)
        val payload = parsePayloadFromApiResponse(resp2) ?: return null

        // Step 3: Get messages token
        val data3 = "client_id=$CLIENT_ID&token_type=messages&payload=$payload&client_secret=$CLIENT_SECRET&version=1&app_id=$APP_ID"
        val resp3 = doPost("https://login.vk.ru/?act=get_anonym_token", data3)
        val token3 = parseTokenFromAnonResponse(resp3) ?: return null

        // Step 4: Get anonymous call token
        val data4 = "vk_join_link=https://vk.com/call/join/$linkId&name=123&access_token=$token3"
        val resp4 = doPost("https://api.vk.ru/method/calls.getAnonymousToken?v=5.264", data4)
        val token4 = try {
            val obj = JsonParser.parseString(resp4).asJsonObject
            obj.getAsJsonObject("response")?.get("token")?.asString
        } catch (_: Exception) { null } ?: return null

        // Step 5: Get OK session key
        val deviceId = UUID.randomUUID().toString()
        val data5 = "session_data=%7B%22version%22%3A2%2C%22device_id%22%3A%22$deviceId%22%2C%22client_version%22%3A1.1%2C%22client_type%22%3A%22SDK_JS%22%7D&method=auth.anonymLogin&format=JSON&application_key=$OK_APP_KEY"
        val resp5 = doPost("https://calls.okcdn.ru/fb.do", data5)
        val sessionKey = try {
            val obj = JsonParser.parseString(resp5).asJsonObject
            obj.get("session_key")?.asString
        } catch (_: Exception) { null } ?: return null

        // Step 6: Join conversation and get TURN creds
        val data6 = "joinLink=$linkId&isVideo=false&protocolVersion=5&anonymToken=$token4&method=vchat.joinConversationByLink&format=JSON&application_key=$OK_APP_KEY&session_key=$sessionKey"
        val resp6 = doPost("https://calls.okcdn.ru/fb.do", data6)
        return parseTurnCredsFromJoinResponse(resp6)
    }

    private fun doPost(urlStr: String, body: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }

        val reader = BufferedReader(InputStreamReader(
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        ))
        return reader.use { it.readText() }
    }
}
