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
        val txId = StunMessage.generateTransactionId()
        val req = StunMessage(StunMessage.CREATE_PERMISSION_REQUEST, txId)
        req.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peerAddress))

        val encoded = if (realm != null && nonce != null) {
            val key = StunMessage.computeLongTermKey(username, realm!!, password)
            req.encodeWithIntegrity(key, realm!!, username, nonce)
        } else {
            req.encode()
        }

        val resp = sendRawAndWait(encoded, txId) ?: return false
        return resp.type == StunMessage.CREATE_PERMISSION_SUCCESS
    }

    // ---- ChannelBind ----

    fun channelBind(peerAddress: InetSocketAddress): Int {
        val channel = nextChannel.getAndIncrement()
        val txId = StunMessage.generateTransactionId()
        val req = StunMessage(StunMessage.CHANNEL_BIND_REQUEST, txId)
        req.setChannelNumber(channel)
        req.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, buildXorPeerAddress(peerAddress))

        val encoded = if (realm != null && nonce != null) {
            val key = StunMessage.computeLongTermKey(username, realm!!, password)
            req.encodeWithIntegrity(key, realm!!, username, nonce)
        } else {
            req.encode()
        }

        val resp = sendRawAndWait(encoded, txId)
        return if (resp?.type == StunMessage.CHANNEL_BIND_SUCCESS) channel else -1
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
            // TCP: prepend 2-byte length for STUN framing
            val tcpOut = tcpSocket?.getOutputStream() ?: return
            synchronized(tcpOut) {
                val buf = ByteBuffer.allocate(2 + data.size)
                buf.putShort(data.size.toShort())
                buf.put(data)
                tcpOut.write(buf.array())
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
                    // Read 2-byte length prefix
                    val lenBuf = ByteArray(2)
                    if (tcpIn.readFully(lenBuf) < 2) break
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    data = ByteArray(len)
                    if (tcpIn.readFully(data) < len) break
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

/**
 * InputStream/OutputStream wrapper over ChannelData.
 * Allows proxying TCP streams through a TURN relay channel.
 */
class RelayStream(
    private val client: TurnClient,
    private val channel: Int
) {
    val inputStream: InputStream = RelayInputStream()
    val outputStream: OutputStream = RelayOutputStream()

    private inner class RelayInputStream : InputStream() {
        private var buffer: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            if (buffer == null || offset >= buffer!!.size) {
                val cd = client.receiveChannelData(30000) ?: return -1
                buffer = cd.data
                offset = 0
            }
            return buffer!![offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (buffer == null || offset >= buffer!!.size) {
                val cd = client.receiveChannelData(30000) ?: return -1
                buffer = cd.data
                offset = 0
            }
            val available = buffer!!.size - offset
            val toCopy = minOf(len, available)
            System.arraycopy(buffer!!, offset, b, off, toCopy)
            offset += toCopy
            return toCopy
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
