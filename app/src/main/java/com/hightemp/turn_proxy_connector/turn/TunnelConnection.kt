package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abstraction for a tunnel connection to the VPS relay server.
 * Implementations can use plain TCP, TURN relay, or TURN+DTLS.
 */
interface TunnelConnection {

    /**
     * Establish the tunnel and return a stream pair for I/O.
     * Returns null if connection fails.
     */
    fun connect(): StreamPair?

    /**
     * Wrapper around InputStream + OutputStream with Closeable.
     */
    class StreamPair(
        val inputStream: InputStream,
        val outputStream: OutputStream,
        private val onClose: () -> Unit = {}
    ) : Closeable {
        override fun close() {
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            onClose()
        }
    }
}

/**
 * Plain TCP tunnel — connects directly to the VPS relay server.
 * Used for debugging or when TURN is not configured.
 */
class PlainTcpTunnel(
    private val serverAddress: InetSocketAddress,
    private val timeoutMs: Int = 5000
) : TunnelConnection {

    override fun connect(): TunnelConnection.StreamPair? {
        return try {
            val socket = Socket()
            socket.connect(serverAddress, timeoutMs)
            socket.soTimeout = 30 * 60 * 1000 // 30 min idle
            TunnelConnection.StreamPair(
                inputStream = socket.getInputStream(),
                outputStream = socket.getOutputStream(),
                onClose = { try { socket.close() } catch (_: Exception) {} }
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * TURN tunnel — connects to VPS through a TURN relay server using ChannelData.
 * Provides InputStream/OutputStream over the TURN channel.
 */
class TurnTunnel(
    private val turnServerAddress: InetSocketAddress,
    private val username: String,
    private val password: String,
    private val vpsAddress: InetSocketAddress,
    private val useUdp: Boolean = true,
    private val timeoutMs: Int = 5000
) : TunnelConnection {

    private var turnClient: TurnClient? = null

    override fun connect(): TunnelConnection.StreamPair? {
        return try {
            val client = TurnClient(
                serverAddress = turnServerAddress,
                username = username,
                password = password,
                useUdp = useUdp
            )
            turnClient = client

            client.connect()
            client.allocate() ?: return null
            if (!client.createPermission(vpsAddress)) return null
            val channel = client.channelBind(vpsAddress)
            if (channel < 0) return null

            val relay = client.getRelayStream(channel)
            TunnelConnection.StreamPair(
                inputStream = relay.inputStream,
                outputStream = relay.outputStream,
                onClose = { client.close() }
            )
        } catch (_: Exception) {
            turnClient?.close()
            null
        }
    }
}

/**
 * Simple config for a TURN server (used by the factory).
 */
data class TurnServerConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val useTcp: Boolean
)

/**
 * Factory that creates the appropriate TunnelConnection based on configuration.
 */
class TunnelConnectionFactory(
    private val turnServers: List<TurnServerConfig>,
    private val vpsAddress: InetSocketAddress,
    private val useDtls: Boolean,
    private val connectionTimeout: Int = 5000
) {
    /**
     * Create a TunnelConnection. If TURN servers are configured, uses TURN;
     * otherwise falls back to plain TCP.
     */
    fun create(): TunnelConnection {
        val enabledServer = turnServers.firstOrNull()

        if (enabledServer != null) {
            return TurnTunnel(
                turnServerAddress = InetSocketAddress(enabledServer.host, enabledServer.port),
                username = enabledServer.username,
                password = enabledServer.password,
                vpsAddress = vpsAddress,
                useUdp = !enabledServer.useTcp,
                timeoutMs = connectionTimeout
            )
        }

        // Fallback to plain TCP (for debugging / no TURN configured)
        return PlainTcpTunnel(vpsAddress, connectionTimeout)
    }
}
