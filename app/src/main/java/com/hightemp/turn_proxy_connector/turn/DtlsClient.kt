package com.hightemp.turn_proxy_connector.turn

import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ServerOnlyTlsAuthentication
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Bouncy Castle DTLS TLS client that:
 * - Skips certificate verification (self-signed VPS cert)
 * - Uses TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (matching Go server)
 * - DTLS 1.2
 */
class InsecureDtlsTlsClient(crypto: TlsCrypto) : DefaultTlsClient(crypto) {

    override fun getAuthentication(): TlsAuthentication {
        return object : ServerOnlyTlsAuthentication() {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                // Accept any certificate (self-signed on VPS)
            }
        }
    }

    override fun getSupportedCipherSuites(): IntArray {
        return intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
    }
}

/**
 * DTLS client using Bouncy Castle DTLSClientProtocol.
 *
 * Performs a real DTLS 1.2 handshake over the provided DatagramTransport
 * (typically a TurnDatagramTransport wrapping TURN ChannelData).
 *
 * After handshake, provides DTLSTransport for encrypted send/receive.
 *
 * Architecture:
 *   HTTP data → DtlsClient.send() → DTLS encrypt → DatagramTransport → TURN → VPS
 *   HTTP data ← DtlsClient.receive() ← DTLS decrypt ← DatagramTransport ← TURN ← VPS
 */
class DtlsClient : Closeable {

    private var dtlsTransport: DTLSTransport? = null
    private var connected = false

    val isConnected: Boolean get() = connected

    /**
     * Perform DTLS handshake over the given datagram transport.
     *
     * @param transport The underlying transport (e.g., TurnDatagramTransport)
     * @return DTLSTransport for encrypted communication, or null on failure
     */
    fun connect(transport: DatagramTransport): DTLSTransport? {
        return try {
            val crypto = BcTlsCrypto(SecureRandom())
            val tlsClient = InsecureDtlsTlsClient(crypto)
            val protocol = DTLSClientProtocol()

            val dtls = protocol.connect(tlsClient, transport)
            dtlsTransport = dtls
            connected = true
            dtls
        } catch (e: TlsFatalAlert) {
            android.util.Log.e("DtlsClient", "DTLS handshake TLS fatal alert: ${e.alertDescription}", e)
            connected = false
            null
        } catch (e: Exception) {
            android.util.Log.e("DtlsClient", "DTLS handshake failed: ${e.message}", e)
            connected = false
            null
        }
    }

    override fun close() {
        connected = false
        try { dtlsTransport?.close() } catch (_: Exception) {}
        dtlsTransport = null
    }
}

/**
 * InputStream wrapping a DTLSTransport.
 * Each DTLS record becomes a chunk of data in the stream.
 *
 * @param idleTimeoutMs Maximum time to wait for data before returning EOF.
 *        Defaults to 30 minutes to match Go server's idle timeout.
 *        For CONNECT tunnels this must be long enough for idle browsing.
 */
class DtlsInputStream(
    private val transport: DTLSTransport,
    private val idleTimeoutMs: Int = 30 * 60 * 1000
) : InputStream() {
    private var buffer: ByteArray? = null
    private var offset = 0

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (buffer == null || offset >= buffer!!.size) {
            val tmp = ByteArray(2048)
            val n = try {
                transport.receive(tmp, 0, tmp.size, idleTimeoutMs)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) return -1  // n==0 would violate InputStream contract; treat as EOF
            buffer = tmp.copyOf(n)
            offset = 0
        }
        val available = buffer!!.size - offset
        val toCopy = minOf(len, available)
        System.arraycopy(buffer!!, offset, b, off, toCopy)
        offset += toCopy
        return toCopy
    }
}

/**
 * OutputStream wrapping a DTLSTransport.
 * Fragments large writes into DTLS-safe chunks.
 */
class DtlsOutputStream(private val transport: DTLSTransport) : OutputStream() {
    /**
     * Max plaintext chunk size per DTLS record.
     * BC DTLSTransport.send() encrypts and adds overhead internally.
     * Use getSendLimit() if available; otherwise conservative default.
     * With MTU 1200 and ~80 bytes DTLS overhead → ~1120 bytes usable.
     */
    private val maxChunk: Int = try {
        transport.sendLimit.coerceIn(256, 1400)
    } catch (_: Exception) {
        1100
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        while (pos < off + len) {
            val chunkSize = minOf(maxChunk, off + len - pos)
            transport.send(b, pos, chunkSize)
            pos += chunkSize
        }
    }

    override fun flush() { /* DTLS sends immediately */ }
}

