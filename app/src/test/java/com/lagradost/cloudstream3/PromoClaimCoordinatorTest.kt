package com.lagradost.cloudstream3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PromoClaimCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = "request_0000000001"
    private val device = "11111111"
    private class Disk(var value: PendingPromoClaim? = null) : PromoClaimIntentStore {
        var failWriteAt = -1
        var unreadable = false
        var writes = 0
        @Synchronized override fun read(): PendingPromoClaim? {
            if (unreadable) throw IOException("synthetic storage unavailable")
            return value
        }
        @Synchronized override fun compareAndSet(expected: PendingPromoClaim?, next: PendingPromoClaim?): Boolean {
            writes++
            if (writes == failWriteAt || expected != value) return false
            value = next
            return true
        }
    }
    private class Transport : PromoClaimTransport {
        val claims = mutableListOf<PendingPromoClaim>()
        val lookups = mutableListOf<PendingPromoClaim>()
        var onClaim: suspend (PendingPromoClaim) -> PromoHttpReply = { throw IOException("synthetic offline") }
        var onLookup: suspend (PendingPromoClaim) -> PromoHttpReply = { throw IOException("synthetic offline") }
        override suspend fun claim(intent: PendingPromoClaim): PromoHttpReply { claims += intent; return onClaim(intent) }
        override suspend fun lookup(intent: PendingPromoClaim): PromoHttpReply { lookups += intent; return onLookup(intent) }
    }
    @After fun close() { scope.cancel() }
    private fun manager(disk: Disk, transport: Transport, newKey: () -> String = { key }) =
        PromoClaimCoordinator(scope, disk, transport, newKey)
    private fun success(banned: Boolean = false): PromoHttpReply {
        val samples = javaClass.classLoader!!.getResourceAsStream("adixtream-promo-contract-v1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("responses") }
        return PromoHttpReply(200, samples.getJSONObject(if (banned) 1 else 0).getJSONObject("body").toString())
    }
    private fun error(code: String, status: Int) = PromoHttpReply(status,
        JSONObject().put("contractVersion", 1).put("outcome", code).put("retryable", status == 503).toString())

    @Test fun `intent and dispatched marker are committed before first network call`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = { intent ->
            assertEquals(intent, disk.value)
            assertTrue(intent.dispatched)
            assertEquals(2, disk.writes)
            success()
        } }
        assertTrue(manager(disk, transport).submit(device, " alpha ") is PromoClaimResult.Completed)
        assertEquals("ALPHA", disk.value!!.promoCode)
    }

    @Test fun `failed initial persistence sends no request`() = runBlocking {
        val disk = Disk().apply { failWriteAt = 1 }; val transport = Transport()
        assertEquals(PromoClaimResult.Pending("INTENT_NOT_PERSISTED"), manager(disk, transport).submit(device, "ALPHA"))
        assertTrue(transport.claims.isEmpty()); assertTrue(transport.lookups.isEmpty())
    }

    @Test fun `failed dispatched persistence also sends no request and retains key`() = runBlocking {
        val disk = Disk().apply { failWriteAt = 2 }; val transport = Transport()
        val first = manager(disk, transport)
        assertEquals(PromoClaimResult.Pending("INTENT_NOT_PERSISTED"), first.submit(device, "ALPHA"))
        assertFalse(disk.value!!.dispatched)
        assertEquals(key, disk.value!!.idempotencyKey)
        assertTrue(transport.claims.isEmpty())
    }

    @Test fun `timeout preserves dispatched intent instead of clearing or changing it`() = runBlocking {
        val disk = Disk(); val transport = Transport()
        assertEquals(PromoClaimResult.Pending("UNKNOWN_RESULT"), manager(disk, transport).submit(device, "ALPHA"))
        assertTrue(disk.value!!.dispatched)
        assertEquals(key, disk.value!!.idempotencyKey)
    }

    @Test fun `restart after uncertain commit performs receipt lookup with original key`() = runBlocking {
        val disk = Disk(); val firstTransport = Transport()
        manager(disk, firstTransport).submit(device, "ALPHA")
        val transport = Transport().apply { onLookup = { success() } }
        val result = manager(disk, transport) { error("must not generate new intent") }.submit(device, "ALPHA")
        assertTrue(result is PromoClaimResult.Completed)
        assertEquals(key, transport.lookups.single().idempotencyKey)
        assertTrue(transport.claims.isEmpty())
    }

    @Test fun `confirmed unknown lookup retries claim with the same stored key`() = runBlocking {
        val disk = Disk(PendingPromoClaim(device, "ALPHA", key, true))
        val transport = Transport().apply {
            onLookup = { error("UNKNOWN_RESULT", 503) }
            onClaim = { success() }
        }
        assertTrue(manager(disk, transport).submit(device, "ALPHA") is PromoClaimResult.Completed)
        assertEquals(key, transport.lookups.single().idempotencyKey)
        assertEquals(key, transport.claims.single().idempotencyKey)
    }

    @Test fun `lookup network failure or malformed response does not blindly send another claim`() = runBlocking {
        for (malformed in listOf(false, true)) {
            val disk = Disk(PendingPromoClaim(device, "ALPHA", key, true))
            val transport = Transport().apply { if (malformed) onLookup = { PromoHttpReply(200, "broken") } }
            assertEquals(PromoClaimResult.Pending("UNKNOWN_RESULT"), manager(disk, transport).submit(device, "ALPHA"))
            assertTrue(transport.claims.isEmpty()); assertNotNull(disk.value)
        }
    }

    @Test fun `parallel submissions share one app scoped request`() = runBlocking {
        val disk = Disk(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val generated = AtomicInteger()
        val transport = Transport().apply { onClaim = { started.complete(Unit); release.await(); success() } }
        val coordinator = manager(disk, transport) { generated.incrementAndGet(); key }
        val requests = List(20) { async { coordinator.submit(device, "ALPHA") } }
        withTimeout(1000) { started.await() }
        release.complete(Unit)
        assertTrue(requests.awaitAll().all { it is PromoClaimResult.Completed })
        assertEquals(1, transport.claims.size); assertEquals(1, generated.get())
    }

    @Test fun `screen cancellation does not cancel shared request or lose its intent`() = runBlocking {
        val disk = Disk(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            onClaim = { started.complete(Unit); release.await(); success() }
            onLookup = { success() }
        }
        val coordinator = manager(disk, transport)
        val waiter = async { coordinator.submit(device, "ALPHA") }
        withTimeout(1000) { started.await() }; waiter.cancelAndJoin(); release.complete(Unit)
        assertTrue(withTimeout(1000) { coordinator.submit(device, "ALPHA") } is PromoClaimResult.Completed)
        assertEquals(1, transport.claims.size); assertNotNull(disk.value)
    }

    @Test fun `different pending promo cannot replace an unresolved intent`() = runBlocking {
        val original = PendingPromoClaim(device, "OLDPROMO", key, true)
        val disk = Disk(original); val transport = Transport()
        assertEquals(PromoClaimResult.Pending("ANOTHER_CLAIM_PENDING"), manager(disk, transport).submit(device, "ALPHA"))
        assertEquals(original, disk.value); assertTrue(transport.claims.isEmpty()); assertTrue(transport.lookups.isEmpty())
    }

    @Test fun `different input during an active claim cannot start competing network work`() = runBlocking {
        val disk = Disk(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val transport = Transport().apply { onClaim = { started.complete(Unit); release.await(); success() } }
        val coordinator = manager(disk, transport)
        val first = async { coordinator.submit(device, "ALPHA") }
        withTimeout(1000) { started.await() }
        assertEquals(PromoClaimResult.Pending("ANOTHER_CLAIM_PENDING"), coordinator.submit(device, "BETA"))
        release.complete(Unit); first.await(); assertEquals(1, transport.claims.size)
    }

    @Test fun `auth failure remains pending with original intent`() = runBlocking {
        for ((code, status) in listOf("AUTH_REQUIRED" to 401, "OWNERSHIP_REQUIRED" to 403)) {
            val disk = Disk(); val transport = Transport().apply { onClaim = { error(code, status) } }
            assertEquals(PromoClaimResult.Pending(code), manager(disk, transport).submit(device, "ALPHA"))
            assertEquals(key, disk.value!!.idempotencyKey)
        }
    }

    @Test fun `banned current result is delivered without treating old receipt as premium`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = { success(banned = true) } }
        val result = manager(disk, transport).submit(device, "ALPHA") as PromoClaimResult.Completed
        assertEquals("BANNED", result.reply.currentState)
        assertNotNull(disk.value)
    }

    @Test fun `unresolved current entitlement preserves intent even with an applied receipt`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = {
            val json = JSONObject(success().body).apply {
                getJSONObject("currentEntitlement").put("state", "UNKNOWN").put("expiresAt", JSONObject.NULL)
            }
            PromoHttpReply(200, json.toString())
        } }
        assertEquals(PromoClaimResult.Pending("ENTITLEMENT_UNRESOLVED"), manager(disk, transport).submit(device, "ALPHA"))
        assertNotNull(disk.value)
    }

    @Test fun `definite legacy rejection is separate from network uncertainty`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = { error("LEGACY_RECONCILIATION_REQUIRED", 409) } }
        val result = manager(disk, transport).submit(device, "ALPHA") as PromoClaimResult.Rejected
        assertEquals("LEGACY_RECONCILIATION_REQUIRED", result.code)
        assertNotNull(disk.value)
    }

    @Test fun `completed result is not cleared until explicitly acknowledged after handling`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = { success() } }; val coordinator = manager(disk, transport)
        val result = coordinator.submit(device, "ALPHA")
        assertNotNull(disk.value)
        assertTrue(coordinator.acknowledge(result)); assertNull(disk.value)
        assertFalse(coordinator.acknowledge(result))
    }

    @Test fun `stale acknowledge cannot remove a newer intent`() = runBlocking {
        val disk = Disk(); val transport = Transport().apply { onClaim = { success() } }; val coordinator = manager(disk, transport)
        val result = coordinator.submit(device, "ALPHA")
        val newer = PendingPromoClaim(device, "BETA", "request_0000000002", true)
        disk.value = newer
        assertFalse(coordinator.acknowledge(result)); assertEquals(newer, disk.value)
        assertFalse(coordinator.acknowledge(PromoClaimResult.Pending("UNKNOWN_RESULT")))
    }

    @Test fun `corrupt storage invalid input or invalid key cannot trigger a network request`() = runBlocking {
        val disk = Disk().apply { unreadable = true }; val transport = Transport()
        assertEquals(PromoClaimResult.Pending("INTENT_STORAGE_UNAVAILABLE"), manager(disk, transport).submit(device, "ALPHA"))
        assertEquals(PromoClaimResult.Pending("REQUEST_INVALID"), manager(Disk(), transport).submit("../users", "ALPHA"))
        assertEquals(PromoClaimResult.Pending("INTENT_NOT_PERSISTED"), manager(Disk(), transport) { "short" }.submit(device, "ALPHA"))
        assertTrue(transport.claims.isEmpty()); assertTrue(transport.lookups.isEmpty())
    }
}
