package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Timer
import java.util.TimerTask

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
 * WARNING: No DTLS encryption, no reliability guarantees over UDP.
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
    private var refreshTimer: Timer? = null

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

            // Start refresh timer (allocation + channel binding)
            refreshTimer = Timer("turn-refresh", true).apply {
                scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        try { client.refresh(600) } catch (_: Exception) {}
                        try { client.refreshChannelBind(channel, vpsAddress) } catch (_: Exception) {}
                    }
                }, 300_000L, 300_000L)
            }

            val relay = client.getRelayStream(channel)
            TunnelConnection.StreamPair(
                inputStream = relay.inputStream,
                outputStream = relay.outputStream,
                onClose = {
                    refreshTimer?.cancel()
                    client.close()
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("TurnTunnel", "TURN tunnel connect failed: ${e.message}", e)
            refreshTimer?.cancel()
            turnClient?.close()
            null
        }
    }
}

/**
 * DTLS + TURN tunnel — connects to VPS through TURN relay with DTLS encryption.
 *
 * Architecture (matching reference Go client):
 *   HTTP data → DTLS encrypt → ChannelData → TURN relay → VPS DTLS server → Internet
 *
 * Steps:
 * 1. Connect to TURN server (UDP or TCP)
 * 2. Allocate relay address
 * 3. CreatePermission for VPS peer
 * 4. ChannelBind for VPS peer
 * 5. Create TurnDatagramTransport over ChannelData
 * 6. Perform DTLS handshake through the TURN relay
 * 7. Return encrypted InputStream/OutputStream
 */
class DtlsTurnTunnel(
    private val turnServerAddress: InetSocketAddress,
    private val username: String,
    private val password: String,
    private val vpsAddress: InetSocketAddress,
    private val useUdp: Boolean = true,
    private val timeoutMs: Int = 5000
) : TunnelConnection {

    private var turnClient: TurnClient? = null
    private var dtlsClient: DtlsClient? = null
    private var turnTransport: TurnDatagramTransport? = null
    private var refreshTimer: Timer? = null

    override fun connect(): TunnelConnection.StreamPair? {
        return try {
            // Step 1-4: TURN setup
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

            // Step 5: Create datagram transport over TURN ChannelData
            val transport = TurnDatagramTransport(client, channel)
            turnTransport = transport

            // Step 6: DTLS handshake through TURN relay
            val dtls = DtlsClient()
            dtlsClient = dtls
            val dtlsTransport = dtls.connect(transport)
                ?: throw IllegalStateException("DTLS handshake failed")

            // Step 7: Start refresh timer (every 300s to keep 600s allocation + 10min channel binding alive)
            startRefreshTimer(client, channel)

            // Step 8: Wrap DTLSTransport as InputStream/OutputStream
            TunnelConnection.StreamPair(
                inputStream = DtlsInputStream(dtlsTransport, 30 * 60 * 1000),
                outputStream = DtlsOutputStream(dtlsTransport),
                onClose = {
                    stopRefreshTimer()
                    dtls.close()
                    transport.close()
                    client.close()
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DtlsTurnTunnel", "DTLS+TURN tunnel connect failed: ${e.message}", e)
            stopRefreshTimer()
            dtlsClient?.close()
            turnTransport?.close()
            turnClient?.close()
            null
        }
    }

    private fun startRefreshTimer(client: TurnClient, channel: Int) {
        refreshTimer = Timer("turn-refresh", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        client.refresh(600)
                    } catch (_: Exception) {
                        // Refresh failure — allocation may expire
                    }
                    try {
                        client.refreshChannelBind(channel, vpsAddress)
                    } catch (_: Exception) {
                        // ChannelBind refresh failure — binding may expire
                    }
                }
            }, REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS)
        }
    }

    private fun stopRefreshTimer() {
        refreshTimer?.cancel()
        refreshTimer = null
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 300_000L // 5 minutes
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
     * Create a TunnelConnection. If TURN servers are configured:
     * - useDtls=true → DtlsTurnTunnel (TURN + DTLS encryption)
     * - useDtls=false → TurnTunnel (TURN only, no encryption)
     * Otherwise falls back to plain TCP.
     */
    fun create(): TunnelConnection {
        val enabledServer = turnServers.firstOrNull()

        if (enabledServer != null) {
            val turnAddr = InetSocketAddress(enabledServer.host, enabledServer.port)
            if (useDtls) {
                return DtlsTurnTunnel(
                    turnServerAddress = turnAddr,
                    username = enabledServer.username,
                    password = enabledServer.password,
                    vpsAddress = vpsAddress,
                    useUdp = !enabledServer.useTcp,
                    timeoutMs = connectionTimeout
                )
            } else {
                return TurnTunnel(
                    turnServerAddress = turnAddr,
                    username = enabledServer.username,
                    password = enabledServer.password,
                    vpsAddress = vpsAddress,
                    useUdp = !enabledServer.useTcp,
                    timeoutMs = connectionTimeout
                )
            }
        }

        // Fallback to plain TCP (for debugging / no TURN configured)
        return PlainTcpTunnel(vpsAddress, connectionTimeout)
    }
}
