package com.hightemp.turn_proxy_connector.turn

import java.nio.ByteBuffer

/**
 * ChannelData message framing per RFC 5766 Section 11.4.
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         Channel Number        |            Length             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                                                               |
 * /                       Application Data                        /
 * /                                                               /
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Channel numbers 0x4000-0x7FFF are valid.
 */
data class ChannelData(
    val channelNumber: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelData) return false
        return channelNumber == other.channelNumber && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * channelNumber + data.contentHashCode()

    companion object {
        const val HEADER_SIZE = 4
        const val MIN_CHANNEL = 0x4000
        const val MAX_CHANNEL = 0x7FFF

        fun encode(channelNumber: Int, data: ByteArray): ByteArray {
            val padded = padTo4(data.size)
            val buf = ByteBuffer.allocate(HEADER_SIZE + padded)
            buf.putShort(channelNumber.toShort())
            buf.putShort(data.size.toShort())
            buf.put(data)
            // Pad remaining
            for (i in data.size until padded) buf.put(0)
            return buf.array()
        }

        fun decode(raw: ByteArray): ChannelData? {
            if (raw.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(raw)
            val channel = buf.short.toInt() and 0xFFFF
            if (channel < MIN_CHANNEL || channel > MAX_CHANNEL) return null
            val length = buf.short.toInt() and 0xFFFF
            if (raw.size < HEADER_SIZE + length) return null
            val data = ByteArray(length)
            buf.get(data)
            return ChannelData(channel, data)
        }

        /**
         * Check if bytes start with a valid ChannelData header.
         * Channel numbers in range 0x4000 - 0x7FFF indicate ChannelData.
         */
        fun isChannelData(data: ByteArray): Boolean {
            if (data.size < HEADER_SIZE) return false
            val firstByte = data[0].toInt() and 0xFF
            // 0x40-0x7F range for first byte
            return firstByte in 0x40..0x7F
        }

        private fun padTo4(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)
    }
}
