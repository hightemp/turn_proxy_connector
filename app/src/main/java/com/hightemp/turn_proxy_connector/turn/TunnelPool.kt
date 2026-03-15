package com.hightemp.turn_proxy_connector.turn

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool of TunnelConnections that manages multiple tunnels for
 * parallel HTTP request processing.
 *
 * Each acquire() call either reuses an idle tunnel or creates a new one
 * (up to poolSize). When the StreamPair is closed, the tunnel slot is released.
 *
 * Supports:
 * - Configurable pool size
 * - Retry on connection failure (maxRetries)
 * - Concurrent-safe acquire/release
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

    private val semaphore = Semaphore(poolSize)
    private val shuttingDown = AtomicBoolean(false)
    private val _activeCount = AtomicInteger(0)

    val activeCount: Int get() = _activeCount.get()

    /**
     * Acquire a StreamPair from the pool. Creates a new tunnel if needed.
     * Blocks up to [timeoutMs] milliseconds waiting for a pool slot.
     *
     * @return StreamPair, or null if pool is shut down, timeout expires, or all retries fail.
     */
    fun acquire(timeoutMs: Long = 5000): TunnelConnection.StreamPair? {
        if (shuttingDown.get()) return null

        // Wait for an available pool slot
        if (!semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            return null
        }

        if (shuttingDown.get()) {
            semaphore.release()
            return null
        }

        // Try to connect with retries
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val tunnel = factory.create()
                val stream = tunnel.connect()
                if (stream != null) {
                    _activeCount.incrementAndGet()
                    // Wrap the stream to release slot on close
                    return TunnelConnection.StreamPair(
                        inputStream = stream.inputStream,
                        outputStream = stream.outputStream,
                        onClose = {
                            _activeCount.decrementAndGet()
                            semaphore.release()
                            try { stream.close() } catch (_: Exception) {}
                        }
                    )
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
     * Shut down the pool. Prevents new acquires.
     */
    fun shutdown() {
        shuttingDown.set(true)
    }

    override fun close() {
        shutdown()
    }
}
