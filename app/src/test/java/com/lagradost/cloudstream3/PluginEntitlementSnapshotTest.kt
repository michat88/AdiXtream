package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.settings.extensions.PluginEntitlementSnapshot
import com.lagradost.cloudstream3.ui.settings.extensions.pluginEntitlementSnapshots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PluginEntitlementSnapshotTest {
    @Test
    fun `thousands of row renders share one background entitlement read`() = runBlocking {
        val reads = AtomicInteger()
        val readThread = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val snapshot = pluginEntitlementSnapshots(flowOf(Unit)) {
            reads.incrementAndGet()
            readThread.set(Thread.currentThread())
            status(premium = true)
        }.first()

        repeat(10_000) {
            assertFalse(snapshot.isLocked("internal-premium", null))
            assertFalse(snapshot.isLocked("", "/plugins/premium/Provider.cs3"))
        }
        assertEquals(1, reads.get())
        assertNotSame(caller, readThread.get())
    }

    @Test
    fun `expiry refreshes row presentation without scrolling or a preference event`() = runBlocking {
        val reads = AtomicInteger()
        val snapshots = withTimeout(5_000) {
            pluginEntitlementSnapshots(flowOf(Unit), nowMillis = { 0L }) {
                status(premium = reads.incrementAndGet() == 1, expiresAt = 1L)
            }.take(2).toList()
        }
        assertFalse(snapshots[0].isLocked("internal-premium", null))
        assertTrue(snapshots[1].isLocked("internal-premium", null))
        assertEquals(2, reads.get())
    }

    @Test
    fun `a license change refreshes the snapshot before its periodic timer`() = runBlocking {
        val shown = CompletableDeferred<Unit>()
        val changes = flow {
            emit(Unit)
            shown.await()
            emit(Unit)
        }
        val reads = AtomicInteger()
        val snapshots = withTimeout(5_000) {
            pluginEntitlementSnapshots(changes) {
                status(premium = reads.incrementAndGet() == 1)
            }.onEach { shown.complete(Unit) }.take(2).toList()
        }
        assertTrue(snapshots[0].isPremium)
        assertFalse(snapshots[1].isPremium)
        assertEquals(2, reads.get())
    }

    @Test
    fun `resubscribing after leaving the screen reads current entitlement again`() = runBlocking {
        val reads = AtomicInteger()
        val snapshots = pluginEntitlementSnapshots(flowOf(Unit)) {
            status(premium = reads.incrementAndGet() == 2)
        }
        assertFalse(snapshots.first().isPremium)
        assertTrue(snapshots.first().isPremium)
        assertEquals(2, reads.get())
    }

    @Test
    fun `presentation keeps remote and local Premium locks without revealing repository identity`() {
        val snapshot = status(premium = false)
        assertTrue(snapshot.isLocked("internal-premium", null))
        assertTrue(snapshot.isLocked("", "/plugins/premium/Provider.cs3"))
        assertFalse(snapshot.isLocked("internal-free", null))
        assertFalse(snapshot.isLocked("", "/plugins/free/Provider.cs3"))
        assertFalse(snapshot.toString().contains("internal-premium"))
        assertFalse(snapshot.toString().contains("/plugins/"))
    }

    private fun status(premium: Boolean, expiresAt: Long = Long.MAX_VALUE) = PluginEntitlementSnapshot(
        isPremium = premium,
        expiresAt = expiresAt,
        premiumRepositoryUrl = "internal-premium",
        premiumDirectory = File("/plugins/premium"),
    )
}
