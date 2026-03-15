package com.hightemp.turn_proxy_connector.proxy

import com.hightemp.turn_proxy_connector.data.AppSettings
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
 * to a remote Go relay server over plain TCP.
 *
 * Flow:  Browser -> this proxy -> TCP -> relay server -> Internet
 */
class HttpProxyServer(
    private val settings: AppSettings,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HttpProxy"
        private const val BUFFER_SIZE = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val _activeConnections = AtomicLong(0)
    private var serverJob: Job? = null

    val running: Boolean get() = isRunning.get()
    val activeConnections: Long get() = _activeConnections.get()

    fun start() {
        if (isRunning.getAndSet(true)) {
            LogBuffer.w(TAG, "Proxy already running")
            return
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", settings.proxyPort))
                }
                LogBuffer.i(TAG, "Listening on 127.0.0.1:${settings.proxyPort}")
                LogBuffer.i(TAG, "Relay server: ${settings.serverHost}:${settings.serverPort}")

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
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        LogBuffer.i(TAG, "Proxy stop requested")
    }

    private fun handleClient(clientSocket: Socket) {
        _activeConnections.incrementAndGet()
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

            // Connect to relay server
            val relaySocket = Socket()
            try {
                relaySocket.connect(
                    InetSocketAddress(settings.serverHost, settings.serverPort),
                    settings.connectionTimeoutSec * 1000
                )
                relaySocket.soTimeout = settings.idleTimeoutMin * 60 * 1000
            } catch (e: Exception) {
                LogBuffer.e(TAG, "Cannot connect to relay: ${e.message}")
                sendError(clientOut, "502 Bad Gateway", "Cannot connect to relay server")
                clientSocket.close()
                return
            }

            val relayIn = relaySocket.getInputStream()
            val relayOut = relaySocket.getOutputStream()

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

                // Bidirectional relay
                val t1 = Thread {
                    pipeStream(clientIn, relayOut, "client->relay")
                    try { relaySocket.shutdownOutput() } catch (_: Exception) {}
                }
                val t2 = Thread {
                    pipeStream(relayIn, clientOut, "relay->client")
                    try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            } else {
                // For plain HTTP: also send request body if Content-Length present
                val contentLength = headers
                    .find { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

                if (contentLength > 0) {
                    pipeBytes(clientIn, relayOut, contentLength)
                }

                // Read full relay response and pipe to client
                pipeStream(relayIn, clientOut, "relay->client")
            }

            try { relaySocket.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            LogBuffer.e(TAG, "Connection error: ${e.message}")
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
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Connection closed
        }
    }

    private fun pipeBytes(input: InputStream, output: OutputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val n = input.read(buffer, 0, toRead)
            if (n == -1) break
            output.write(buffer, 0, n)
            remaining -= n
        }
        output.flush()
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
