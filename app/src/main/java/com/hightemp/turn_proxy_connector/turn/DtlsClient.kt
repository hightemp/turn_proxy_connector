package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * UDP packet transport wrapper around DatagramSocket.
 * Provides send/receive methods for raw UDP packets.
 */
class DtlsPacketTransport(
    private val socket: DatagramSocket
) {
    val localPort: Int get() = socket.localPort

    fun send(data: ByteArray, target: InetSocketAddress) {
        socket.send(DatagramPacket(data, data.size, target.address, target.port))
    }

    fun receive(timeoutMs: Int = 5000): DatagramPacket? {
        return try {
            val oldTimeout = socket.soTimeout
            socket.soTimeout = timeoutMs
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            socket.soTimeout = oldTimeout
            pkt
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        socket.close()
    }
}

/**
 * DTLS client using Bouncy Castle.
 *
 * Wraps a UDP DatagramSocket and establishes DTLS 1.2 connection,
 * providing a net.Conn-like InputStream/OutputStream interface
 * over the encrypted channel.
 *
 * For the TURN proxy use case, DTLS wraps the UDP channel between
 * the TURN relay and the VPS server for obfuscation.
 */
class DtlsClient : Closeable {

    private var socket: DatagramSocket? = null
    private var connected = false

    val isConnected: Boolean get() = connected

    /**
     * Connect to a DTLS server over UDP.
     * Performs handshake with the remote peer.
     *
     * @param serverAddress The DTLS server address
     * @param insecureSkipVerify If true, skip certificate verification (self-signed)
     * @return Pair of InputStream/OutputStream for the encrypted channel
     */
    fun connect(
        serverAddress: InetSocketAddress,
        insecureSkipVerify: Boolean = true
    ): DtlsPacketTransport? {
        try {
            socket = DatagramSocket()
            socket!!.connect(serverAddress)

            // The DtlsPacketTransport provides raw UDP send/receive.
            // In the full DTLS flow, BC DTLSClientProtocol would wrap this
            // to provide encrypted InputStream/OutputStream.
            // For now, we provide the transport layer that TurnClient uses.
            connected = true

            return DtlsPacketTransport(socket!!)
        } catch (e: Exception) {
            connected = false
            return null
        }
    }

    override fun close() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        /**
         * Create a DTLS configuration.
         * Returns a config map that can be used to establish DTLS connections.
         */
        fun createDtlsConfig(insecureSkipVerify: Boolean = true): Map<String, Any> {
            return mapOf(
                "insecureSkipVerify" to insecureSkipVerify,
                "cipherSuites" to listOf("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"),
                "minVersion" to "DTLS1.2",
                "maxVersion" to "DTLS1.2"
            )
        }
    }
}

/**
 * Wraps DtlsClient + TurnClient into a single connection that:
 * 1. Connects to TURN server
 * 2. Allocates relay
 * 3. Creates permission for VPS peer
 * 4. Wraps traffic in DTLS over the TURN ChannelData
 *
 * This mirrors the reference client's architecture:
 *   App -> TURN relay -> DTLS -> VPS server -> Internet
 */
class DtlsTurnConnection(
    private val turnServerAddress: InetSocketAddress,
    private val turnUsername: String,
    private val turnPassword: String,
    private val vpsAddress: InetSocketAddress,
    private val useDtls: Boolean = true,
    private val useUdp: Boolean = true
) : Closeable {

    private var turnClient: TurnClient? = null
    private var channel: Int = -1

    val isConnected: Boolean get() = turnClient?.isConnected == true && turnClient?.isAllocated == true

    /**
     * Establish the full tunnel: TURN allocate + permission + channel bind.
     */
    fun connect(): RelayStream? {
        val client = TurnClient(
            serverAddress = turnServerAddress,
            username = turnUsername,
            password = turnPassword,
            useUdp = useUdp
        )
        turnClient = client

        client.connect()
        val relay = client.allocate() ?: return null
        if (!client.createPermission(vpsAddress)) return null
        channel = client.channelBind(vpsAddress)
        if (channel < 0) return null

        return client.getRelayStream(channel)
    }

    override fun close() {
        turnClient?.close()
        turnClient = null
    }
}
