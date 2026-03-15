package com.hightemp.turn_proxy_connector.turn

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TDD tests for TunnelConnection transport abstraction.
 */
class TunnelConnectionTest {

    // ---- Interface contract ----

    @Test
    fun `PlainTcpTunnel implements TunnelConnection`() {
        val tunnel: TunnelConnection = PlainTcpTunnel(InetSocketAddress("127.0.0.1", 1234), 5000)
        assertNotNull(tunnel)
    }

    @Test
    fun `TurnTunnel implements TunnelConnection`() {
        val tunnel: TunnelConnection = TurnTunnel(
            turnServerAddress = InetSocketAddress("127.0.0.1", 3478),
            username = "user",
            password = "pass",
            vpsAddress = InetSocketAddress("127.0.0.1", 56000)
        )
        assertNotNull(tunnel)
    }

    // ---- PlainTcpTunnel ----

    @Test
    fun `PlainTcpTunnel connect and pipe data`() {
        // Start a TCP echo server
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val echoThread = Thread {
            val conn = server.accept()
            val buf = ByteArray(1024)
            val n = conn.getInputStream().read(buf)
            if (n > 0) {
                conn.getOutputStream().write(buf, 0, n)
                conn.getOutputStream().flush()
            }
            conn.close()
        }
        echoThread.start()

        val tunnel = PlainTcpTunnel(InetSocketAddress("127.0.0.1", serverPort), 5000)
        val conn = tunnel.connect()
        assertNotNull(conn)

        conn!!.outputStream.write("hello".toByteArray())
        conn.outputStream.flush()

        val buf = ByteArray(1024)
        val n = conn.inputStream.read(buf)
        assertEquals("hello", String(buf, 0, n))

        conn.close()
        server.close()
    }

    @Test
    fun `PlainTcpTunnel connect failure returns null`() {
        // Connect to a port that's not listening
        val tunnel = PlainTcpTunnel(InetSocketAddress("127.0.0.1", 1), 500)
        val conn = tunnel.connect()
        assertNull(conn)
    }

    // ---- TunnelConnection.StreamPair ----

    @Test
    fun `StreamPair wraps input and output streams`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream()
        val pair = TunnelConnection.StreamPair(pipeIn, pipeOut)
        assertSame(pipeIn, pair.inputStream)
        assertSame(pipeOut, pair.outputStream)
        pair.close()
    }

    // ---- TunnelConnectionFactory ----

    @Test
    fun `factory creates PlainTcpTunnel when no TURN servers enabled`() {
        val factory = TunnelConnectionFactory(
            turnServers = emptyList(),
            vpsAddress = InetSocketAddress("10.0.0.1", 56000),
            useDtls = false,
            connectionTimeout = 5000
        )
        val tunnel = factory.create()
        assertTrue(tunnel is PlainTcpTunnel)
    }

    @Test
    fun `factory creates TurnTunnel when TURN servers provided`() {
        val servers = listOf(
            TurnServerConfig("turn.example.com", 3478, "user", "pass", false)
        )
        val factory = TunnelConnectionFactory(
            turnServers = servers,
            vpsAddress = InetSocketAddress("10.0.0.1", 56000),
            useDtls = true,
            connectionTimeout = 5000
        )
        val tunnel = factory.create()
        assertTrue(tunnel is TurnTunnel)
    }
}
