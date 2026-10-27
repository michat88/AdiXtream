package com.lagradost.cloudstream3

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PromoClaimIntentFileStoreTest {
    private val directory = Files.createTempDirectory("adixtream-promo-store-test-").toFile()
    private val intent = PendingPromoClaim("11111111", "ALPHA", "request_0000000001")
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `real disk roundtrip survives a new store instance`() {
        val store = PromoClaimIntentFileStore(directory)
        assertNull(store.read()); assertTrue(store.compareAndSet(null, intent))
        assertEquals(intent, PromoClaimIntentFileStore(directory).read())
        assertTrue(store.compareAndSet(intent, intent.copy(dispatched = true)))
        assertTrue(PromoClaimIntentFileStore(directory).read()!!.dispatched)
    }

    @Test fun `compare and set cannot replace newer disk state with a stale intent`() {
        val store = PromoClaimIntentFileStore(directory)
        val newer = intent.copy(idempotencyKey = "request_0000000002")
        assertTrue(store.compareAndSet(null, newer))
        assertFalse(store.compareAndSet(intent, null)); assertEquals(newer, store.read())
    }

    @Test fun `corrupt state is preserved and never treated as empty`() {
        val file = File(directory, "adixtream-promo-intent-v1.json").apply { writeText("synthetic-corrupt-data") }
        val store = PromoClaimIntentFileStore(directory)
        for (operation in listOf<() -> Any?>({ store.read() }, { store.compareAndSet(null, intent) })) {
            try { operation(); fail("must reject corrupt state") } catch (e: IOException) {
                assertEquals("PROMO_INTENT_STORAGE_UNAVAILABLE", e.message)
                assertFalse(e.message!!.contains(directory.path))
            }
        }
        assertEquals("synthetic-corrupt-data", file.readText())
    }

    @Test fun `invalid destination produces safe failure without replacing original file`() {
        val file = File(directory, "not-a-directory").apply { writeText("preserve") }
        try { PromoClaimIntentFileStore(file).compareAndSet(null, intent); fail("must reject") }
        catch (e: IOException) { assertEquals("PROMO_INTENT_STORAGE_UNAVAILABLE", e.message) }
        assertEquals("preserve", file.readText())
    }

    @Test fun `two concurrent store instances permit only one initial intent`() {
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val results = (1..2).map { index -> pool.submit<Boolean> {
                gate.await()
                PromoClaimIntentFileStore(directory).compareAndSet(null, intent.copy(idempotencyKey = "request_000000000$index"))
            } }
            gate.countDown()
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
            assertNotNull(PromoClaimIntentFileStore(directory).read())
        } finally { pool.shutdownNow() }
    }

    @Test fun `incomplete temporary write does not erase previous committed intent`() {
        val store = PromoClaimIntentFileStore(directory)
        assertTrue(store.compareAndSet(null, intent))
        File(directory, "promo-intent-interrupted.tmp").writeText("incomplete")
        assertEquals(intent, PromoClaimIntentFileStore(directory).read())
        assertTrue(store.compareAndSet(intent, null)); assertNull(store.read())
    }
}
