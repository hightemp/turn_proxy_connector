package com.hightemp.turn_proxy_connector.turn

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TDD tests for TurnClient.
 * Uses a fake TURN server (UDP) to test client-server interaction.
 */
class TurnClientTest {

    // ---- Helper: Fake TURN Server ----

    /**
     * Minimal fake TURN server that responds to Allocate, CreatePermission, ChannelBind.
     */
    private class FakeTurnServer(val relayAddress: InetSocketAddress) : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var running = true
        private var nonce = "test-nonce-123"
        private var realm = "test-realm"
        private val thread: Thread

        init {
            socket.soTimeout = 2000
            thread = Thread {
                while (running) {
                    try {
                        val buf = ByteArray(2048)
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        handlePacket(pkt)
                    } catch (_: Exception) {}
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        private fun handlePacket(pkt: DatagramPacket) {
            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)

            // Check if it's a ChannelData message
            if (ChannelData.isChannelData(data)) {
                val cd = ChannelData.decode(data) ?: return
                // Echo back as ChannelData
                val response = ChannelData.encode(cd.channelNumber, cd.data)
                socket.send(DatagramPacket(response, response.size, pkt.address, pkt.port))
                return
            }

            val msg = try { StunMessage.decode(data) } catch (_: Exception) { return }

            when (msg.type) {
                StunMessage.ALLOCATE_REQUEST -> handleAllocate(msg, pkt)
                StunMessage.CREATE_PERMISSION_REQUEST -> handleCreatePermission(msg, pkt)
                StunMessage.CHANNEL_BIND_REQUEST -> handleChannelBind(msg, pkt)
                StunMessage.REFRESH_REQUEST -> handleRefresh(msg, pkt)
                StunMessage.SEND_INDICATION -> handleSendIndication(msg, pkt)
            }
        }

        private fun handleAllocate(msg: StunMessage, pkt: DatagramPacket) {
            val hasIntegrity = msg.getAttribute(StunMessage.ATTR_MESSAGE_INTEGRITY) != null

            if (!hasIntegrity) {
                // Return 401 with nonce and realm
                val resp = StunMessage(StunMessage.ALLOCATE_ERROR, msg.transactionId)
                val errorValue = byteArrayOf(0x00, 0x00, 0x04, 0x01) + "Unauthorized".toByteArray()
                resp.addAttribute(StunMessage.ATTR_ERROR_CODE, errorValue)
                resp.addAttribute(StunMessage.ATTR_NONCE, nonce.toByteArray())
                resp.addAttribute(StunMessage.ATTR_REALM, realm.toByteArray())
                send(resp, pkt)
                return
            }

            // Success
            val resp = StunMessage(StunMessage.ALLOCATE_SUCCESS, msg.transactionId)
            resp.addAttribute(StunMessage.ATTR_XOR_RELAYED_ADDRESS, buildXorRelayAddress(relayAddress))
            resp.addAttribute(StunMessage.ATTR_XOR_MAPPED_ADDRESS, buildXorMappedAddress(
                InetSocketAddress(pkt.address, pkt.port)
            ))
            resp.setLifetime(600)
            send(resp, pkt)
        }

        private fun handleCreatePermission(msg: StunMessage, pkt: DatagramPacket) {
            val resp = StunMessage(StunMessage.CREATE_PERMISSION_SUCCESS, msg.transactionId)
            send(resp, pkt)
        }

        private fun handleChannelBind(msg: StunMessage, pkt: DatagramPacket) {
            val resp = StunMessage(StunMessage.CHANNEL_BIND_SUCCESS, msg.transactionId)
            send(resp, pkt)
        }

        private fun handleRefresh(msg: StunMessage, pkt: DatagramPacket) {
            val resp = StunMessage(StunMessage.REFRESH_SUCCESS, msg.transactionId)
            resp.setLifetime(600)
            send(resp, pkt)
        }

        private fun handleSendIndication(msg: StunMessage, pkt: DatagramPacket) {
            // Echo back as Data indication
            val dataBuf = msg.getAttribute(StunMessage.ATTR_DATA) ?: return
            val peerAddr = msg.getAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS) ?: return

            val resp = StunMessage(StunMessage.DATA_INDICATION, StunMessage.generateTransactionId())
            resp.addAttribute(StunMessage.ATTR_XOR_PEER_ADDRESS, peerAddr)
            resp.addAttribute(StunMessage.ATTR_DATA, dataBuf)
            send(resp, pkt)
        }

        private fun send(msg: StunMessage, dest: DatagramPacket) {
            val data = msg.encode()
            socket.send(DatagramPacket(data, data.size, dest.address, dest.port))
        }

        private fun buildXorRelayAddress(addr: InetSocketAddress): ByteArray {
            return buildXorPeerAddress(addr)
        }

        private fun buildXorMappedAddress(addr: InetSocketAddress): ByteArray {
            return buildXorPeerAddress(addr)
        }

        override fun close() {
            running = false
            socket.close()
            thread.join(2000)
        }
    }

