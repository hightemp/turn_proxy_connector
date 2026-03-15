package com.hightemp.turn_proxy_connector.credentials

import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

/**
 * TDD tests for TURN credential fetchers.
 *
 * Since these fetchers depend on external APIs (VK, Yandex),
 * we test the parsing logic and data models rather than actual HTTP calls.
 */
class CredentialFetcherTest {

    // ---- Data models ----

    @Test
    fun `TurnCredentials holds username, password, and server address`() {
        val creds = TurnCredentials(
            username = "user123",
            password = "pass456",
            serverAddress = "turn.example.com:3478"
        )
        assertEquals("user123", creds.username)
        assertEquals("pass456", creds.password)
        assertEquals("turn.example.com:3478", creds.serverAddress)
    }

    @Test
    fun `TurnCredentials parses host and port`() {
        val creds = TurnCredentials(
            username = "u", password = "p",
            serverAddress = "192.168.1.1:3478"
        )
        assertEquals("192.168.1.1", creds.host)
        assertEquals(3478, creds.port)
    }

    @Test
    fun `TurnCredentials parses address without port defaults to 3478`() {
        val creds = TurnCredentials(
            username = "u", password = "p",
            serverAddress = "turn.example.com"
        )
        assertEquals("turn.example.com", creds.host)
        assertEquals(3478, creds.port)
    }

    // ---- VK URL parsing ----

    @Test
    fun `VkCredentialFetcher extracts link ID from full URL`() {
        val link = "https://vk.com/call/join/abc123def"
        val id = VkCredentialFetcher.extractLinkId(link)
        assertEquals("abc123def", id)
    }

    @Test
    fun `VkCredentialFetcher extracts link ID from partial URL`() {
        val id = VkCredentialFetcher.extractLinkId("abc123def")
        assertEquals("abc123def", id)
    }

    @Test
    fun `VkCredentialFetcher strips query params from link ID`() {
        val link = "https://vk.com/call/join/abc123?foo=bar"
        val id = VkCredentialFetcher.extractLinkId(link)
        assertEquals("abc123", id)
    }

    @Test
    fun `VkCredentialFetcher parseTurnUrl cleans turn prefix`() {
        val url = "turn:192.168.1.1:3478?transport=udp"
        val result = VkCredentialFetcher.parseTurnUrl(url)
        assertEquals("192.168.1.1:3478", result)
    }

    @Test
    fun `VkCredentialFetcher parseTurnUrl cleans turns prefix`() {
        val url = "turns:relay.example.com:443?transport=tcp"
        val result = VkCredentialFetcher.parseTurnUrl(url)
        assertEquals("relay.example.com:443", result)
    }

    @Test
    fun `VkCredentialFetcher parseTurnUrl handles plain address`() {
        val url = "192.168.1.1:3478"
        val result = VkCredentialFetcher.parseTurnUrl(url)
        assertEquals("192.168.1.1:3478", result)
    }

    // ---- VK JSON response parsing ----

    @Test
    fun `VkCredentialFetcher parseTokenFromAnonResponse extracts access_token`() {
        val json = """{"data":{"access_token":"token123","expires_in":3600}}"""
        val token = VkCredentialFetcher.parseTokenFromAnonResponse(json)
        assertEquals("token123", token)
    }

    @Test
    fun `VkCredentialFetcher parsePayloadFromApiResponse extracts payload`() {
        val json = """{"response":{"payload":"payload456"}}"""
        val payload = VkCredentialFetcher.parsePayloadFromApiResponse(json)
        assertEquals("payload456", payload)
    }

    @Test
    fun `VkCredentialFetcher parseTurnCredsFromJoinResponse extracts credentials`() {
        val json = """
        {
            "turn_server": {
                "username": "turnuser",
                "credential": "turnpass",
                "urls": ["turn:192.168.1.1:3478?transport=udp", "turns:192.168.1.1:5349?transport=tcp"]
            }
        }
        """.trimIndent()
        val creds = VkCredentialFetcher.parseTurnCredsFromJoinResponse(json)
        assertNotNull(creds)
        assertEquals("turnuser", creds!!.username)
        assertEquals("turnpass", creds.password)
        assertEquals("192.168.1.1:3478", creds.serverAddress)
    }

