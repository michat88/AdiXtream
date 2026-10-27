package com.lagradost.cloudstream3

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AtomicPromoRestTransportTest {
    private val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@AtomicPromoRestTransportTest.executor
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/"
    @After fun close() { server.stop(0); executor.shutdownNow() }
    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray(); sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test fun `real HTTP uses existing paths and one root PATCH override with no credentials`() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        server.createContext("/") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestURI.rawQuery)
            assertEquals("no-cache", exchange.requestHeaders.getFirst("Cache-Control"))
            calls += Triple(exchange.requestMethod, exchange.requestURI.path,
                exchange.requestBody.bufferedReader().use { it.readText() })
            if (exchange.requestMethod == "POST") assertEquals("PATCH", exchange.requestHeaders.getFirst("X-HTTP-Method-Override"))
            exchange.reply(200, "{}")
        }
        val t = AtomicPromoRestTransport(base)
        assertEquals(200, t.user("11111111").status)
        assertTrue(t.promo("ALPHA").serverTime > 0L)
        assertEquals(200, t.commit("{\"synthetic\":true}").status)
        assertEquals(listOf("GET", "GET", "POST"), calls.map { it.first })
        assertEquals(listOf("/users/11111111.json", "/promo_codes/ALPHA.json", "/.json"), calls.map { it.second })
        assertEquals("{\"synthetic\":true}", calls.last().third)
    }

    @Test fun `redirects are returned without following a different endpoint`() = runBlocking {
        val followed = AtomicInteger()
        server.createContext("/users/") {
            it.responseHeaders.set("Location", base + "redirect-target")
            it.reply(302, "redirect")
        }
        server.createContext("/redirect-target") { followed.incrementAndGet(); it.reply(200, "{}") }
        assertEquals(302, AtomicPromoRestTransport(base).user("11111111").status)
        assertEquals(0, followed.get())
    }

    @Test fun `lost connection after receiving request body is not automatically replayed`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/") {
            it.requestBody.use { body -> body.readBytes() }; calls.incrementAndGet(); it.close()
        }
        try { AtomicPromoRestTransport(base).commit("{\"synthetic\":true}"); fail("Expected lost reply") }
        catch (_: IOException) { /* caller must verify through GET instead of replaying */ }
        assertEquals(1, calls.get())
    }

    @Test fun `503 with immediate retry hint cannot replay a mutating request`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/") {
            it.requestBody.use { body -> body.readBytes() }; calls.incrementAndGet()
            it.responseHeaders.set("Retry-After", "0")
            it.reply(503, "{}")
        }
        assertEquals(503, AtomicPromoRestTransport(base).commit("{\"synthetic\":true}").status)
        assertEquals(1, calls.get())
    }

    @Test fun `stalled body terminates within the bounded network deadline`() = runBlocking {
        val release = CountDownLatch(1)
        server.createContext("/") {
            it.sendResponseHeaders(200, 100)
            try { release.await() } finally { it.close() }
        }
        val started = System.nanoTime()
        try {
            try { AtomicPromoRestTransport(base).user("11111111"); fail("Expected read timeout") }
            catch (_: IOException) { }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 8_000)
        } finally { release.countDown() }
    }

    @Test fun `oversized body is rejected without parsing or returning customer data`() = runBlocking {
        server.createContext("/") { it.reply(200, "x".repeat(256 * 1024 + 1)) }
        try { AtomicPromoRestTransport(base).user("11111111"); fail("Expected body limit") }
        catch (failure: IOException) { assertEquals("PROMO_RESPONSE_TOO_LARGE", failure.message) }
    }

    @Test fun `production transport refuses cleartext remote host and embedded credentials`() {
        for (url in listOf("http://example.invalid/", "https://user:password@example.invalid/",
            "https://example.invalid/?auth=synthetic", "https://example.invalid/#synthetic")) {
            try { AtomicPromoRestTransport(url); fail("Expected rejected transport configuration") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
