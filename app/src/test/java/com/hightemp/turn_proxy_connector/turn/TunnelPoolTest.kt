package com.hightemp.turn_proxy_connector.turn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TDD tests for TunnelPool — manages a pool of TunnelConnections
 * for multiplexing HTTP requests across multiple TURN tunnels.
 */
class TunnelPoolTest {

    // ---- Fake TunnelConnection for testing ----

    private class FakeTunnel(
        private val shouldSucceed: Boolean = true,
        private val inputData: ByteArray = ByteArray(0),
        private val connectDelay: Long = 0
    ) : TunnelConnection {
        val connectCount = AtomicInteger(0)
        val closedStreams = AtomicInteger(0)

        override fun connect(): TunnelConnection.StreamPair? {
            connectCount.incrementAndGet()
            if (connectDelay > 0) Thread.sleep(connectDelay)
            if (!shouldSucceed) return null
            return TunnelConnection.StreamPair(
                inputStream = ByteArrayInputStream(inputData),
                outputStream = ByteArrayOutputStream(),
                onClose = { closedStreams.incrementAndGet() }
            )
        }
    }

    private class FakeTunnelFactory(
        private val shouldSucceed: Boolean = true,
        private val connectDelay: Long = 0
    ) : TunnelPool.TunnelFactory {
        val created = AtomicInteger(0)
        override fun create(): TunnelConnection {
            created.incrementAndGet()
            return FakeTunnel(shouldSucceed, connectDelay = connectDelay)
        }
    }

    // ---- Pool creation ----

    @Test
    fun `pool starts with zero connections`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 4)
        assertEquals(0, pool.activeCount)
        pool.shutdown()
    }

    // ---- Acquire ----

    @Test
    fun `acquire returns a stream pair`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 4)

        val stream = pool.acquire(timeoutMs = 2000)
        assertNotNull(stream)

        stream!!.close()
        pool.shutdown()
    }

    @Test
    fun `acquire creates tunnel on first call`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 4)

        pool.acquire(timeoutMs = 2000)?.close()
        assertTrue(factory.created.get() > 0)

        pool.shutdown()
    }

    @Test
    fun `acquire returns null when factory fails`() {
        val factory = FakeTunnelFactory(shouldSucceed = false)
        val pool = TunnelPool(factory, poolSize = 2)

        val stream = pool.acquire(timeoutMs = 500)
        assertNull(stream)

        pool.shutdown()
    }

    // ---- Pool size ----

    @Test
    fun `pool respects max size limit`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 2)

        // Acquire 2 streams
        val s1 = pool.acquire(timeoutMs = 2000)
        val s2 = pool.acquire(timeoutMs = 2000)
        assertNotNull(s1)
        assertNotNull(s2)
        assertTrue(pool.activeCount <= 2)

        s1?.close()
        s2?.close()
        pool.shutdown()
    }

    // ---- Concurrent access ----

    @Test
    fun `pool handles concurrent acquires`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 8)
        val acquired = AtomicInteger(0)
        val latch = CountDownLatch(10)

        val threads = (1..10).map {
            Thread {
                try {
                    val stream = pool.acquire(timeoutMs = 5000)
                    if (stream != null) {
                        acquired.incrementAndGet()
                        Thread.sleep(50)
                        stream.close()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue("Should have acquired some connections", acquired.get() > 0)

        pool.shutdown()
    }

    // ---- Shutdown ----

    @Test
    fun `shutdown prevents new acquires`() {
        val factory = FakeTunnelFactory()
        val pool = TunnelPool(factory, poolSize = 4)

        pool.acquire(timeoutMs = 1000)?.close()
        pool.shutdown()

        val stream = pool.acquire(timeoutMs = 500)
        assertNull(stream)
    }

    // ---- Failover ----

    @Test
    fun `pool retries with different factory on failure`() {
        val callCount = AtomicInteger(0)
        val failFirst = object : TunnelPool.TunnelFactory {
            override fun create(): TunnelConnection {
                val n = callCount.incrementAndGet()
                return FakeTunnel(shouldSucceed = n > 1) // first call fails
            }
        }
        val pool = TunnelPool(failFirst, poolSize = 4, maxRetries = 3)

        val stream = pool.acquire(timeoutMs = 5000)
        assertNotNull(stream)
        assertTrue(callCount.get() >= 2) // retried at least once

        stream?.close()
        pool.shutdown()
    }

    @Test
    fun `pool gives up after maxRetries`() {
        val factory = FakeTunnelFactory(shouldSucceed = false)
        val pool = TunnelPool(factory, poolSize = 2, maxRetries = 3)

        val stream = pool.acquire(timeoutMs = 2000)
        assertNull(stream)
        assertTrue(factory.created.get() <= 3)

        pool.shutdown()
    }
}
