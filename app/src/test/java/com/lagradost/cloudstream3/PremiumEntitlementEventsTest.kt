package com.lagradost.cloudstream3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PremiumEntitlementEventsTest {
    @Test fun `expiry emits an access event even with no successful server GET`() = runBlocking {
        var now = 1000L
        val license = PremiumLicense(PremiumLicenseState.PREMIUM, 2000L)
        val source = MutableStateFlow<PremiumLicense?>(license)
        val events = premiumEntitlementEvents(source, { now }, { duration -> now += duration })
            .take(2).toList()
        assertEquals(2, events.size)
        assertEquals(2000L, now)
        // No FREE decision, preference write, or destruction of recovery data.
        assertEquals(license, source.value)
    }

    @Test fun `renewal replaces old expiry timer and keeps premium active until renewed expiry`() = runBlocking {
        var now = 1000L
        val source = MutableStateFlow<PremiumLicense?>(PremiumLicense(PremiumLicenseState.PREMIUM, 2000L))
        val waiting = CompletableDeferred<Unit>()
        var waits = 0
        val remaining = mutableListOf<Long>()
        val job = async {
            withTimeout(2000L) {
                premiumEntitlementEvents(source, { now }, { duration ->
                    remaining.add(duration)
                    if (++waits == 1) { waiting.complete(Unit); CompletableDeferred<Unit>().await() }
                    else now += duration
                }).take(3).toList()
            }
        }
        waiting.await()
        source.value = PremiumLicense(PremiumLicenseState.PREMIUM, 5000L)
        assertEquals(3, job.await().size)
        assertEquals(listOf(1000L, 4000L), remaining)
        assertEquals(5000L, now)
    }

    @Test fun `unknown free expired and banned emit without scheduling premium timers`() = runBlocking {
        for (license in listOf(null, PremiumLicense(PremiumLicenseState.FREE, 0L),
            PremiumLicense(PremiumLicenseState.EXPIRED, 1000L), PremiumLicense(PremiumLicenseState.BANNED, 9000L))) {
            val result = premiumEntitlementEvents(flowOf(license), { 2000L }, { fail("No active grant") }).toList()
            assertEquals(1, result.size)
        }
    }

    @Test fun `unknown to resolved Free still emits so a waiting startup can recover`() = runBlocking {
        val source = MutableStateFlow<PremiumLicense?>(null)
        var count = 0
        val events = premiumEntitlementEvents(source, { 2000L })
            .onEach { if (++count == 1) source.value = PremiumLicense(PremiumLicenseState.FREE, 0L) }
            .take(2).toList()
        assertEquals(2, events.size)
    }
}
