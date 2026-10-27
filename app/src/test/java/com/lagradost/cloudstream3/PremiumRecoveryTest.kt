package com.lagradost.cloudstream3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PremiumRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_900_000_000_000L
    private val day = 86_400_000L

    @After fun close() { scope.cancel() }

    private class Disk(var license: PremiumLicense?, var writable: Boolean = true) {
        val writes = AtomicInteger()
        fun save(value: PremiumLicense): Boolean {
            writes.incrementAndGet()
            if (writable) license = value
            return writable
        }
    }

    private fun recovery(
        disk: Disk,
        elapsed: () -> Long = { 100L },
        wallClock: () -> Long = { now },
        fetch: suspend () -> PremiumLicense?,
    ) = PremiumRecovery(scope, { disk.license }, disk::save, fetch, wallClock, elapsed)

    private fun premium(expiry: Long = now + day, version: Long = 0L) =
        PremiumLicense(PremiumLicenseState.PREMIUM, expiry, version)

    @Test fun `expired cache recovers a server renewal without clearing it first`() = runBlocking {
        val old = premium(now - day)
        val disk = Disk(old)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = recovery(disk) { started.complete(Unit); release.await(); premium(now + 30 * day) }
        val pending = async { manager.resolveStartup() }
        started.await()
        assertEquals(old, disk.license)
        assertEquals(0, disk.writes.get())
        assertFalse(manager.hasResolvedLicense())
        release.complete(Unit)
        val result = pending.await()
        assertEquals(now + 30 * day, result.license!!.expiresAt)
        assertTrue(result.persisted)
        assertTrue(manager.cached()!!.grantsPremium(now))
    }

    @Test fun `stored free is not terminal when server is premium`() = runBlocking {
        val disk = Disk(PremiumLicense(PremiumLicenseState.FREE, 0L))
        val result = recovery(disk) { premium() }.resolveStartup()
        assertEquals(PremiumLicenseState.PREMIUM, result.license!!.state)
        assertTrue(result.persisted)
    }

    @Test fun `clear data recovers premium using server result without a local license`() = runBlocking {
        val disk = Disk(null)
        val result = recovery(disk) { premium() }.resolveStartup()
        assertTrue(result.license!!.grantsPremium(now))
        assertEquals(result.license, disk.license)
    }

    @Test fun `valid cached premium does not block startup on network`() = runBlocking {
        val disk = Disk(premium())
        val calls = AtomicInteger()
        val manager = recovery(disk) { calls.incrementAndGet(); CompletableDeferred<PremiumLicense>().await() }
        val result = withTimeout(1_000) { manager.resolveStartup() }
        assertTrue(result.license!!.grantsPremium(now))
        assertEquals(0, calls.get())
    }

    @Test fun `offline recovery preserves expired data and remains unresolved`() = runBlocking {
        val old = premium(now - day)
        val disk = Disk(old)
        val manager = recovery(disk) { throw java.io.IOException("synthetic offline") }
        val result = manager.resolveStartup()
        assertNull(result.license)
        assertEquals(old, disk.license)
        assertEquals(old, manager.cached())
        assertEquals(0, disk.writes.get())
    }

    @Test fun `failed recovery after clear data does not persist free`() = runBlocking {
        val disk = Disk(null)
        assertNull(recovery(disk) { null }.resolveStartup().license)
        assertNull(disk.license)
        assertEquals(0, disk.writes.get())
    }

    @Test fun `failed forced refresh preserves a valid cached grant`() = runBlocking {
        val disk = Disk(premium())
        val manager = recovery(disk) { null }
        assertNull(manager.refresh(force = true).license)
        assertTrue(manager.cached()!!.grantsPremium(manager.currentTimeMillis()))
        assertEquals(0, disk.writes.get())
    }

    @Test fun `simultaneous recovery callers share one fetch`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = recovery(Disk(null)) { calls.incrementAndGet(); started.complete(Unit); release.await(); premium() }
        val jobs = List(20) { async { manager.refresh(force = true) } }
        started.await()
        // All requests have been launched before the blocking response is released.
        kotlinx.coroutines.yield()
        release.complete(Unit)
        assertTrue(jobs.awaitAll().all { it.license!!.grantsPremium(now) })
        assertEquals(1, calls.get())
    }

    @Test fun `background polling is throttled after failure and retries after interval`() = runBlocking {
        var elapsed = 0L
        val calls = AtomicInteger()
        val manager = recovery(Disk(null), elapsed = { elapsed }) { calls.incrementAndGet(); null }
        manager.refresh()
        repeat(10_000) { manager.requestRefresh() }
        manager.refresh()
        assertEquals(1, calls.get())
        elapsed = 5 * 60 * 1000L
        manager.refresh()
        assertEquals(2, calls.get())
    }

    @Test fun `cancelling one screen does not cancel shared recovery for its replacement`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disk = Disk(null)
        val manager = recovery(disk) { calls.incrementAndGet(); started.complete(Unit); release.await(); premium() }
        val oldScreen = async { manager.refresh(force = true) }
        started.await()
        oldScreen.cancel()
        val newScreen = async(start = CoroutineStart.UNDISPATCHED) { manager.refresh(force = true) }
        release.complete(Unit)
        assertTrue(newScreen.await().persisted)
        assertTrue(disk.license!!.grantsPremium(now))
        assertEquals(1, calls.get())
    }

    @Test fun `explicit startup retry is not trapped by a failed background throttle`() = runBlocking {
        val calls = AtomicInteger()
        val manager = recovery(Disk(null)) { if (calls.incrementAndGet() == 1) null else premium() }
        assertNull(manager.resolveStartup().license)
        assertTrue(manager.resolveStartup().license!!.grantsPremium(now))
        assertEquals(2, calls.get())
    }

    @Test fun `older server response cannot overwrite a newer activation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disk = Disk(null)
        val manager = recovery(disk) { started.complete(Unit); release.await(); PremiumLicense(PremiumLicenseState.FREE, 0L) }
        val request = async { manager.refresh(force = true) }
        started.await()
        assertTrue(manager.record(premium(now + 90 * day)))
        release.complete(Unit)
        assertEquals(now + 90 * day, request.await().license!!.expiresAt)
        assertEquals(now + 90 * day, disk.license!!.expiresAt)
        assertEquals(1, disk.writes.get())
    }

    @Test fun `older premium response cannot undo a newer ban`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disk = Disk(premium())
        val manager = recovery(disk) { started.complete(Unit); release.await(); premium(now + 90 * day) }
        val request = async { manager.refresh(force = true) }
        started.await()
        manager.record(PremiumLicense(PremiumLicenseState.BANNED, now + day))
        release.complete(Unit)
        assertEquals(PremiumLicenseState.BANNED, request.await().license!!.state)
        assertFalse(manager.cached()!!.grantsPremium(now))
    }

    @Test fun `both disk writes failing does not report durable recovery or discard verified session`() = runBlocking {
        val disk = Disk(null, writable = false)
        val manager = recovery(disk) { premium() }
        val result = manager.resolveStartup()
        assertFalse(result.persisted)
        assertFalse(manager.hasResolvedLicense())
        assertNull(disk.license)
        assertTrue(manager.cached()!!.grantsPremium(now))
        disk.writable = true
        val retry = manager.resolveStartup()
        assertTrue(retry.persisted)
        assertTrue(manager.hasResolvedLicense())
        assertTrue(disk.license!!.grantsPremium(now))
    }

    @Test fun `ban stays effective in memory when disk fails`() = runBlocking {
        val disk = Disk(premium(), writable = false)
        val manager = recovery(disk) { PremiumLicense(PremiumLicenseState.BANNED, now + day) }
        assertFalse(manager.refresh(force = true).persisted)
        assertFalse(manager.cached()!!.grantsPremium(now))
        assertTrue(disk.license!!.grantsPremium(now))
    }

    @Test fun `newer inactive secure snapshot wins over old premium fallback`() {
        val banned = PremiumLicense(PremiumLicenseState.BANNED, now + day, 10L)
        assertEquals(banned, newestPremiumLicense(banned, premium(version = 9L)))
    }

    @Test fun `network recovery makes a waiting bootstrap ready without persisting free first`() = runBlocking {
        val disk = Disk(null)
        val calls = AtomicInteger()
        val manager = recovery(disk) { if (calls.incrementAndGet() == 1) null else premium() }
        assertNull(manager.resolveStartup().license)
        assertFalse(manager.hasResolvedLicense())
        assertNull(disk.license)
        manager.refresh(force = true)
        assertTrue(manager.hasResolvedLicense())
        assertTrue(manager.changes.value!!.grantsPremium(now))
        assertEquals(1, disk.writes.get())
    }

    @Test fun `newer fallback survives stale secure data and an app process restart`() {
        val renewed = premium(now + 30 * day, version = 11L)
        val selected = newestPremiumLicense(PremiumLicense(PremiumLicenseState.FREE, 0L, 10L), renewed)
        val manager = recovery(Disk(selected)) { null }
        assertEquals(renewed, manager.cached())
        assertTrue(manager.cached()!!.grantsPremium(now))
    }

    @Test fun `local write order stays monotonic when wall clock moves backwards`() {
        var wall = now
        val disk = Disk(null)
        val manager = recovery(disk, wallClock = { wall }) { null }
        manager.record(premium())
        val first = disk.license!!.version
        wall -= 100 * day
        manager.record(PremiumLicense(PremiumLicenseState.BANNED, now + day))
        assertTrue(disk.license!!.version > first)
    }

    @Test fun `server clock anchors access when device wall clock is wrong`() = runBlocking {
        var elapsed = 100L
        var wall = now + 365 * day
        val manager = recovery(Disk(null), elapsed = { elapsed }, wallClock = { wall }) {
            premium(now + day).copy(serverTime = now, receivedAtElapsed = elapsed)
        }
        manager.resolveStartup()
        assertTrue(manager.cached()!!.grantsPremium(manager.currentTimeMillis()))
        wall = 0L
        elapsed += day
        assertFalse(manager.cached()!!.grantsPremium(manager.currentTimeMillis()))
    }

    @Test fun `server renewal parser supports milliseconds seconds numeric strings and ISO`() {
        for (expiry in listOf("1900086400000", "1900086400", "\"1900086400000\"", "\"2030-03-18T17:46:40.000Z\"")) {
            val result = parseServerPremiumLicense("{\"status\":\"aktif\",\"expired_at\":$expiry}", now, null)
            assertNotNull(expiry, result)
            assertTrue(expiry, result!!.expiresAt > now)
        }
    }

    @Test fun `invalid dates status and response shapes stay unknown`() {
        for (body in listOf("broken", "[]", "{}", "{\"status\":\"unexpected\",\"expired_at\":1900086400000}",
            "{\"status\":\"aktif\",\"expired_at\":\"2026-02-30T00:00:00Z\"}",
            "{\"status\":\"aktif\",\"expired_at\":null}", "{\"status\":\"aktif\",\"expired_at\":-1}")) {
            assertNull(body, parseServerPremiumLicense(body, now, premium()))
        }
    }

    @Test fun `missing record or expiry does not erase a previously paid record`() {
        assertNull(parseServerPremiumLicense("null", now, premium(now - day)))
        assertNull(parseServerPremiumLicense("{\"status\":\"aktif\"}", now, premium(now - day)))
        assertEquals(PremiumLicenseState.FREE, parseServerPremiumLicense("null", now, null)!!.state)
        assertEquals(PremiumLicenseState.FREE, parseServerPremiumLicense("{\"status\":\"aktif\"}", now, null)!!.state)
    }

    @Test fun `positive expiry without server clock never grants premium`() {
        assertNull(parseServerPremiumLicense("{\"status\":\"aktif\",\"expired_at\":1900086400000}", 0L, null))
    }

    @Test fun `ban is authoritative and preserves display expiry`() {
        val result = parseServerPremiumLicense("{\"status\":\"banned\"}", now, premium())!!
        assertEquals(PremiumLicenseState.BANNED, result.state)
        assertEquals(now + day, result.expiresAt)
        assertFalse(result.grantsPremium(now))
    }

    @Test fun `expired authoritative result preserves expiry rather than clearing history`() {
        val result = parseServerPremiumLicense("{\"status\":\"aktif\",\"expired_at\":1899913600000}", now, null)!!
        assertEquals(PremiumLicenseState.EXPIRED, result.state)
        assertEquals(now - day, result.expiresAt)
    }
}
