package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TURN client implementing RFC 5766.
 *
 * Supports:
 * - Allocate (with long-term credential retry on 401)
 * - CreatePermission
 * - ChannelBind
 * - Send/Data indications
 * - ChannelData framing
 *
 * Transport: UDP (DatagramSocket) or TCP (Socket with STUN framing).
 */
class TurnClient(
    private val serverAddress: InetSocketAddress,
    private val username: String,
    private val password: String,
    private val useUdp: Boolean = true,
    private val requestedFamily: Int = 0x01 // IPv4
) : Closeable {

    companion object {
        private const val TAG = "TurnClient"
        private const val MAX_PACKET_SIZE = 4096
        private const val DEFAULT_TIMEOUT_MS = 5000
    }

    // UDP transport
    private var udpSocket: DatagramSocket? = null
    // TCP transport (STUN framing)
    private var tcpSocket: Socket? = null

    private val running = AtomicBoolean(false)
    private val allocated = AtomicBoolean(false)
    private val nextChannel = AtomicInteger(ChannelData.MIN_CHANNEL)

    // Pending STUN transactions
    private val pendingResponses = ConcurrentHashMap<String, LinkedBlockingQueue<StunMessage>>()
    // Incoming ChannelData
    private val channelDataQueue = LinkedBlockingQueue<ChannelData>()
    // Incoming Data indications
    private val dataIndicationQueue = LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>()

    private var receiverThread: Thread? = null

    // Auth state from 401 response
    private var realm: String? = null
    private var nonce: String? = null

    var relayAddress: InetSocketAddress? = null
        private set
    var mappedAddress: InetSocketAddress? = null
        private set

    val isConnected: Boolean get() = running.get()
    val isAllocated: Boolean get() = allocated.get()

    // ---- Connect / Close ----

    fun connect() {
        if (running.getAndSet(true)) return

        if (useUdp) {
            udpSocket = DatagramSocket().apply {
                soTimeout = DEFAULT_TIMEOUT_MS
                connect(serverAddress)
            }
        } else {
            tcpSocket = Socket().apply {
                connect(serverAddress, DEFAULT_TIMEOUT_MS)
                soTimeout = DEFAULT_TIMEOUT_MS
            }
        }

        receiverThread = Thread(::receiveLoop).apply {
            isDaemon = true
            name = "TurnClient-receiver"
            start()
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        allocated.set(false)
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        receiverThread?.interrupt()
        receiverThread = null
        pendingResponses.clear()
        channelDataQueue.clear()
        dataIndicationQueue.clear()
    }

    // ---- Allocate ----

    fun allocate(): InetSocketAddress? {
        // First attempt without credentials
        val txId1 = StunMessage.generateTransactionId()
        val req1 = StunMessage(StunMessage.ALLOCATE_REQUEST, txId1)
        req1.addAttribute(
            StunMessage.ATTR_REQUESTED_TRANSPORT,
            byteArrayOf(17, 0, 0, 0) // UDP = 17
        )
        if (requestedFamily != 0) {
            req1.addAttribute(
                StunMessage.ATTR_REQUESTED_ADDRESS_FAMILY,
                byteArrayOf(requestedFamily.toByte(), 0, 0, 0)
            )
        }

        val resp1 = sendAndWait(req1) ?: return null

        if (resp1.type == StunMessage.ALLOCATE_ERROR) {
            val errorCode = resp1.getErrorCode()
            if (errorCode == 401) {
                // Extract realm and nonce
                realm = resp1.getAttribute(StunMessage.ATTR_REALM)?.let { String(it) }
                nonce = resp1.getAttribute(StunMessage.ATTR_NONCE)?.let { String(it) }

                if (realm != null && nonce != null) {
                    return allocateWithCredentials()
                }
            }
            return null
        }

        return parseAllocateSuccess(resp1)
    }

    private fun allocateWithCredentials(): InetSocketAddress? {
        val txId = StunMessage.generateTransactionId()
        val req = StunMessage(StunMessage.ALLOCATE_REQUEST, txId)
        req.addAttribute(
            StunMessage.ATTR_REQUESTED_TRANSPORT,
            byteArrayOf(17, 0, 0, 0)
        )
        if (requestedFamily != 0) {
            req.addAttribute(
                StunMessage.ATTR_REQUESTED_ADDRESS_FAMILY,
                byteArrayOf(requestedFamily.toByte(), 0, 0, 0)
            )
        }

        val key = StunMessage.computeLongTermKey(username, realm!!, password)
        val encoded = req.encodeWithIntegrity(key, realm!!, username, nonce)
        val decoded = StunMessage.decode(encoded)

        val resp = sendRawAndWait(encoded, decoded.transactionId) ?: return null

        if (resp.type == StunMessage.ALLOCATE_ERROR) return null

        return parseAllocateSuccess(resp)
    }

    private fun parseAllocateSuccess(resp: StunMessage): InetSocketAddress? {
        relayAddress = resp.getXorRelayedAddress()
        mappedAddress = resp.getXorMappedAddress()
        if (relayAddress != null) {
            allocated.set(true)
        }
        return relayAddress
    }

    // ---- CreatePermission ----

    fun createPermission(peerAddress: InetSocketAddress): Boolean {
        return sendAuthenticatedRequest(StunMessage.CREATE_PERMISSION_REQUEST) { req ->
            req.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peerAddress))
        }?.type == StunMessage.CREATE_PERMISSION_SUCCESS
    }

    // ---- ChannelBind ----

    fun channelBind(peerAddress: InetSocketAddress): Int {
        val channel = nextChannel.getAndIncrement()
        val resp = sendAuthenticatedRequest(StunMessage.CHANNEL_BIND_REQUEST) { req ->
            req.setChannelNumber(channel)
            req.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peerAddress))
        }
        return if (resp?.type == StunMessage.CHANNEL_BIND_SUCCESS) channel else -1
    }

    /**
     * Refresh an existing ChannelBind to prevent expiry.
     * RFC 5766 §11.3: channel bindings last 10 minutes unless refreshed.
     * Must be called periodically (e.g., every 300s) with the same channel+peer.
     *
     * @return true if refresh succeeded
     */
    fun refreshChannelBind(channel: Int, peerAddress: InetSocketAddress): Boolean {
        val resp = sendAuthenticatedRequest(StunMessage.CHANNEL_BIND_REQUEST) { req ->
            req.setChannelNumber(channel)
            req.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peerAddress))
        }
        return resp?.type == StunMessage.CHANNEL_BIND_SUCCESS
    }

    // ---- Refresh ----

    /**
     * Send a Refresh request to extend the TURN allocation lifetime.
     * Should be called periodically (e.g., every 300s) before the allocation expires (~600s).
     *
     * @param lifetime Requested lifetime in seconds. 0 = deallocate.
     * @return New lifetime granted by server, or -1 on failure.
     */
    fun refresh(lifetime: Int = 600): Int {
        val resp = sendAuthenticatedRequest(StunMessage.REFRESH_REQUEST) { req ->
            req.setLifetime(lifetime)
        } ?: return -1
        return if (resp.type == StunMessage.REFRESH_SUCCESS) {
            resp.getLifetime()
        } else {
            -1
        }
    }

    // ---- Authenticated request with 438 Stale Nonce retry ----

    /**
     * Send an authenticated STUN request with automatic 438 Stale Nonce retry.
     * The [configure] lambda adds attributes specific to the request type.
     */
    private fun sendAuthenticatedRequest(
        requestType: Int,
        configure: (StunMessage) -> Unit
    ): StunMessage? {
        for (attempt in 0..1) {  // At most 1 retry for stale nonce
            val txId = StunMessage.generateTransactionId()
            val req = StunMessage(requestType, txId)
            configure(req)

            val encoded = if (realm != null && nonce != null) {
                val key = StunMessage.computeLongTermKey(username, realm!!, password)
                req.encodeWithIntegrity(key, realm!!, username, nonce)
            } else {
                req.encode()
            }

            val resp = sendRawAndWait(encoded, txId) ?: return null

            // Check for 438 Stale Nonce error
            val errorCode = resp.getErrorCode()
            if (errorCode == 438) {
                // Update nonce from the error response and retry
                val newNonce = resp.getAttribute(StunMessage.ATTR_NONCE)?.let { String(it) }
                val newRealm = resp.getAttribute(StunMessage.ATTR_REALM)?.let { String(it) }
                if (newNonce != null) nonce = newNonce
                if (newRealm != null) realm = newRealm
                continue  // Retry with new nonce
            }

            return resp
        }
        return null  // Both attempts failed
    }

    // ---- Data Send/Receive ----

    fun sendChannelData(channel: Int, data: ByteArray) {
        val frame = ChannelData.encode(channel, data)
        sendRaw(frame)
    }

    fun receiveChannelData(timeoutMs: Long = DEFAULT_TIMEOUT_MS.toLong()): ChannelData? {
        return channelDataQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun sendIndication(peer: InetSocketAddress, data: ByteArray) {
        val msg = StunMessage(StunMessage.SEND_INDICATION, StunMessage.generateTransactionId())
        msg.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peer))
        msg.addAttribute(StunMessage.ATTR_DATA, data)
        sendRaw(msg.encode())
    }

    fun receiveDataIndication(timeoutMs: Long = DEFAULT_TIMEOUT_MS.toLong()): Pair<InetSocketAddress, ByteArray>? {
        return dataIndicationQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ---- Relay Stream (InputStream/OutputStream over ChannelData) ----

    fun getRelayStream(channel: Int): RelayStream {
        return RelayStream(this, channel)
    }

    // ---- Transport Layer ----

    private fun sendRaw(data: ByteArray) {
        if (useUdp) {
            udpSocket?.send(DatagramPacket(data, data.size))
        } else {
            // TCP: STUN messages and ChannelData are self-framed per RFC 5766 §7.
            // STUN: 20-byte header with message length in bytes 2-3.
            // ChannelData: 4-byte header with channel (2 bytes) + length (2 bytes).
            // No extra length prefix needed.
            val tcpOut = tcpSocket?.getOutputStream() ?: return
            synchronized(tcpOut) {
                tcpOut.write(data)
                tcpOut.flush()
            }
        }
    }

    private fun sendAndWait(msg: StunMessage, timeoutMs: Long = DEFAULT_TIMEOUT_MS.toLong()): StunMessage? {
        val txKey = msg.transactionId.toHex()
        val queue = LinkedBlockingQueue<StunMessage>()
        pendingResponses[txKey] = queue
        try {
            sendRaw(msg.encode())
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingResponses.remove(txKey)
        }
    }

    private fun sendRawAndWait(data: ByteArray, txId: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS.toLong()): StunMessage? {
        val txKey = txId.toHex()
        val queue = LinkedBlockingQueue<StunMessage>()
        pendingResponses[txKey] = queue
        try {
            sendRaw(data)
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingResponses.remove(txKey)
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running.get()) {
            try {
                val data: ByteArray
                if (useUdp) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt) ?: break
                    data = buf.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                } else {
                    val tcpIn = tcpSocket?.getInputStream() ?: break
                    // TCP: Self-framing per RFC 5766 §7.
                    // Peek at first 2 bytes to determine message type:
                    // - STUN: top 2 bits are 0, length at bytes 2-3 (20-byte header + attributes)
                    // - ChannelData: top bit is 0, second bit is 1 (0x4000-0x7FFF), length at bytes 2-3
                    val headerBuf = ByteArray(4)
                    if (tcpIn.readFully(headerBuf) < 4) break
                    val firstTwo = ((headerBuf[0].toInt() and 0xFF) shl 8) or (headerBuf[1].toInt() and 0xFF)
                    val lengthField = ((headerBuf[2].toInt() and 0xFF) shl 8) or (headerBuf[3].toInt() and 0xFF)

                    if (firstTwo in ChannelData.MIN_CHANNEL..ChannelData.MAX_CHANNEL) {
                        // ChannelData: header (4 bytes already read) + data (lengthField) + padding to 4-byte boundary
                        val padded = (lengthField + 3) and 0x7FFFFFFC
                        val payload = ByteArray(padded)
                        if (padded > 0 && tcpIn.readFully(payload) < padded) break
                        data = ByteArray(4 + padded)
                        System.arraycopy(headerBuf, 0, data, 0, 4)
                        System.arraycopy(payload, 0, data, 4, padded)
                    } else {
                        // STUN message: 20-byte header (4 already read) + attributes (lengthField bytes)
                        val remaining = 16 + lengthField // 16 bytes of header left + attributes
                        data = ByteArray(4 + remaining)
                        System.arraycopy(headerBuf, 0, data, 0, 4)
                        if (remaining > 0 && tcpIn.readFully(data, 4, remaining) < remaining) break
                    }
                }

                dispatch(data)
            } catch (_: java.net.SocketTimeoutException) {
                // Normal timeout, continue loop
            } catch (_: Exception) {
                if (running.get()) continue else break
            }
        }
    }

    private fun dispatch(data: ByteArray) {
        if (data.size < 4) return

        if (ChannelData.isChannelData(data)) {
            val cd = ChannelData.decode(data)
            if (cd != null) {
                channelDataQueue.offer(cd)
            }
            return
        }

        // STUN message
        try {
            val msg = StunMessage.decode(data)
            when (msg.type) {
                StunMessage.DATA_INDICATION -> {
                    val peerData = msg.getAttribute(StunMessage.ATTR_DATA)
                    val xorPeer = msg.getAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS)
                    if (peerData != null) {
                        // Try to extract peer address (best effort)
                        val peerAddr = if (xorPeer != null) {
                            try {
                                decodeXorPeerAddress(xorPeer, msg.transactionId)
                            } catch (_: Exception) {
                                InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
                            }
                        } else {
                            InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
                        }
                        dataIndicationQueue.offer(peerAddr to peerData)
                    }
                }
                else -> {
                    // Match to pending transaction
                    val txKey = msg.transactionId.toHex()
                    pendingResponses[txKey]?.offer(msg)
                }
            }
        } catch (_: Exception) {
            // Not a valid STUN message, ignore
        }
    }

    private fun decodeXorPeerAddress(value: ByteArray, txId: ByteArray): InetSocketAddress {
        if (value.size < 8) throw IllegalArgumentException("XOR peer address too short")
        val family = value[1].toInt() and 0xFF
        val xorPort = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        val port = xorPort xor (StunMessage.MAGIC_COOKIE ushr 16)
        val magicBytes = ByteBuffer.allocate(4).putInt(StunMessage.MAGIC_COOKIE).array()
        val ip = ByteArray(4) { i -> (value[4 + i].toInt() xor magicBytes[i].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }
}

// ---- Extension helpers ----

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun InputStream.readFully(buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = read(buf, total, buf.size - total)
        if (n == -1) return total
        total += n
    }
    return total
}

