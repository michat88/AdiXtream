package com.lagradost.cloudstream3.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Fake : SubscriptionService {
        var local = SubscriptionState(deviceId = "same-device")
        val calls = mutableListOf<Pair<String, Boolean>>()
        lateinit var reply: (Boolean, String) -> Unit
        override fun status() = local
        override fun activate(code: String, promo: Boolean, result: (Boolean, String) -> Unit) {
            calls += code to promo
            reply = result
        }
    }
    @Test fun activationKeepsDeviceIdentityAndIgnoresDuplicateSubmission() = runTest(dispatcher) {
        val service = Fake(); val model = SubscriptionViewModel(service)
        model.submit("   ", false)
        assertTrue(service.calls.isEmpty())
        assertNotNull(model.state.value.message)
        model.submit(" code ", false)
        model.submit("duplicate", true)
        assertEquals(listOf("code" to false), service.calls)
        assertTrue(model.state.value.loading)
        service.local = service.local.copy(tier = SubscriptionTier.ACTIVE, expiresAt = 1234)
        service.reply(true, "activated")
        assertTrue(model.state.value.success)
        assertFalse(model.state.value.loading)
        assertEquals("same-device", model.state.value.deviceId)
        assertEquals(SubscriptionTier.ACTIVE, model.state.value.tier)
    }
    @Test fun promoValidationErrorsDoNotChangeStoredLicense() = runTest(dispatcher) {
        val service = Fake(); val model = SubscriptionViewModel(service)
        model.submit("promo", true)
        assertEquals(listOf("promo" to true), service.calls)
        service.reply(false, "already redeemed")
        assertEquals("already redeemed", model.state.value.message)
        assertFalse(model.state.value.success)
        assertEquals(SubscriptionTier.FREE, model.state.value.tier)
    }
    @Test fun timeoutShowsFeedbackAndOldRequestCannotOverwriteNewerResult() = runTest(dispatcher) {
        val service = Fake(); val model = SubscriptionViewModel(service)
        model.submit("slow", false)
        val oldReply = service.reply
        advanceTimeBy(60_001)
        assertFalse(model.state.value.loading)
        assertNotNull(model.state.value.message)
        model.submit("new", false)
        oldReply(false, "stale")
        assertTrue(model.state.value.loading)
        service.reply(false, "latest")
        assertEquals("latest", model.state.value.message)
    }
}
