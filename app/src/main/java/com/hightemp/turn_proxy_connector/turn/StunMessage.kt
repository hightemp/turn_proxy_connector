package com.hightemp.turn_proxy_connector.turn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * STUN/TURN message encoder/decoder per RFC 5389 / RFC 5766.
 *
 * STUN Header (20 bytes):
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     STUN Message Type     |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                                                               |
 * |                     Transaction ID (96 bits)                  |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class StunMessage(
    val type: Int,
    val transactionId: ByteArray
) {
    private val attributes = mutableListOf<Pair<Int, ByteArray>>()

    fun addAttribute(attrType: Int, value: ByteArray) {
        attributes.add(attrType to value)
    }

    fun getAttribute(attrType: Int): ByteArray? =
        attributes.firstOrNull { it.first == attrType }?.second

    fun getAllAttributes(attrType: Int): List<ByteArray> =
        attributes.filter { it.first == attrType }.map { it.second }

    // ---- Convenience setters/getters ----

    fun setLifetime(seconds: Int) {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(seconds)
        addAttribute(ATTR_LIFETIME, buf.array())
    }

    fun getLifetime(): Int {
        val value = getAttribute(ATTR_LIFETIME) ?: return 0
        return ByteBuffer.wrap(value).int
    }

    fun setChannelNumber(channel: Int) {
        val buf = ByteBuffer.allocate(4)
        buf.putShort(channel.toShort())
        buf.putShort(0) // RFFU
        addAttribute(ATTR_CHANNEL_NUMBER, buf.array())
    }

    fun getChannelNumber(): Int {
        val value = getAttribute(ATTR_CHANNEL_NUMBER) ?: return 0
        return ByteBuffer.wrap(value).short.toInt() and 0xFFFF
    }

    fun getXorMappedAddress(): InetSocketAddress? =
        decodeXorAddress(getAttribute(ATTR_XOR_MAPPED_ADDRESS))

    fun getXorRelayedAddress(): InetSocketAddress? =
        decodeXorAddress(getAttribute(ATTR_XOR_RELAYED_ADDRESS))

    fun getErrorCode(): Int {
        val value = getAttribute(ATTR_ERROR_CODE) ?: return 0
        if (value.size < 4) return 0
        val clazz = value[2].toInt() and 0x07
        val number = value[3].toInt() and 0xFF
        return clazz * 100 + number
    }

    // ---- Encoding ----

    fun encode(): ByteArray {
        val body = encodeAttributes()
        val buf = ByteBuffer.allocate(HEADER_SIZE + body.size)
        buf.putShort(type.toShort())
        buf.putShort(body.size.toShort())
        buf.putInt(MAGIC_COOKIE)
        buf.put(transactionId)
        buf.put(body)
        return buf.array()
    }

    /**
     * Encode with MESSAGE-INTEGRITY (long-term credentials).
     * Adds USERNAME, REALM, NONCE (if provided), then MESSAGE-INTEGRITY.
     */
    fun encodeWithIntegrity(
        key: ByteArray,
        realm: String,
        username: String,
        nonce: String? = null
    ): ByteArray {
        // Add auth attributes if not already present
        if (getAttribute(ATTR_USERNAME) == null) {
            addAttribute(ATTR_USERNAME, username.toByteArray())
        }
        if (getAttribute(ATTR_REALM) == null) {
            addAttribute(ATTR_REALM, realm.toByteArray())
        }
        if (nonce != null && getAttribute(ATTR_NONCE) == null) {
            addAttribute(ATTR_NONCE, nonce.toByteArray())
        }

        // Encode everything so far
        val body = encodeAttributes()
        // MESSAGE-INTEGRITY adds 24 bytes (4 header + 20 HMAC-SHA1)
        val totalBodyLength = body.size + 24

        val headerBuf = ByteBuffer.allocate(HEADER_SIZE)
        headerBuf.putShort(type.toShort())
        headerBuf.putShort(totalBodyLength.toShort())
        headerBuf.putInt(MAGIC_COOKIE)
        headerBuf.put(transactionId)

        val toSign = ByteArray(HEADER_SIZE + body.size)
        System.arraycopy(headerBuf.array(), 0, toSign, 0, HEADER_SIZE)
        System.arraycopy(body, 0, toSign, HEADER_SIZE, body.size)

        val hmac = Mac.getInstance("HmacSHA1")
        hmac.init(SecretKeySpec(key, "HmacSHA1"))
        val integrity = hmac.doFinal(toSign)

        addAttribute(ATTR_MESSAGE_INTEGRITY, integrity)

        val finalBody = encodeAttributes()
        val result = ByteBuffer.allocate(HEADER_SIZE + finalBody.size)
        result.putShort(type.toShort())
        result.putShort(finalBody.size.toShort())
        result.putInt(MAGIC_COOKIE)
        result.put(transactionId)
        result.put(finalBody)
        return result.array()
    }

    private fun encodeAttributes(): ByteArray {
        val buf = ByteBuffer.allocate(attributes.sumOf { 4 + padTo4(it.second.size) })
        for ((attrType, value) in attributes) {
            buf.putShort(attrType.toShort())
            buf.putShort(value.size.toShort())
            buf.put(value)
            // Padding
            val padding = padTo4(value.size) - value.size
            for (i in 0 until padding) buf.put(0)
        }
        return buf.array()
    }

    // ---- XOR Address ----

    private fun decodeXorAddress(value: ByteArray?): InetSocketAddress? {
        if (value == null || value.size < 8) return null
        val family = value[1].toInt() and 0xFF
        val xorPort = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        val port = xorPort xor (MAGIC_COOKIE ushr 16)

        return when (family) {
            0x01 -> { // IPv4
                val magicBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
                val ip = ByteArray(4) { i -> (value[4 + i].toInt() xor magicBytes[i].toInt()).toByte() }
                InetSocketAddress(InetAddress.getByAddress(ip), port)
            }
            0x02 -> { // IPv6
                if (value.size < 20) return null
                val magicAndTxId = ByteBuffer.allocate(16)
                    .putInt(MAGIC_COOKIE)
                    .put(transactionId)
                    .array()
                val ip = ByteArray(16) { i -> (value[4 + i].toInt() xor magicAndTxId[i].toInt()).toByte() }
                InetSocketAddress(InetAddress.getByAddress(ip), port)
            }
            else -> null
        }
    }

    companion object {
        const val HEADER_SIZE = 20
        const val MAGIC_COOKIE = 0x2112A442.toInt()

        // Message types
        const val BINDING_REQUEST = 0x0001
        const val BINDING_SUCCESS = 0x0101
        const val ALLOCATE_REQUEST = 0x0003
        const val ALLOCATE_SUCCESS = 0x0103
        const val ALLOCATE_ERROR = 0x0113
        const val REFRESH_REQUEST = 0x0004
        const val REFRESH_SUCCESS = 0x0104
        const val REFRESH_ERROR = 0x0114
        const val CREATE_PERMISSION_REQUEST = 0x0008
        const val CREATE_PERMISSION_SUCCESS = 0x0108
        const val CREATE_PERMISSION_ERROR = 0x0118
        const val CHANNEL_BIND_REQUEST = 0x0009
        const val CHANNEL_BIND_SUCCESS = 0x0109
        const val CHANNEL_BIND_ERROR = 0x0119
        const val SEND_INDICATION = 0x0016
        const val DATA_INDICATION = 0x0017

        // Attribute types
        const val ATTR_MAPPED_ADDRESS = 0x0001
        const val ATTR_USERNAME = 0x0006
        const val ATTR_MESSAGE_INTEGRITY = 0x0008
        const val ATTR_ERROR_CODE = 0x0009
        const val ATTR_CHANNEL_NUMBER = 0x000C
        const val ATTR_LIFETIME = 0x000D
        const val ATTR_XOR_PEER_ADDRESS = 0x0012
        const val ATTR_DATA = 0x0013
        const val ATTR_REALM = 0x0014
        const val ATTR_NONCE = 0x0015
        const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
        const val ATTR_REQUESTED_ADDRESS_FAMILY = 0x0017
        const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        const val ATTR_REQUESTED_TRANSPORT = 0x0019
        const val ATTR_SOFTWARE = 0x8022

        fun generateTransactionId(): ByteArray {
            val id = ByteArray(12)
            SecureRandom().nextBytes(id)
            return id
        }

        fun decode(data: ByteArray): StunMessage {
            require(data.size >= HEADER_SIZE) { "Message too short" }
            val buf = ByteBuffer.wrap(data)
            val type = buf.short.toInt() and 0xFFFF
            val length = buf.short.toInt() and 0xFFFF
            val magic = buf.int
            require(magic == MAGIC_COOKIE) { "Invalid magic cookie" }
            val txId = ByteArray(12)
            buf.get(txId)

            val msg = StunMessage(type, txId)

            var remaining = length
            while (remaining >= 4 && buf.remaining() >= 4) {
                val attrType = buf.short.toInt() and 0xFFFF
                val attrLen = buf.short.toInt() and 0xFFFF
                remaining -= 4

                if (attrLen > buf.remaining()) break

                val value = ByteArray(attrLen)
                buf.get(value)
                remaining -= attrLen

                // Skip padding
                val padded = padTo4(attrLen)
                val padding = padded - attrLen
                if (padding > 0 && buf.remaining() >= padding) {
                    buf.position(buf.position() + padding)
                    remaining -= padding
                }

                msg.addAttribute(attrType, value)
            }

            return msg
        }

        /**
         * Compute long-term credential key: MD5(username:realm:password)
         */
        fun computeLongTermKey(username: String, realm: String, password: String): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            return md5.digest("$username:$realm:$password".toByteArray())
        }

        private fun padTo4(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)
    }
}

/**
 * XOR-PEER-ADDRESS builder utility.
 */
fun buildXorPeerAddress(address: InetSocketAddress): ByteArray {
    val ip = address.address.address
    val port = address.port
    val xorPort = port xor (StunMessage.MAGIC_COOKIE ushr 16)
    val magicBytes = ByteBuffer.allocate(4).putInt(StunMessage.MAGIC_COOKIE).array()

    return when (ip.size) {
        4 -> {
            val buf = ByteBuffer.allocate(8)
            buf.put(0) // reserved
            buf.put(1) // IPv4
            buf.putShort(xorPort.toShort())
            for (i in 0 until 4) {
                buf.put((ip[i].toInt() xor magicBytes[i].toInt()).toByte())
            }
            buf.array()
        }
        else -> throw UnsupportedOperationException("IPv6 XOR-PEER-ADDRESS not implemented")
    }
}

private fun padTo4(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)