    @Test
    fun `VkCredentialFetcher parseTurnCredsFromJoinResponse returns null on invalid JSON`() {
        val creds = VkCredentialFetcher.parseTurnCredsFromJoinResponse("{}")
        assertNull(creds)
    }

    // ---- Yandex URL parsing ----

    @Test
    fun `YandexCredentialFetcher extracts link ID from full URL`() {
        val link = "https://telemost.yandex.ru/j/12345678901234567890"
        val id = YandexCredentialFetcher.extractLinkId(link)
        assertEquals("12345678901234567890", id)
    }

    @Test
    fun `YandexCredentialFetcher extracts link ID from partial`() {
        val id = YandexCredentialFetcher.extractLinkId("12345678901234567890")
        assertEquals("12345678901234567890", id)
    }

    // ---- Yandex JSON response parsing ----

    @Test
    fun `YandexCredentialFetcher parseConferenceResponse extracts fields`() {
        val json = """
        {
            "uri": "https://example.com",
            "room_id": "room123",
            "peer_id": "peer456",
            "client_configuration": {
                "media_server_url": "wss://media.yandex.ru/ws"
            },
            "credentials": "cred789"
        }
        """.trimIndent()
        val data = YandexCredentialFetcher.parseConferenceResponse(json)
        assertNotNull(data)
        assertEquals("room123", data!!.roomId)
        assertEquals("peer456", data.peerId)
        assertEquals("wss://media.yandex.ru/ws", data.wssUrl)
        assertEquals("cred789", data.credentials)
    }

    @Test
    fun `YandexCredentialFetcher parseTurnFromServerHello extracts TURN creds`() {
        val json = """
        {
            "uid": "abc",
            "serverHello": {
                "rtcConfiguration": {
                    "iceServers": [
                        {"urls": ["stun:stun.example.com:3478"]},
                        {
                            "urls": ["turn:relay.yandex.ru:3478?transport=udp"],
                            "username": "yuser",
                            "credential": "ypass"
                        }
                    ]
                }
            }
        }
        """.trimIndent()
        val creds = YandexCredentialFetcher.parseTurnFromServerHello(json)
        assertNotNull(creds)
        assertEquals("yuser", creds!!.username)
        assertEquals("ypass", creds.password)
        assertEquals("relay.yandex.ru:3478", creds.serverAddress)
    }

    @Test
    fun `YandexCredentialFetcher parseTurnFromServerHello skips TCP transport`() {
        val json = """
        {
            "uid": "abc",
            "serverHello": {
                "rtcConfiguration": {
                    "iceServers": [
                        {
                            "urls": ["turn:relay.yandex.ru:443?transport=tcp"],
                            "username": "yuser",
                            "credential": "ypass"
                        }
                    ]
                }
            }
        }
        """.trimIndent()
        val creds = YandexCredentialFetcher.parseTurnFromServerHello(json)
        assertNull(creds)
    }

    @Test
    fun `YandexCredentialFetcher parseTurnFromServerHello returns null when no TURN`() {
        val json = """
        {
            "uid": "abc",
            "serverHello": {
                "rtcConfiguration": {
                    "iceServers": [
                        {"urls": ["stun:stun.example.com:3478"]}
                    ]
                }
            }
        }
        """.trimIndent()
        val creds = YandexCredentialFetcher.parseTurnFromServerHello(json)
        assertNull(creds)
    }

    // ---- CredentialFetcher interface ----

    @Test
    fun `CredentialFetcherFactory returns VK fetcher for VK link`() {
        val fetcher = CredentialFetcherFactory.create("https://vk.com/call/join/abc123")
        assertTrue(fetcher is VkCredentialFetcher)
    }

    @Test
    fun `CredentialFetcherFactory returns Yandex fetcher for Yandex link`() {
        val fetcher = CredentialFetcherFactory.create("https://telemost.yandex.ru/j/123456")
        assertTrue(fetcher is YandexCredentialFetcher)
    }

    @Test
    fun `CredentialFetcherFactory returns null for unknown link`() {
        val fetcher = CredentialFetcherFactory.create("https://example.com/something")
        assertNull(fetcher)
    }
}
