package com.hightemp.turn_proxy_connector.proxy

import com.hightemp.turn_proxy_connector.data.AppSettings
import com.hightemp.turn_proxy_connector.data.TurnServer
import com.hightemp.turn_proxy_connector.turn.PlainTcpTunnel
import com.hightemp.turn_proxy_connector.turn.TunnelConnection
import com.hightemp.turn_proxy_connector.turn.TunnelConnectionFactory
import com.hightemp.turn_proxy_connector.turn.TunnelPool
import com.hightemp.turn_proxy_connector.turn.TurnServerConfig
import com.hightemp.turn_proxy_connector.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP proxy server on 127.0.0.1:proxyPort.
 * Forwards every incoming HTTP request (including CONNECT for HTTPS)
 * to a remote Go relay server through a TunnelConnection pool.
 *
 * Flow:  Browser -> this proxy -> TunnelPool -> relay server -> Internet
 *
 * TunnelConnection can be:
 * - PlainTcpTunnel (direct TCP to VPS, for debugging)
 * - TurnTunnel (via TURN relay server)
 * - DTLS+TURN (future, for encryption)
 */
class HttpProxyServer(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val turnServers: List<TurnServer> = emptyList()
) {
    companion object {
        private const val TAG = "HttpProxy"
        private const val BUFFER_SIZE = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val _activeConnections = AtomicLong(0)
    private val _bytesSent = AtomicLong(0)
    private val _bytesReceived = AtomicLong(0)
    private var serverJob: Job? = null
    private var tunnelPool: TunnelPool? = null

    val running: Boolean get() = isRunning.get()
    val activeConnections: Long get() = _activeConnections.get()
    val bytesSent: Long get() = _bytesSent.get()
    val bytesReceived: Long get() = _bytesReceived.get()

    fun start() {
        if (isRunning.getAndSet(true)) {
            LogBuffer.w(TAG, "Proxy already running")
            return
        }

        // Create the tunnel pool
        val poolSize = settings.turnPoolSize.coerceIn(1, 32)
        tunnelPool = TunnelPool(
            factory = object : TunnelPool.TunnelFactory {
                override fun create(): TunnelConnection = createTunnel()
            },
            poolSize = poolSize,
            maxRetries = 3
        )

        // Pre-warm the pool in background to reduce first-request latency
        scope.launch(Dispatchers.IO) {
            try {
                LogBuffer.i(TAG, "Warming up tunnel pool...")
                tunnelPool?.warmUp(1)
                LogBuffer.i(TAG, "Tunnel pool warm-up complete")
            } catch (e: Exception) {
                LogBuffer.w(TAG, "Pool warm-up failed: ${e.message}")
            }
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", settings.proxyPort))
                }
                LogBuffer.i(TAG, "Listening on 127.0.0.1:${settings.proxyPort}")

                val enabledTurnServers = turnServers.filter { it.enabled }
                if (enabledTurnServers.isNotEmpty()) {
                    LogBuffer.i(TAG, "Using TURN servers: ${enabledTurnServers.joinToString { "${it.host}:${it.port}" }} (pool=$poolSize)")
                } else {
                    LogBuffer.i(TAG, "No TURN servers, using direct TCP to ${settings.serverHost}:${settings.serverPort} (pool=$poolSize)")
                }

                while (isActive && isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: SocketException) {
                        if (isRunning.get()) {
                            LogBuffer.e(TAG, "Accept error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                LogBuffer.e(TAG, "Server error: ${e.message}")
            } finally {
                isRunning.set(false)
                LogBuffer.i(TAG, "Proxy stopped")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        tunnelPool?.shutdown()
        tunnelPool = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        LogBuffer.i(TAG, "Proxy stop requested")
    }

    private fun createTunnel(): TunnelConnection {
        val enabledTurnServers = turnServers.filter { it.enabled }
        val turnConfigs = enabledTurnServers.map { server ->
            TurnServerConfig(
                host = server.host,
                port = server.port,
                username = server.username,
                password = server.password,
                useTcp = server.useTcp
            )
        }
        val vpsAddress = InetSocketAddress(settings.serverHost, settings.serverPort)
        return TunnelConnectionFactory(
            turnServers = turnConfigs,
            vpsAddress = vpsAddress,
            useDtls = settings.useDtls,
            connectionTimeout = settings.connectionTimeoutSec * 1000
        ).create()
    }

    private fun handleClient(clientSocket: Socket) {
        _activeConnections.incrementAndGet()
        var pooledStream: TunnelPool.PooledStream? = null
        var fallbackStream: TunnelConnection.StreamPair? = null
        try {
            clientSocket.soTimeout = settings.idleTimeoutMin * 60 * 1000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            if (settings.serverHost.isBlank()) {
                LogBuffer.e(TAG, "Relay server host not configured")
                sendError(clientOut, "502 Bad Gateway", "Relay server not configured")
                clientSocket.close()
                return
            }

            // Read the request line
            val requestLine = readLine(clientIn)
            if (requestLine.isNullOrBlank()) {
                clientSocket.close()
                return
            }

            // Read headers
            val headers = mutableListOf<String>()
            while (true) {
                val headerLine = readLine(clientIn) ?: break
                if (headerLine.isEmpty()) break
                headers.add(headerLine)
            }

            LogBuffer.d(TAG, requestLine)

            // Acquire connection from the pool (with retry/failover)
            val pool = tunnelPool
            if (pool != null) {
                pooledStream = try {
                    pool.acquire(timeoutMs = settings.connectionTimeoutSec * 1000L)
                } catch (e: Exception) {
                    LogBuffer.e(TAG, "Cannot acquire tunnel: ${e.message}")
                    null
                }
            }

            // Fallback: create direct tunnel if pool not available
            if (pooledStream == null && pool == null) {
                fallbackStream = try {
                    createTunnel().connect()
                } catch (e: Exception) {
                    LogBuffer.e(TAG, "Cannot connect to relay: ${e.message}")
                    null
                }
            }

            if (pooledStream == null && fallbackStream == null) {
                LogBuffer.e(TAG, "Tunnel connection failed")
                sendError(clientOut, "502 Bad Gateway", "Cannot connect to relay server")
                clientSocket.close()
                return
            }

            val relayIn = pooledStream?.inputStream ?: fallbackStream!!.inputStream
            val relayOut = pooledStream?.outputStream ?: fallbackStream!!.outputStream

            // Send request + headers to relay
            val req = buildString {
                append(requestLine).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            relayOut.write(req.toByteArray(Charsets.ISO_8859_1))
            relayOut.flush()

            val parts = requestLine.split(" ", limit = 3)
            val isConnect = parts.isNotEmpty() && parts[0].equals("CONNECT", ignoreCase = true)

            if (isConnect) {
                // Read relay response (should be 200 Connection Established)
                val statusLine = readLine(relayIn) ?: ""
                val respHeaders = mutableListOf<String>()
                while (true) {
                    val h = readLine(relayIn) ?: break
                    if (h.isEmpty()) break
                    respHeaders.add(h)
                }

                // Forward status to client
                val resp = buildString {
                    append(statusLine).append("\r\n")
                    respHeaders.forEach { append(it).append("\r\n") }
                    append("\r\n")
                }
                clientOut.write(resp.toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                LogBuffer.d(TAG, "CONNECT tunnel: $statusLine")

                // Bidirectional relay with proper cleanup:
                // When one direction closes, close both sockets to unblock the other.
                val t1 = Thread({
                    try {
                        pipeStream(clientIn, relayOut, "client->relay")
                    } finally {
                        // Client closed → close relay to unblock t2
                        try { pooledStream?.close() ?: fallbackStream?.close() } catch (_: Exception) {}
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }, "proxy-pipe-c2r")
                val t2 = Thread({
                    try {
                        pipeStream(relayIn, clientOut, "relay->client")
                    } finally {
                        // Relay closed → close client to unblock t1
                        try { clientSocket.close() } catch (_: Exception) {}
                        try { pooledStream?.close() ?: fallbackStream?.close() } catch (_: Exception) {}
                    }
                }, "proxy-pipe-r2c")
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            } else {
                // For plain HTTP: also send request body if Content-Length present
                val contentLength = headers
                    .find { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

                val isChunkedRequest = headers.any {
                    it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
                }

                if (isChunkedRequest) {
                    pipeChunked(clientIn, relayOut, "client->relay")
                } else if (contentLength > 0) {
                    pipeBytes(clientIn, relayOut, contentLength.toLong())
                }

                // Read HTTP response: parse status line + headers to determine body length
                val statusLine = readLine(relayIn) ?: ""
                val respHeaders = mutableListOf<String>()
                while (true) {
                    val h = readLine(relayIn) ?: break
                    if (h.isEmpty()) break
                    respHeaders.add(h)
                }

                // Forward response status + headers to client
                val resp = buildString {
                    append(statusLine).append("\r\n")
                    respHeaders.forEach { append(it).append("\r\n") }
                    append("\r\n")
                }
                clientOut.write(resp.toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                // Determine body transfer method
                val respContentLength = respHeaders
                    .find { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toLongOrNull()

                val isChunkedResponse = respHeaders.any {
                    it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
                }

                // Extract status code (e.g., "HTTP/1.1 204 No Content" → 204)
                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 200
                val hasNoBody = statusCode in 100..199 || statusCode == 204 || statusCode == 304

                if (hasNoBody) {
                    // No body expected
                } else if (isChunkedResponse) {
                    pipeChunked(relayIn, clientOut, "relay->client")
                } else if (respContentLength != null && respContentLength >= 0) {
                    pipeBytes(relayIn, clientOut, respContentLength)
                } else {
                    // No Content-Length, not chunked — read until EOF (e.g., HTTP/1.0)
                    // Force connection close after this — can't determine when body ends otherwise
                    pipeStream(relayIn, clientOut, "relay->client")
                    // Connection is not reusable after read-until-EOF
                    pooledStream?.close() ?: fallbackStream?.close()
                    try { clientSocket.close() } catch (_: Exception) {}
                    return
                }

                // Check if server requested closing the connection
                val connectionClose = respHeaders.any {
                    it.startsWith("Connection:", ignoreCase = true) &&
                    it.contains("close", ignoreCase = true)
                }

                if (connectionClose) {
                    // Server wants to close — destroy, don't reuse
                    pooledStream?.close() ?: fallbackStream?.close()
                } else {
                    // Plain HTTP: release tunnel back to pool for reuse
                    pooledStream?.release() ?: fallbackStream?.close()
                }
            }

            try { clientSocket.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            LogBuffer.e(TAG, "Connection error: ${e.message}")
            // On error, destroy the connection (not reusable)
            try { pooledStream?.close() ?: fallbackStream?.close() } catch (_: Exception) {}
        } finally {
            _activeConnections.decrementAndGet()
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                sb.append('\r')
                if (next != -1) sb.append(next.toChar())
            } else if (b == '\n'.code) {
                return sb.toString()
            } else {
                sb.append(b.toChar())
            }
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream, label: String) {
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            val isSending = label.contains("client->relay")
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
                if (isSending) _bytesSent.addAndGet(n.toLong())
                else _bytesReceived.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
            // Connection closed
        }
    }

    private fun pipeBytes(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n == -1) break
            output.write(buffer, 0, n)
            remaining -= n
            _bytesSent.addAndGet(n.toLong())
        }
        output.flush()
    }

    /**
     * Pipe HTTP chunked transfer encoding: reads chunk-size CRLF chunk-data CRLF ...
     * until 0-length chunk. Forwards raw chunked encoding to output.
     */
    private fun pipeChunked(input: InputStream, output: OutputStream, label: String) {
        try {
            val isSending = label.contains("client->relay")
            while (true) {
                // Read chunk size line
                val sizeLine = readLine(input) ?: break
                val chunkSize = sizeLine.trim().split(";")[0].toLongOrNull(16) ?: break

                // Forward chunk header
                val header = "$sizeLine\r\n".toByteArray(Charsets.ISO_8859_1)
                output.write(header)

                if (chunkSize == 0L) {
                    // Terminal chunk — read and forward trailing CRLF
                    val trailer = readLine(input) ?: ""
                    output.write("$trailer\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    break
                }

                // Forward chunk data
                var remaining = chunkSize
                val buffer = ByteArray(BUFFER_SIZE)
                while (remaining > 0) {
                    val toRead = minOf(remaining.toInt(), buffer.size)
                    val n = input.read(buffer, 0, toRead)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    remaining -= n
                    if (isSending) _bytesSent.addAndGet(n.toLong())
                    else _bytesReceived.addAndGet(n.toLong())
                }

                // Read trailing CRLF after chunk data
                val crlf = readLine(input) ?: break
                output.write("$crlf\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
        } catch (_: Exception) {
            // Connection closed
        }
    }

    private fun sendError(output: OutputStream, status: String, message: String) {
        try {
            val body = "<html><body><h1>$status</h1><p>$message</p></body></html>"
            val resp = "HTTP/1.1 $status\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            output.write(resp.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }
}
