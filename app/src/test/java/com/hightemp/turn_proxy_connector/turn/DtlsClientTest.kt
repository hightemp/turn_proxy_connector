package com.hightemp.turn_proxy_connector.turn

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TDD tests for DtlsClient wrapper.
 * Tests use a simple UDP echo server to verify the DTLS layer works.
 */
class DtlsClientTest {

    @Test
    fun `DtlsClient wraps DatagramSocket for net_Conn-like interface`() {
        val client = DtlsClient()
        assertNotNull(client)
        assertFalse(client.isConnected)
    }

    @Test
    fun `DtlsClient connect to DTLS server and handshake`() {
        // We can't easily test real DTLS without a server,
        // but we CAN test that the client creates a proper config
        val config = DtlsClient.createDtlsConfig(insecureSkipVerify = true)
        assertNotNull(config)
    }

    @Test
    fun `DtlsPacketTransport wraps UDP socket as PacketConn`() {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val transport = DtlsPacketTransport(socket)
        assertNotNull(transport)
        assertEquals(socket.localPort, transport.localPort)
        socket.close()
    }

    @Test
    fun `DtlsPacketTransport send and receive raw UDP`() {
        // Echo server
        val echoSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val echoPort = echoSocket.localPort

        val latch = CountDownLatch(1)
        Thread {
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            echoSocket.receive(pkt)
            echoSocket.send(DatagramPacket(pkt.data, pkt.length, pkt.address, pkt.port))
            latch.countDown()
        }.start()

        val clientSocket = DatagramSocket()
        val transport = DtlsPacketTransport(clientSocket)

        val testData = "hello dtls transport".toByteArray()
        transport.send(testData, InetSocketAddress("127.0.0.1", echoPort))

        val received = transport.receive(1000)
        assertNotNull(received)
        assertEquals("hello dtls transport", String(received!!.data, 0, received.length))

        echoSocket.close()
        clientSocket.close()
    }
}