private fun InputStream.readFully(buf: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = read(buf, off + total, len - total)
        if (n == -1) return total
        total += n
    }
    return total
}

/**
 * InputStream/OutputStream wrapper over ChannelData.
 * Allows proxying TCP streams through a TURN relay channel.
 */
class RelayStream(
    private val client: TurnClient,
    private val channel: Int,
    private val idleTimeoutMs: Long = 30 * 60 * 1000L
) {
    val inputStream: InputStream = RelayInputStream()
    val outputStream: OutputStream = RelayOutputStream()

    private inner class RelayInputStream : InputStream() {
        private var buffer: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            if (buffer == null || offset >= buffer!!.size) {
                val cd = receiveForChannelNonEmpty(idleTimeoutMs) ?: return -1
                buffer = cd.data
                offset = 0
            }
            return buffer!![offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (buffer == null || offset >= buffer!!.size) {
                val cd = receiveForChannelNonEmpty(idleTimeoutMs) ?: return -1
                buffer = cd.data
                offset = 0
            }
            val available = buffer!!.size - offset
            val toCopy = minOf(len, available)
            System.arraycopy(buffer!!, offset, b, off, toCopy)
            offset += toCopy
            return toCopy
        }

        /**
         * Receive ChannelData only for this stream's channel number.
         * Discards data for other channels and skips empty payloads.
         */
        private fun receiveForChannelNonEmpty(timeoutMs: Long): ChannelData? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                val cd = client.receiveChannelData(remaining) ?: return null
                if (cd.channelNumber == channel && cd.data.isNotEmpty()) return cd
                // Wrong channel or empty payload — discard and try again
            }
        }
    }

    private inner class RelayOutputStream : OutputStream() {
        override fun write(b: Int) {
            client.sendChannelData(channel, byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // TURN limits payload per ChannelData to ~1200 bytes for UDP
            val maxChunk = 1200
            var pos = off
            while (pos < off + len) {
                val chunkSize = minOf(maxChunk, off + len - pos)
                val chunk = b.copyOfRange(pos, pos + chunkSize)
                client.sendChannelData(channel, chunk)
                pos += chunkSize
            }
        }

        override fun flush() { /* no-op for UDP */ }
    }
}
