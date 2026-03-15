package com.hightemp.turn_proxy_connector.turn

import org.bouncycastle.tls.DatagramTransport
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Bouncy Castle DatagramTransport implementation that sends/receives
 * datagrams through a TURN relay ChannelData binding.
 *
 * This is the bridge between BC's DTLS layer and the TURN tunnel:
 * - send() → TurnClient.sendChannelData() → TURN relay → VPS
 * - receive() → TurnClient.receiveChannelData() ← TURN relay ← VPS
 *
 * The DTLS layer sits on top of this, adding encryption.
 * Data flow: HTTP bytes → DTLS encrypt → TurnDatagramTransport → ChannelData → TURN → VPS
 */
class TurnDatagramTransport(
    private val turnClient: TurnClient,
    private val channel: Int,
    private val mtu: Int = 1200
) : DatagramTransport {

    @Volatile
    private var closed = false

    override fun getReceiveLimit(): Int = mtu

    /**
     * Send limit accounts for DTLS record overhead (~80 bytes for GCM).
     * BC uses this to fragment plaintext before encryption.
     */
    override fun getSendLimit(): Int = mtu - DTLS_OVERHEAD

    /**
     * Receive a datagram from the TURN relay (blocking up to waitMillis).
     * Per BC DatagramTransport contract: returns bytes read (>= 0), or throws IOException.
     * Only accepts ChannelData matching our channel number.
     */
    @Throws(IOException::class)
    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        if (closed) throw IOException("Transport closed")

        val deadline = System.currentTimeMillis() + waitMillis
        while (!closed) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("Receive timed out")

            val cd = turnClient.receiveChannelData(remaining)
            if (cd == null) {
                if (closed) throw IOException("Transport closed")
                throw SocketTimeoutException("Receive timed out")
            }
            // Filter by channel number
            if (cd.channelNumber != channel) continue
            // Skip empty datagrams
            if (cd.data.isEmpty()) continue

            val toCopy = minOf(len, cd.data.size)
            System.arraycopy(cd.data, 0, buf, off, toCopy)
            return toCopy
        }
        throw IOException("Transport closed")
    }

    /**
     * Send a datagram through the TURN relay ChannelData binding.
     */
    @Throws(IOException::class)
    override fun send(buf: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Transport closed")
        val data = if (off == 0 && len == buf.size) buf else buf.copyOfRange(off, off + len)
        turnClient.sendChannelData(channel, data)
    }

    override fun close() {
        closed = true
    }

    companion object {
        /** Approximate DTLS 1.2 GCM record overhead (header + IV + tag) */
        private const val DTLS_OVERHEAD = 80
    }
}
