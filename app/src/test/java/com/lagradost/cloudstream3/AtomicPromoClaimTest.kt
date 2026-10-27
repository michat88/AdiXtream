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

class AtomicPromoClaimTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_793_102_400_000L
    private val day = 86_400_000L
    private val device = "11111111"
    private val initialExpiry = now + 10 * day
    private val saved = mutableListOf<PremiumLicense>()
    private inner class Transport : AtomicPromoTransport {
        var user = JSONObject().put("status", "aktif").put("expired_at", initialExpiry)
        var promo = JSONObject().put("status", "aktif").put("days", 3).put("used_count", 0)
            .put("max_quota", 10).put("valid_until", now + 20 * day).put("expired_at", now + 20 * day)
        val writes = mutableListOf<JSONObject>()
        var users = 0
        var promos = 0
        var beforeUser: suspend () -> Unit = {}
        var beforeCommit: suspend () -> Unit = {}
        var afterCommit: suspend () -> Unit = {}
        var readStatus = 200
        var writeStatus = 200
        var serverTime = now
        var nullUser = false
        override suspend fun user(deviceId: String): AtomicPromoResponse {
            users++; beforeUser()
            return AtomicPromoResponse(readStatus, if (nullUser) "null" else user.toString(), serverTime)
        }
        override suspend fun promo(code: String): AtomicPromoResponse {
            promos++
            return AtomicPromoResponse(200, promo.toString(), serverTime)
        }
        override suspend fun commit(patch: String): AtomicPromoResponse {
            val json = JSONObject(patch); writes += json; beforeCommit()
            if (writeStatus in 200..299) {
                val expiry = json.get("users/$device/expired_at")
                user.put("expired_at", if (expiry is JSONObject)
                    user.getLong("expired_at") + expiry.getJSONObject(".sv").getLong("increment") else expiry)
                user.put("redeemed_promos", JSONObject().put("ALPHA", true))
                if (nullUser) { user.remove("status"); nullUser = false }
            }
            val status = writeStatus
            afterCommit()
            return AtomicPromoResponse(status, "{}", now)
        }
    }
    @After fun close() { scope.cancel() }
    private fun client(t: Transport, persist: Boolean = true) = AtomicPromoClaim(scope, t) { license, _ -> saved += license; persist }

    @Test fun `active path sends one patch and saves only the verified server expiry`() = runBlocking {
        val t = Transport()
        assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, " alpha ").status)
        assertEquals(1, t.writes.size)
        val patch = t.writes.single()
        assertEquals(4, patch.length())
        assertTrue(patch.getBoolean("users/$device/redeemed_promos/ALPHA"))
        assertEquals(1L, patch.getLong("promo_codes/ALPHA/used_count"))
        assertEquals(3 * day, patch.getJSONObject("users/$device/expired_at").getJSONObject(".sv").getLong("increment"))
        assertEquals(initialExpiry + 3 * day, saved.single().expiresAt)
        assertEquals(now, saved.single().serverTime)
    }

    @Test fun `generated payload matches fixture exercised by the real emulator`() = runBlocking {
        val fixture = javaClass.classLoader!!.getResourceAsStream("adixtream-atomic-promo-v1.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val t = Transport()
        assertEquals(now, fixture.getLong("now"))
        t.user = fixture.getJSONObject("user"); t.promo = fixture.getJSONObject("promo")
        client(t).submit(device, "ALPHA")
        assertEquals(fixture.getJSONObject("patch").toString(), t.writes.single().toString())
    }

    @Test fun `failure before commit never consumes a marker or changes local license`() = runBlocking {
        val t = Transport().apply { beforeUser = { throw IOException("offline") } }
        assertEquals(AtomicPromoStatus.NETWORK, client(t).submit(device, "ALPHA").status)
        assertTrue(t.writes.isEmpty()); assertTrue(saved.isEmpty())
    }

    @Test fun `lost response after atomic commit is read back without another write`() = runBlocking {
        val t = Transport().apply { afterCommit = { throw IOException("reply lost") } }
        assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
        assertEquals(1, t.writes.size)
        assertEquals(initialExpiry + 3 * day, saved.single().expiresAt)
    }

    @Test fun `unknown result and offline verification neither retries nor fabricates premium`() = runBlocking {
        val t = Transport().apply { afterCommit = {
            beforeUser = { throw IOException("offline") }; throw IOException("reply lost")
        } }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertEquals(1, t.writes.size); assertTrue(saved.isEmpty())
    }

    @Test fun `uncommitted unknown write does not turn into already claimed`() = runBlocking {
        val t = Transport().apply { beforeCommit = { throw IOException("not delivered") } }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertFalse(t.user.has("redeemed_promos")); assertTrue(saved.isEmpty())
        assertEquals(1, t.writes.size)
    }

    @Test fun `server error with no commit stays unknown without replaying a write`() = runBlocking {
        val t = Transport().apply { writeStatus = 503 }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertEquals(1, t.writes.size); assertTrue(saved.isEmpty())
    }

    @Test fun `concurrent same code requests share the whole operation`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val t = Transport().apply { beforeCommit = { entered.complete(Unit); release.await() } }
        val c = client(t)
        val first = async { c.submit(device, "ALPHA") }
        withTimeout(3000) { entered.await() }
        val other = (1..20).map { async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { c.submit(device, "ALPHA") } }
        release.complete(Unit)
        assertEquals(AtomicPromoStatus.APPLIED, first.await().status)
        assertTrue(other.awaitAll().all { it.status == AtomicPromoStatus.APPLIED })
        assertEquals(1, t.writes.size); assertEquals(1, saved.size)
    }

    @Test fun `different code cannot overlap a pending claim`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val t = Transport().apply { beforeCommit = { entered.complete(Unit); release.await() } }
        val c = client(t); val first = async { c.submit(device, "ALPHA") }
        withTimeout(3000) { entered.await() }
        assertEquals(AtomicPromoStatus.BUSY, c.submit(device, "BETA").status)
        release.complete(Unit); first.await()
        assertEquals(1, t.promos); assertEquals(1, t.writes.size)
    }

    @Test fun `screen cancellation does not stop verification and license persistence`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val applied = CompletableDeferred<Unit>()
        val t = Transport().apply { beforeCommit = { entered.complete(Unit); release.await() } }
        val c = AtomicPromoClaim(scope, t) { license, _ -> saved += license; applied.complete(Unit); true }
        val screen = async { c.submit(device, "ALPHA") }
        withTimeout(3000) { entered.await() }; screen.cancelAndJoin(); release.complete(Unit)
        withTimeout(3000) { applied.await() }
        assertEquals(1, t.writes.size); assertEquals(initialExpiry + 3 * day, saved.single().expiresAt)
    }

    @Test fun `old partial marker is not deleted or automatically given a second bonus`() = runBlocking {
        val t = Transport().apply { user.put("redeemed_promos", JSONObject().put("ALPHA", true)) }
        assertEquals(AtomicPromoStatus.ALREADY_RECORDED, client(t).submit(device, "ALPHA").status)
        assertTrue(t.writes.isEmpty()); assertEquals(initialExpiry, saved.single().expiresAt)
    }

    @Test fun `banned user cannot send a claim and stays banned locally`() = runBlocking {
        val t = Transport().apply { user.put("status", "banned") }
        assertEquals(AtomicPromoStatus.BANNED, client(t).submit(device, "ALPHA").status)
        assertTrue(t.writes.isEmpty()); assertEquals(PremiumLicenseState.BANNED, saved.single().state)
    }

    @Test fun `ban observed after commit is never saved as premium`() = runBlocking {
        val t = Transport().apply { afterCommit = { user.put("status", "banned") } }
        assertEquals(AtomicPromoStatus.BANNED, client(t).submit(device, "ALPHA").status)
        assertEquals(PremiumLicenseState.BANNED, saved.single().state)
    }

    @Test fun `compatible numeric admin renewal during commit is preserved`() = runBlocking {
        val t = Transport().apply { beforeCommit = { user.put("expired_at", now + 40 * day) } }
        assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
        assertEquals(now + 43 * day, saved.single().expiresAt)
    }

    @Test fun `expired free and absent users start bonus from server clock`() = runBlocking {
        for (expiry in listOf<Any?>(0L, now - day, null)) {
            val t = Transport().apply { if (expiry == null) nullUser = true else user.put("expired_at", expiry) }
            assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
            assertEquals(now + 3 * day, t.writes.single().getLong("users/$device/expired_at"))
        }
    }

    @Test fun `legacy second and ISO expiries remain compatible without rewriting other fields`() = runBlocking {
        for (expiry in listOf<Any>(initialExpiry / 1000L, (initialExpiry / 1000L).toString(), "2026-11-06T12:00:00.000Z")) {
            val t = Transport().apply { user.put("expired_at", expiry) }
            assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
            assertEquals(initialExpiry + 3 * day, t.writes.single().getLong("users/$device/expired_at"))
        }
    }

    @Test fun `stale counter rejection refreshes all data before a bounded retry`() = runBlocking {
        val t = Transport().apply {
            writeStatus = 401
            afterCommit = { writeStatus = 200; promo.put("used_count", 1) }
        }
        // The first rejection must not be changed to success by the test hook itself.
        var attempts = 0
        t.beforeCommit = { if (++attempts == 1) t.writeStatus = 401 else t.writeStatus = 200 }
        assertEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
        assertEquals(2, t.writes.size)
        assertEquals(2L, t.writes.last().getLong("promo_codes/ALPHA/used_count"))
    }

    @Test fun `denial never gets mislabeled as an already claimed promo`() = runBlocking {
        val t = Transport().apply { writeStatus = 401 }
        assertEquals(AtomicPromoStatus.DENIED, client(t).submit(device, "ALPHA").status)
        assertEquals(2, t.writes.size); assertTrue(saved.isEmpty())
    }

    @Test fun `invalid dates aliases quotas days and unsafe expiry never send a write`() = runBlocking {
        val cases: List<(Transport) -> Unit> = listOf(
            { it.promo.put("valid_until", now - day); it.promo.put("expired_at", now - day) },
            { it.promo.put("expired_at", now + day) },
            { it.promo.put("days", 0) }, { it.promo.put("days", 36_501) },
            { it.promo.put("days", "3") }, { it.promo.put("days", 1.5) },
            { it.promo.put("used_count", 10) }, { it.promo.put("status", "nonaktif") },
            { it.user.put("expired_at", "broken") }, { it.user.put("expired_at", 1e24) },
            { it.user.put("expired_at", 9_007_199_254_740_991L) },
            { it.serverTime = 0 }, { it.user.put("status", "unknown") },
            { it.user.remove("expired_at"); it.user.put("code", "synthetic") },
        )
        for (change in cases) {
            val t = Transport(); change(t)
            assertNotEquals(AtomicPromoStatus.APPLIED, client(t).submit(device, "ALPHA").status)
            assertTrue(t.writes.isEmpty())
        }
        assertTrue(saved.isEmpty())
    }

    @Test fun `invalid input cannot become a REST path`() = runBlocking {
        val t = Transport(); val c = client(t)
        for (code in listOf("", "../ALPHA", "A?auth=x", "A#B", "A/B")) {
            assertEquals(AtomicPromoStatus.INVALID, c.submit(device, code).status)
        }
        assertEquals(AtomicPromoStatus.INVALID, c.submit("../user", "ALPHA").status)
        assertEquals(0, t.users); assertTrue(t.writes.isEmpty())
    }

    @Test fun `local storage failure keeps server claim and reports recovery needed`() = runBlocking {
        val t = Transport()
        assertEquals(AtomicPromoStatus.LOCAL_SYNC, client(t, false).submit(device, "ALPHA").status)
        assertEquals(1, t.writes.size); assertTrue(t.user.getJSONObject("redeemed_promos").getBoolean("ALPHA"))
    }

    @Test fun `successful status alone cannot grant without matching marker and expiry`() = runBlocking {
        val t = Transport().apply { afterCommit = { user.remove("redeemed_promos") } }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertTrue(saved.isEmpty())
    }

    @Test fun `marker without a delivered bonus cannot be reported as success`() = runBlocking {
        val t = Transport().apply { afterCommit = { user.put("expired_at", initialExpiry) } }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertTrue(saved.isEmpty())
    }

    @Test fun `verification response without a server clock cannot update local premium`() = runBlocking {
        val t = Transport().apply { afterCommit = { serverTime = 0L } }
        assertEquals(AtomicPromoStatus.UNKNOWN, client(t).submit(device, "ALPHA").status)
        assertTrue(saved.isEmpty())
    }

    @Test fun `verification revision is captured before the GET rather than after it`() = runBlocking {
        var revision = 7L
        val t = Transport().apply { beforeUser = { if (users == 2) revision = 8L } }
        val c = AtomicPromoClaim(scope, t, readRevision = { revision }) { license, observed ->
            if (observed != revision) false else { saved += license; true }
        }
        assertEquals(AtomicPromoStatus.LOCAL_SYNC, c.submit(device, "ALPHA").status)
        assertTrue(saved.isEmpty()); assertEquals(1, t.writes.size)
    }
}
