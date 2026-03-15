package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool of persistent TunnelConnections that reuses established TURN+DTLS sessions.
 *
 * Instead of creating a new TURN allocation + DTLS handshake for every HTTP request
 * (which would take 500ms-5s each), the pool maintains a set of ready connections
 * that are returned to the pool after use.
 *
 * Flow:
 * 1. acquire() takes a PooledStream from the pool (or creates new)
 * 2. The caller uses the PooledStream for one HTTP request/response
 * 3. When done:
 *    - CONNECT tunnels: close() destroys the TURN+DTLS connection
 *    - Plain HTTP: release() returns the connection to idle pool for reuse
 *
 * Supports:
 * - Configurable pool size (max concurrent connections)
 * - Retry on connection failure (maxRetries)
 * - Pre-warming: creates connections eagerly on start
 * - Graceful shutdown
 */
class TunnelPool(
    private val factory: TunnelFactory,
    private val poolSize: Int = 4,
    private val maxRetries: Int = 3
) : Closeable {

    /**
     * Factory interface for creating TunnelConnections.
     */
    interface TunnelFactory {
        fun create(): TunnelConnection
    }

    /**
     * A stream acquired from the pool. Provides two disposal methods:
     * - [close]: Destroys the underlying TURN+DTLS connection (for CONNECT tunnels)
     * - [release]: Returns the connection to the idle pool for reuse (for plain HTTP)
     */
    inner class PooledStream internal constructor(
        private val inner: TunnelConnection.StreamPair
    ) : Closeable {
        val inputStream: InputStream get() = inner.inputStream
        val outputStream: OutputStream get() = inner.outputStream

        private val disposed = AtomicBoolean(false)

        /**
         * Destroy the connection and release the pool slot.
         * Use for CONNECT tunnels that consume the full connection.
         */
        override fun close() {
            if (!disposed.getAndSet(true)) {
                _activeCount.decrementAndGet()
                semaphore.release()
                try { inner.close() } catch (_: Exception) {}
            }
        }

        /**
         * Return the connection to the idle pool for reuse.
         * Use for plain HTTP requests where the connection stays valid.
         */
        fun release() {
            if (!disposed.getAndSet(true)) {
                _activeCount.decrementAndGet()
                semaphore.release()
                if (shuttingDown.get()) {
                    try { inner.close() } catch (_: Exception) {}
                } else {
                    // Health-check: verify stream is usable by writing a zero-byte probe.
                    // If the underlying connection is broken, write/flush will throw.
                    val healthy = try {
                        inner.outputStream.flush()
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (healthy) {
                        idleConnections.offer(inner)
                    } else {
                        try { inner.close() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private val semaphore = Semaphore(poolSize)
    private val shuttingDown = AtomicBoolean(false)
    private val _activeCount = AtomicInteger(0)

    // Pool of pre-established idle connections
    private val idleConnections = ConcurrentLinkedQueue<TunnelConnection.StreamPair>()

    val activeCount: Int get() = _activeCount.get()

    /**
     * Pre-warm the pool by establishing connections in background.
     * Call after creating the pool to reduce first-request latency.
     */
    fun warmUp(count: Int = 1) {
        if (shuttingDown.get()) return
        val toCreate = minOf(count, poolSize)
        for (i in 0 until toCreate) {
            try {
                val tunnel = factory.create()
                val stream = tunnel.connect()
                if (stream != null) {
                    idleConnections.offer(stream)
                }
            } catch (_: Exception) {
                // Warm-up failures are non-fatal
            }
        }
    }

    /**
     * Acquire a PooledStream from the pool. Reuses an idle connection if available,
     * otherwise creates a new tunnel with retry.
     *
     * Blocks up to [timeoutMs] milliseconds waiting for a pool slot.
     *
     * @return PooledStream, or null if pool is shut down, timeout expires, or all retries fail.
     */
    fun acquire(timeoutMs: Long = 5000): PooledStream? {
        if (shuttingDown.get()) return null

        // Wait for an available pool slot
        if (!semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            return null
        }

        if (shuttingDown.get()) {
            semaphore.release()
            return null
        }

        // Try to reuse an idle connection
        val idle = idleConnections.poll()
        if (idle != null) {
            _activeCount.incrementAndGet()
            return PooledStream(idle)
        }

        // Create a new connection with retries
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val tunnel = factory.create()
                val stream = tunnel.connect()
                if (stream != null) {
                    _activeCount.incrementAndGet()
                    return PooledStream(stream)
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        // All retries exhausted
        semaphore.release()
        return null
    }

    /**
     * Shut down the pool. Prevents new acquires and closes idle connections.
     */
    fun shutdown() {
        shuttingDown.set(true)
        // Close all idle connections
        while (true) {
            val conn = idleConnections.poll() ?: break
            try { conn.close() } catch (_: Exception) {}
        }
    }

    override fun close() {
        shutdown()
    }
}
