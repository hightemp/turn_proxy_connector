package com.hightemp.turn_proxy_connector.turn

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/**
 * TDD tests for STUN/TURN message encoding and decoding.
 * RFC 5389 - STUN, RFC 5766 - TURN
 */
class StunMessageTest {

    // ---- STUN Header ----

    @Test
    fun `encode binding request has correct header`() {
        val msg = StunMessage(
            type = StunMessage.BINDING_REQUEST,
            transactionId = ByteArray(12) { it.toByte() }
        )
        val encoded = msg.encode()

        // Header: 20 bytes
        assertTrue(encoded.size >= 20)
        // Type = 0x0001
        assertEquals(0x00.toByte(), encoded[0])
        assertEquals(0x01.toByte(), encoded[1])
        // Magic cookie = 0x2112A442
        assertEquals(0x21.toByte(), encoded[4])
        assertEquals(0x12.toByte(), encoded[5])
        assertEquals(0xA4.toByte(), encoded[6])
        assertEquals(0x42.toByte(), encoded[7])
        // Transaction ID starts at offset 8
        for (i in 0 until 12) {
            assertEquals(i.toByte(), encoded[8 + i])
        }
    }

    @Test
    fun `message length in header excludes the 20-byte header`() {
        val msg = StunMessage(
            type = StunMessage.BINDING_REQUEST,
            transactionId = ByteArray(12)
        )
        val encoded = msg.encode()
        val length = ((encoded[2].toInt() and 0xFF) shl 8) or (encoded[3].toInt() and 0xFF)
        assertEquals(encoded.size - 20, length)
    }

    @Test
    fun `decode encoded message round-trip`() {
        val txId = ByteArray(12) { (it * 7).toByte() }
        val original = StunMessage(
            type = StunMessage.ALLOCATE_REQUEST,
            transactionId = txId
        )
        original.addAttribute(StunMessage.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) // UDP=17

        val encoded = original.encode()
        val decoded = StunMessage.decode(encoded)

        assertEquals(original.type, decoded.type)
        assertArrayEquals(original.transactionId, decoded.transactionId)
        assertNotNull(decoded.getAttribute(StunMessage.ATTR_REQUESTED_TRANSPORT))
    }

    // ---- Attributes ----

    @Test
    fun `encode USERNAME attribute`() {
        val msg = StunMessage(
            type = StunMessage.ALLOCATE_REQUEST,
            transactionId = ByteArray(12)
        )
        msg.addAttribute(StunMessage.ATTR_USERNAME, "testuser".toByteArray())

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)
        val username = decoded.getAttribute(StunMessage.ATTR_USERNAME)

