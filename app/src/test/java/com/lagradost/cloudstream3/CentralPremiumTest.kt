package com.lagradost.cloudstream3

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CentralPremiumTest {
    private val id = "12345678-1234-4234-8234-123456789012"
    private val now = 1_900_000_000_000L
    private val session = object : CentralPremiumSession {
        override val uid = "synthetic-owner"
        override suspend fun firebaseIdToken() = "synthetic.header.signature"
    }
    private fun response(state: String = "PREMIUM", expiry: Any = now + 86400000L, receipt: Boolean = true) = JSONObject()
        .put("contractVersion", 2).put("requestId", id).put("outcome", "APPLIED").put("serverTime", now)
        .put("currentEntitlement", JSONObject().put("state", state).put("expiresAt", expiry).put("revision", 1))
        .also { if (receipt) it.put("receipt", JSONObject().put("requestId", id).put("appliedAt", now)) }.toString()
    private class Store : CentralPremiumIntentStore {
        var pending: CentralPremiumIntent? = null
        override fun read() = pending
        override fun compareAndSet(expected: CentralPremiumIntent?, next: CentralPremiumIntent?): Boolean {
            if (pending != expected) return false
            pending = next
            return true
        }
    }
    private fun coordinator(api: CentralPremiumApi, store: Store, save: (PremiumLicense, Long) -> Boolean = { _, _ -> true }) =
        CentralPremiumCoordinator(api, store, { 4L }, save) { id }
    private suspend fun rejects(code: String? = null, action: suspend () -> Unit) {
        try { action(); fail("Must reject") } catch (e: CentralPremiumException) { if (code != null) assertEquals(code, e.code) }
    }
    @Test fun productionFlagIsOffAndMissingOwnerNeverBecomesDeviceIdAuth() {
        assertFalse(CentralPremiumFeature.ENABLED)
        CentralPremiumRuntime.session = null
        try { CentralPremiumRuntime.client(); fail() } catch (e: CentralPremiumException) { assertEquals("OWNERSHIP_REQUIRED", e.code) }
    }
    @Test fun requestUsesSessionBearerAndNoAdminSecret() = runBlocking {
        val api = CentralPremiumApi(session, CentralPremiumTransport { path, token, body ->
            assertEquals("/api/v2/premium/entitlement", path)
            assertEquals("synthetic.header.signature", token)
            assertEquals("11111111", JSONObject(body!!).getString("device_id"))
            response()
        })
        assertTrue(api.current("11111111")!!.grantsPremium(now))
    }
    @Test fun intentIsDurableBeforeSendingAndCurrentLicensePrecedesAcknowledgement() = runBlocking {
        val store = Store()
        var saved = false
        val api = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> assertEquals(id, store.pending?.requestId); response() })
        val result = coordinator(api, store) { license, revision ->
            assertNotNull(store.pending); assertEquals(4L, revision); assertTrue(license.grantsPremium(now)); saved = true; true
        }.submit("promo", "11111111", "TEST")
        assertTrue(result.applied); assertTrue(saved); assertNull(store.pending)
    }
    @Test fun lostReplyAndRestartReuseSameIdWithoutSecondSubmission() = runBlocking {
        val store = Store(); var sends = 0
        val first = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> sends++; throw CentralPremiumException("UNKNOWN_RESULT") })
        rejects("UNKNOWN_RESULT") { coordinator(first, store).submit("promo", "11111111", "TEST") }
        val second = CentralPremiumApi(session, CentralPremiumTransport { path, _, body ->
            assertEquals("/api/v2/premium/operations/$id", path); assertNull(body); response()
        })
        assertTrue(coordinator(second, store).submit("promo", "11111111", "TEST").applied)
        assertEquals(1, sends)
    }
    @Test fun retryAfterCrashBeforeSendUsesOriginalIntent() = runBlocking {
        val store = Store().also { it.pending = CentralPremiumIntent(id, session.uid, "promo", "11111111", "TEST") }
        val paths = mutableListOf<String>()
        val api = CentralPremiumApi(session, CentralPremiumTransport { path, _, body ->
            paths += path
            if (body == null) throw CentralPremiumException("OPERATION_NOT_FOUND")
            assertEquals(id, JSONObject(body).getString("request_id")); response()
        })
        assertTrue(coordinator(api, store).submit("promo", "11111111", "TEST").applied)
        assertEquals(2, paths.size)
    }
    @Test fun differentClaimDoesNotDiscardPendingOperation() = runBlocking {
        val store = Store().also { it.pending = CentralPremiumIntent(id, session.uid, "promo", "11111111", "OLD") }
        val api = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> fail("Network forbidden"); "" })
        rejects("PENDING_OPERATION") { coordinator(api, store).submit("promo", "11111111", "NEW") }
        assertEquals("OLD", store.pending?.code)
    }
    @Test fun receiptWithoutCurrentLicenseOrLocalSaveCannotAcknowledge() = runBlocking {
        for (mode in listOf("UNKNOWN", "SAVE_FAIL")) {
            val store = Store()
            val api = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> response(if (mode == "UNKNOWN") "UNKNOWN" else "PREMIUM") })
            val result = coordinator(api, store) { _, _ -> false }.submit("promo", "11111111", "TEST")
            assertFalse(result.applied); assertTrue(result.pending); assertNotNull(store.pending)
        }
    }
    @Test fun oldReceiptCannotOverrideCurrentBanEvenWithMalformedExpiry() = runBlocking {
        val store = Store(); var saved: PremiumLicense? = null
        val api = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> response("BANNED", JSONObject.NULL) })
        val result = coordinator(api, store) { license, _ -> saved = license; true }.submit("promo", "11111111", "TEST")
        assertFalse(result.applied); assertEquals(PremiumLicenseState.BANNED, saved?.state); assertNull(store.pending)
    }
    @Test fun malformedReceiptExtremeAndStringExpiryNeverGrant() = runBlocking {
        for (raw in listOf(response(expiry = Long.MAX_VALUE), response(expiry = "1900086400000"), response(receipt = false))) {
            val store = Store(); var saved = false
            val api = CentralPremiumApi(session, CentralPremiumTransport { _, _, _ -> raw })
            rejects("INVALID_RESPONSE") { coordinator(api, store) { _, _ -> saved = true; true }.submit("promo", "11111111", "TEST") }
            assertFalse(saved); assertNotNull(store.pending)
        }
    }
    @Test fun invalidOrLegacyTokenCannotBeSent() = runBlocking {
        val bad = object : CentralPremiumSession { override val uid = "owner"; override suspend fun firebaseIdToken() = "SYNTHETIC_SECRET" }
        val api = CentralPremiumApi(bad, CentralPremiumTransport { _, _, _ -> fail("No request"); "" })
        rejects("AUTH_REQUIRED") { api.current("11111111") }
    }
    @Test fun fileIntentSurvivesNewInstanceAndCorruptionFailsClosed() {
        val dir = Files.createTempDirectory("central-synthetic").toFile()
        try {
            val intent = CentralPremiumIntent(id, session.uid, "unlock", "11111111", "SYNTHETIC")
            val first = CentralPremiumIntentFileStore(dir)
            assertTrue(first.compareAndSet(null, intent))
            assertEquals(intent, CentralPremiumIntentFileStore(dir).read())
            assertFalse(first.compareAndSet(null, intent))
            dir.resolve("adixtream-central-intent-v2.json").writeText("corrupt")
            try { first.read(); fail() } catch (_: java.io.IOException) { }
        } finally { dir.deleteRecursively() }
    }
}
