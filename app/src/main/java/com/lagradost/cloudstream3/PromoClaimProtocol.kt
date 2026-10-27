package com.lagradost.cloudstream3

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/** Protocol for the prepared server API. No endpoint or legacy fallback is configured here. */
internal data class PendingPromoClaim(
    val deviceId: String,
    val promoCode: String,
    val idempotencyKey: String,
    val dispatched: Boolean = false,
) {
    fun requestJson(): String = JSONObject().apply {
        put("contractVersion", 1)
        put("deviceId", deviceId)
        put("promoCode", promoCode)
        put("idempotencyKey", idempotencyKey)
    }.toString()

    fun storageJson(): String = JSONObject(requestJson()).apply { put("dispatched", dispatched) }.toString()
}

internal fun canonicalPromoCode(value: String): String? = value.takeIf { it.length <= 128 }
    ?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z0-9_-]{1,64}")) }

internal fun validPromoDeviceId(value: String): Boolean = value.matches(Regex("(?:[0-9]{1,8}|-2147483)"))
internal fun validPromoIntentKey(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{16,100}"))

internal fun parsePendingPromoClaim(text: String): PendingPromoClaim? = try {
    val json = JSONObject(text)
    val device = json.get("deviceId") as? String
    val code = json.get("promoCode") as? String
    val key = json.get("idempotencyKey") as? String
    val sent = json.get("dispatched") as? Boolean
    if (json.length() != 5 || json.exactPromoLong("contractVersion") != 1L || device == null ||
        code == null || key == null || sent == null || !validPromoDeviceId(device) ||
        canonicalPromoCode(code) != code || !validPromoIntentKey(key)) null
    else PendingPromoClaim(device, code, key, sent)
} catch (_: Exception) { null }

internal data class PromoHttpReply(val status: Int, val body: String)

internal sealed interface PromoServerReply {
    data class Completed(
        val operationId: String,
        val replayed: Boolean,
        val serverTime: Long,
        val currentState: String,
        val currentExpiry: Long?,
        val currentRevision: Long?,
        val receiptExpiry: Long,
    ) : PromoServerReply
    data class Rejected(val code: String, val retryable: Boolean) : PromoServerReply
}

private val promoErrorStatuses = mapOf(
    "REQUEST_INVALID" to 400, "AUTH_REQUIRED" to 401, "OWNERSHIP_REQUIRED" to 403,
    "BANNED" to 403, "PROMO_NOT_FOUND" to 404, "PAUSED" to 409, "EXPIRED" to 409,
    "QUOTA_EXHAUSTED" to 409, "IDEMPOTENCY_CONFLICT" to 409,
    "LEGACY_RECONCILIATION_REQUIRED" to 409, "DATA_RECONCILIATION_REQUIRED" to 409,
    "PROMO_DATE_CONFLICT" to 409, "DATA_INVALID" to 409, "PAYLOAD_TOO_LARGE" to 413,
    "UNKNOWN_RESULT" to 503, "TEMPORARY_UNAVAILABLE" to 503, "CONTENTION_RETRY_REQUIRED" to 503,
    "METHOD_NOT_ALLOWED" to 405, "NOT_FOUND" to 404, "UNSUPPORTED_MEDIA_TYPE" to 415,
)

/** Returns null for a malformed/mismatched response, never an implicit Free decision. */
internal fun parsePromoServerReply(reply: PromoHttpReply): PromoServerReply? = try {
    if (reply.body.length > 65_536) null else {
        val json = JSONObject(reply.body)
        val outcome = json.get("outcome") as? String
        val retryable = json.get("retryable") as? Boolean
        if (json.exactPromoLong("contractVersion") != 1L || retryable == null) null
        else if (outcome !in setOf("APPLIED", "REPLAYED")) {
            val status = promoErrorStatuses[outcome]
            if (status == null || status != reply.status || retryable != (status == 503)) null
            else PromoServerReply.Rejected(outcome!!, retryable)
        } else if (reply.status != 200 || retryable) null
        else {
            val operation = json.get("operationId") as? String
            val now = json.exactPromoLong("serverTime")
            val receipt = json.getJSONObject("receipt")
            val grantedExpiry = receipt.exactPromoLong("expiresAt")
            val appliedAt = receipt.exactPromoLong("appliedAt")
            val receiptRevision = receipt.exactPromoLong("revision")
            val days = receipt.exactPromoLong("bonusDays")
            val entitlement = json.getJSONObject("currentEntitlement")
            val state = entitlement.get("state") as? String
            val expiry = entitlement.nullablePromoLong("expiresAt")
            val revision = entitlement.nullablePromoLong("revision")
            val validEntitlement = when (state) {
                "PREMIUM" -> expiry != null && expiry > now && revision != null
                "FREE" -> expiry == 0L && revision != null
                "EXPIRED" -> expiry != null && expiry in 1..now && revision != null
                "BANNED", "UNKNOWN" -> true
                else -> false
            }
            if (state == null || operation == null || !operation.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) ||
                now !in 100_000_000_000L..8_640_000_000_000_000L || days !in 1..36_500L ||
                appliedAt < 100_000_000_000L || appliedAt > now || grantedExpiry < appliedAt ||
                grantedExpiry > 8_640_000_000_000_000L || (expiry != null && expiry > 8_640_000_000_000_000L) ||
                receiptRevision < 1L || (revision != null && revision < 0L) ||
                (expiry != null && expiry < 0L) || !validEntitlement) null
            else PromoServerReply.Completed(operation, outcome == "REPLAYED", now, state,
                expiry, revision, grantedExpiry)
        }
    }
} catch (_: Exception) { null }

// Number only: don't coerce strings, booleans, fractions or rounded out-of-range values.
private fun JSONObject.exactPromoLong(key: String): Long {
    val value = get(key)
    // Android JSONObject represents decimal/exponent tokens as Double. Reject
    // those even if rounding made them look integral; the server emits integers.
    require(value is Int || value is Long || value is BigInteger)
    return BigDecimal(value.toString()).longValueExact().also {
        require(it in 0L..9_007_199_254_740_991L)
    }
}

private fun JSONObject.nullablePromoLong(key: String): Long? =
    if (get(key) === JSONObject.NULL) null else exactPromoLong(key)