        assertNotNull(username)
        assertEquals("testuser", String(username!!))
    }

    @Test
    fun `attribute padding to 4-byte boundary`() {
        val msg = StunMessage(
            type = StunMessage.BINDING_REQUEST,
            transactionId = ByteArray(12)
        )
        // 5 bytes value -> should be padded to 8 in wire format
        msg.addAttribute(StunMessage.ATTR_USERNAME, "hello".toByteArray())

        val encoded = msg.encode()
        // Header(20) + Attr header(4) + value padded(8) = 32
        assertEquals(32, encoded.size)

        val decoded = StunMessage.decode(encoded)
        assertEquals("hello", String(decoded.getAttribute(StunMessage.ATTR_USERNAME)!!))
    }

    @Test
    fun `multiple attributes encoded and decoded`() {
        val msg = StunMessage(
            type = StunMessage.ALLOCATE_REQUEST,
            transactionId = ByteArray(12)
        )
        msg.addAttribute(StunMessage.ATTR_USERNAME, "user".toByteArray())
        msg.addAttribute(StunMessage.ATTR_REALM, "realm.example.com".toByteArray())
        msg.addAttribute(StunMessage.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)

        assertEquals("user", String(decoded.getAttribute(StunMessage.ATTR_USERNAME)!!))
        assertEquals("realm.example.com", String(decoded.getAttribute(StunMessage.ATTR_REALM)!!))
        assertArrayEquals(byteArrayOf(17, 0, 0, 0), decoded.getAttribute(StunMessage.ATTR_REQUESTED_TRANSPORT))
    }

    // ---- Message types ----

    @Test
    fun `allocate request type constant`() {
        assertEquals(0x0003, StunMessage.ALLOCATE_REQUEST)
    }

    @Test
    fun `allocate success response type`() {
        assertEquals(0x0103, StunMessage.ALLOCATE_SUCCESS)
    }

    @Test
    fun `allocate error response type`() {
        assertEquals(0x0113, StunMessage.ALLOCATE_ERROR)
    }

    @Test
    fun `create permission request type`() {
        assertEquals(0x0008, StunMessage.CREATE_PERMISSION_REQUEST)
    }

    @Test
    fun `channel bind request type`() {
        assertEquals(0x0009, StunMessage.CHANNEL_BIND_REQUEST)
    }

    @Test
    fun `send indication type`() {
        assertEquals(0x0016, StunMessage.SEND_INDICATION)
    }

    @Test
    fun `data indication type`() {
        assertEquals(0x0017, StunMessage.DATA_INDICATION)
    }

    @Test
    fun `refresh request type`() {
        assertEquals(0x0004, StunMessage.REFRESH_REQUEST)
    }

    // ---- XOR-MAPPED-ADDRESS ----

    @Test
    fun `decode XOR-MAPPED-ADDRESS IPv4`() {
        val txId = ByteArray(12)
        // XOR-MAPPED-ADDRESS for 192.168.1.100:12345
        // Port: 12345 = 0x3039, XOR 0x2112 = 0x112B
        // Address: C0.A8.01.64 XOR 21.12.A4.42 = E1.BA.A5.26
        val xorAddr = byteArrayOf(
            0x00, 0x01,                         // reserved + family
            0x11.toByte(), 0x2B.toByte(),       // XOR port
            0xE1.toByte(), 0xBA.toByte(), 0xA5.toByte(), 0x26.toByte() // XOR address
        )

        val msg = StunMessage(type = StunMessage.ALLOCATE_SUCCESS, transactionId = txId)
        msg.addAttribute(StunMessage.ATTR_XOR_MAPPED_ADDRESS, xorAddr)

        val addr = msg.getXorMappedAddress()
        assertNotNull(addr)
        assertEquals(12345, addr!!.port)
        assertEquals(InetAddress.getByName("192.168.1.100"), addr.address)
    }

    @Test
    fun `decode XOR-RELAYED-ADDRESS`() {
        val txId = ByteArray(12)
        // relay address: 10.0.0.1:50000
        // Port: 50000 = 0xC350, XOR 0x2112 = 0xE242
        // Addr: 0A.00.00.01 XOR 21.12.A4.42 = 2B.12.A4.43
        val xorAddr = byteArrayOf(
            0x00, 0x01,
            0xE2.toByte(), 0x42.toByte(),
            0x2B.toByte(), 0x12.toByte(), 0xA4.toByte(), 0x43.toByte()
        )

        val msg = StunMessage(type = StunMessage.ALLOCATE_SUCCESS, transactionId = txId)
        msg.addAttribute(StunMessage.ATTR_XOR_RELAYED_ADDRESS, xorAddr)

        val addr = msg.getXorRelayedAddress()
        assertNotNull(addr)
        assertEquals(50000, addr!!.port)
        assertEquals(InetAddress.getByName("10.0.0.1"), addr.address)
    }

    // ---- Error code ----

    @Test
    fun `decode ERROR-CODE attribute`() {
        // Error code 401 Unauthorized
        // class=4, number=1
        val errorValue = byteArrayOf(
            0x00, 0x00,       // reserved
            0x04, 0x01,       // class=4, number=1 -> 401
            // "Unauthorized"
            0x55, 0x6E, 0x61, 0x75, 0x74, 0x68, 0x6F, 0x72, 0x69, 0x7A, 0x65, 0x64
        )

        val msg = StunMessage(type = StunMessage.ALLOCATE_ERROR, transactionId = ByteArray(12))
        msg.addAttribute(StunMessage.ATTR_ERROR_CODE, errorValue)

        val errorCode = msg.getErrorCode()
        assertEquals(401, errorCode)
    }

    // ---- MESSAGE-INTEGRITY ----

    @Test
    fun `compute MESSAGE-INTEGRITY HMAC-SHA1`() {
        val msg = StunMessage(
            type = StunMessage.ALLOCATE_REQUEST,
            transactionId = ByteArray(12) { 0xAA.toByte() }
        )
        msg.addAttribute(StunMessage.ATTR_USERNAME, "user".toByteArray())

        val key = StunMessage.computeLongTermKey("user", "realm", "pass")
        val encoded = msg.encodeWithIntegrity(key, "realm", "user")

        // Should have MESSAGE-INTEGRITY attribute
        val decoded = StunMessage.decode(encoded)
        assertNotNull(decoded.getAttribute(StunMessage.ATTR_MESSAGE_INTEGRITY))
    }

    // ---- NONCE / REALM ----

    @Test
    fun `nonce and realm stored and retrieved`() {
        val msg = StunMessage(type = StunMessage.ALLOCATE_ERROR, transactionId = ByteArray(12))
        msg.addAttribute(StunMessage.ATTR_NONCE, "abc123nonce".toByteArray())
        msg.addAttribute(StunMessage.ATTR_REALM, "example.com".toByteArray())

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)

        assertEquals("abc123nonce", String(decoded.getAttribute(StunMessage.ATTR_NONCE)!!))
        assertEquals("example.com", String(decoded.getAttribute(StunMessage.ATTR_REALM)!!))
    }

    // ---- LIFETIME ----

    @Test
    fun `encode and decode LIFETIME attribute`() {
        val msg = StunMessage(type = StunMessage.ALLOCATE_SUCCESS, transactionId = ByteArray(12))
        msg.setLifetime(600)

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)
        assertEquals(600, decoded.getLifetime())
    }

    // ---- CHANNEL-NUMBER ----

    @Test
    fun `encode CHANNEL-NUMBER attribute`() {
        val msg = StunMessage(type = StunMessage.CHANNEL_BIND_REQUEST, transactionId = ByteArray(12))
        msg.setChannelNumber(0x4000)

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)
        assertEquals(0x4000, decoded.getChannelNumber())
    }

    // ---- ChannelData framing ----

    @Test
    fun `encode ChannelData message`() {
        val data = "Hello, World!".toByteArray()
        val channelData = ChannelData.encode(0x4000, data)

        // Header: 4 bytes (channel number 2 + length 2) + data padded to 4
        assertEquals(0x40.toByte(), channelData[0])
        assertEquals(0x00.toByte(), channelData[1])
        // Length = 13
        assertEquals(0x00.toByte(), channelData[2])
        assertEquals(0x0D.toByte(), channelData[3])
        // Data
        assertEquals('H'.code.toByte(), channelData[4])
    }

    @Test
    fun `decode ChannelData message`() {
        val original = "Test data".toByteArray()
        val encoded = ChannelData.encode(0x4001, original)

        val decoded = ChannelData.decode(encoded)
        assertNotNull(decoded)
        assertEquals(0x4001, decoded!!.channelNumber)
        assertArrayEquals(original, decoded.data)
    }

    @Test
    fun `isChannelData returns true for channel numbers 0x4000-0x7FFF`() {
        assertTrue(ChannelData.isChannelData(byteArrayOf(0x40, 0x00, 0x00, 0x04)))
        assertTrue(ChannelData.isChannelData(byteArrayOf(0x7F, 0xFF.toByte(), 0x00, 0x04)))
        assertFalse(ChannelData.isChannelData(byteArrayOf(0x00, 0x01, 0x00, 0x00)))  // STUN
        assertFalse(ChannelData.isChannelData(byteArrayOf(0x3F, 0xFF.toByte(), 0x00, 0x04)))
    }

    // ---- Transaction ID generation ----

    @Test
    fun `generateTransactionId returns 12 bytes`() {
        val txId = StunMessage.generateTransactionId()
        assertEquals(12, txId.size)
    }

    @Test
    fun `two transaction IDs are different`() {
        val id1 = StunMessage.generateTransactionId()
        val id2 = StunMessage.generateTransactionId()
        assertFalse(id1.contentEquals(id2))
    }
}