    // ---- Tests ----

    @Test
    fun `allocate returns relay address`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            val allocated = client.allocate()

            assertNotNull(allocated)
            assertEquals(50000, allocated!!.port)
            assertEquals(InetAddress.getByName("10.0.0.1"), allocated.address)

            client.close()
        }
    }

    @Test
    fun `create permission succeeds`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            client.allocate()
            val result = client.createPermission(InetSocketAddress("10.0.0.2", 56000))
            assertTrue(result)

            client.close()
        }
    }

    @Test
    fun `channel bind succeeds`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            client.allocate()
            client.createPermission(InetSocketAddress("10.0.0.2", 56000))
            val channel = client.channelBind(InetSocketAddress("10.0.0.2", 56000))
            assertTrue(channel in ChannelData.MIN_CHANNEL..ChannelData.MAX_CHANNEL)

            client.close()
        }
    }

    @Test
    fun `send and receive via ChannelData`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            client.allocate()
            val peer = InetSocketAddress("10.0.0.2", 56000)
            client.createPermission(peer)
            val channel = client.channelBind(peer)

            // Send data via channel
            val testData = "Hello TURN!".toByteArray()
            client.sendChannelData(channel, testData)

            // Fake server echoes back — receive it
            val received = client.receiveChannelData(2000)
            assertNotNull(received)
            assertArrayEquals(testData, received!!.data)
            assertEquals(channel, received.channelNumber)

            client.close()
        }
    }

    @Test
    fun `send via Send indication and receive Data indication`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            client.allocate()
            val peer = InetSocketAddress("10.0.0.2", 56000)
            client.createPermission(peer)

            // Send data via Send indication
            val testData = "Hello via indication".toByteArray()
            client.sendIndication(peer, testData)

            // Receive Data indication (echoed by fake server)
            val received = client.receiveDataIndication(2000)
            assertNotNull(received)
            assertArrayEquals(testData, received!!.second)

            client.close()
        }
    }

    @Test
    fun `client lifecycle connect-allocate-close`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            assertFalse(client.isConnected)
            client.connect()
            assertTrue(client.isConnected)
            client.allocate()
            assertTrue(client.isAllocated)
            client.close()
            assertFalse(client.isConnected)
        }
    }

    @Test
    fun `allocate with retry after 401`() {
        // The fake server always returns 401 first (no integrity), then success
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            // First allocate request should get 401, then client retries with credentials
            val allocated = client.allocate()
            assertNotNull(allocated)

            client.close()
        }
    }

    @Test
    fun `getRelaySocket returns a pipeable interface`() {
        val relayAddr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), 50000)
        FakeTurnServer(relayAddr).use { server ->
            val client = TurnClient(
                serverAddress = InetSocketAddress("127.0.0.1", server.port),
                username = "testuser",
                password = "testpass",
                useUdp = true
            )

            client.connect()
            client.allocate()
            val peer = InetSocketAddress("10.0.0.2", 56000)
            client.createPermission(peer)
            val channel = client.channelBind(peer)

            val relay = client.getRelayStream(channel)
            assertNotNull(relay)

            client.close()
        }
    }
}
