package com.lagradost.cloudstream3

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PromoClaimProtocolTest {
    private fun fixture(): JSONObject = javaClass.classLoader!!.getResourceAsStream("adixtream-promo-contract-v1.json")!!
        .bufferedReader().use { JSONObject(it.readText()) }

    private fun completed(): JSONObject = fixture().getJSONArray("responses").getJSONObject(0).getJSONObject("body")
    private fun parse(json: JSONObject, status: Int = 200) = parsePromoServerReply(PromoHttpReply(status, json.toString()))

    @Test fun `shared server contract examples parse without permissive coercion`() {
        val responses = fixture().getJSONArray("responses")
        for (i in 0 until responses.length()) {
            val sample = responses.getJSONObject(i)
            assertNotNull(sample.getString("name"), parse(sample.getJSONObject("body"), sample.getInt("httpStatus")))
        }
    }

    @Test fun `replayed receipt keeps current banned state rather than old premium grant`() {
        val sample = fixture().getJSONArray("responses").getJSONObject(1)
        val result = parse(sample.getJSONObject("body")) as PromoServerReply.Completed
        assertTrue(result.replayed)
        assertEquals("BANNED", result.currentState)
        assertTrue(result.receiptExpiry > result.serverTime)
    }

    @Test fun `request only sends protocol identity promo and stable intent`() {
        val value = PendingPromoClaim("11111111", "ALPHA", "request_0000000001", true)
        val request = JSONObject(value.requestJson())
        assertEquals(4, request.length())
        assertFalse(request.has("dispatched"))
        assertFalse(request.has("principalId"))
        assertFalse(request.has("expired_at"))
        assertEquals(value, parsePendingPromoClaim(value.storageJson()))
    }

    @Test fun `invalid storage is not treated as a fresh intent`() {
        val valid = JSONObject(PendingPromoClaim("11111111", "ALPHA", "request_0000000001").storageJson())
        assertNull(parsePendingPromoClaim("broken"))
        for (key in listOf("deviceId", "promoCode", "idempotencyKey", "dispatched", "contractVersion")) {
            val copy = JSONObject(valid.toString()).apply { remove(key) }
            assertNull(parsePendingPromoClaim(copy.toString()))
        }
        assertNull(parsePendingPromoClaim(valid.put("dispatched", "true").toString()))
    }

    @Test fun `canonical input keeps existing device id edge case and supports long existing promo`() {
        assertTrue(validPromoDeviceId("-2147483"))
        assertTrue(validPromoDeviceId("12345678"))
        assertFalse(validPromoDeviceId("123456789"))
        assertEquals("LEGACY_LONG_PROMO", canonicalPromoCode(" legacy_long_promo "))
        assertNull(canonicalPromoCode("path/code"))
    }

    @Test fun `malformed transport response never fabricates free`() {
        for (body in listOf("", "null", "<html>unavailable</html>", "{}", "x".repeat(65_537))) {
            assertNull(parsePromoServerReply(PromoHttpReply(200, body)))
        }
    }

    @Test fun `response status outcome retryability and contract version must agree`() {
        assertNull(parse(completed(), 503))
        assertNull(parse(completed().put("contractVersion", "1")))
        assertNull(parse(completed().put("retryable", true)))
        assertNull(parse(JSONObject("""{"contractVersion":1,"outcome":"UNKNOWN_RESULT","retryable":false}"""), 503))
        assertNull(parse(JSONObject("""{"contractVersion":1,"outcome":"NEW_UNSUPPORTED_ERROR","retryable":false}"""), 409))
    }

    @Test fun `numeric strings fractional timestamps booleans and overflowing values are rejected`() {
        for (value in listOf<Any>("1793102400000", true, 1793102400000.5, "9223372036854775808")) {
            assertNull(parse(completed().put("serverTime", value)))
        }
        assertNull(parse(completed().apply { getJSONObject("currentEntitlement").put("expiresAt", 9_007_199_254_740_992L) }))
        val tinyFraction = completed().toString().replace("\"serverTime\":1793102400000", "\"serverTime\":1793102400000.00000001")
        assertNull(parsePromoServerReply(PromoHttpReply(200, tinyFraction)))
    }

    @Test fun `premium result must have future expiry and a current revision`() {
        assertNull(parse(completed().apply { getJSONObject("currentEntitlement").put("expiresAt", getLong("serverTime")) }))
        assertNull(parse(completed().apply { getJSONObject("currentEntitlement").put("revision", JSONObject.NULL) }))
    }

    @Test fun `free expired banned and unknown are distinct current outcomes`() {
        for ((state, expiry) in listOf("FREE" to 0L, "EXPIRED" to 1_793_000_000_000L, "BANNED" to null, "UNKNOWN" to null)) {
            val json = completed().apply {
                getJSONObject("currentEntitlement").put("state", state).put("expiresAt", expiry ?: JSONObject.NULL)
            }
            assertEquals(state, (parse(json) as PromoServerReply.Completed).currentState)
        }
    }

    @Test fun `receipt must be structurally valid and not from a future commit`() {
        assertNull(parse(completed().put("operationId", "-".repeat(36))))
        assertNull(parse(completed().apply { getJSONObject("receipt").put("bonusDays", 0) }))
        assertNull(parse(completed().apply { getJSONObject("receipt").put("appliedAt", getLong("serverTime") + 1) }))
        assertNull(parse(completed().apply { getJSONObject("receipt").put("revision", 0) }))
    }
}
