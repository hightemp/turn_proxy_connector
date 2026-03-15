package com.hightemp.turn_proxy_connector.turn

import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

/**
 * Tests for DtlsClient and related classes.
 * Real DTLS handshake can't be tested without a server, so we test
 * the components: TurnDatagramTransport, InsecureDtlsTlsClient, etc.
 */
class DtlsClientTest {

    @Test
    fun `DtlsClient initial state`() {
        val client = DtlsClient()
        assertNotNull(client)
        assertFalse(client.isConnected)
        client.close()
        assertFalse(client.isConnected)
    }

    @Test
    fun `InsecureDtlsTlsClient creates with correct crypto`() {
        val crypto = BcTlsCrypto(SecureRandom())
        val tlsClient = InsecureDtlsTlsClient(crypto)
        // Client should be non-null and usable
        assertNotNull(tlsClient)
    }

    @Test
    fun `InsecureDtlsTlsClient authentication skips verification`() {
        val crypto = BcTlsCrypto(SecureRandom())
        val tlsClient = InsecureDtlsTlsClient(crypto)
        // Authentication should be available (ServerOnlyTlsAuthentication)
        assertNotNull(tlsClient.authentication)
    }

    @Test
    fun `TurnDatagramTransport limits and close`() {
        // Create a minimal stub TurnClient for testing transport interface
        val client = TurnClient(
            serverAddress = java.net.InetSocketAddress("127.0.0.1", 1),
            username = "test",
            password = "test",
            useUdp = true
        )
        val transport = TurnDatagramTransport(client, 0x4000, 1200)
        assertEquals(1200, transport.receiveLimit)
        // sendLimit = mtu - DTLS_OVERHEAD (80) = 1120
        assertEquals(1120, transport.sendLimit)
        transport.close()
        // After close, receive throws IOException per BC DatagramTransport contract
        val buf = ByteArray(100)
        assertThrows(IOException::class.java) {
            transport.receive(buf, 0, 100, 100)
        }
    }
}
