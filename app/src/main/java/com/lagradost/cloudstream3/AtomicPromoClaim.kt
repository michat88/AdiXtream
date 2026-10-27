package com.lagradost.cloudstream3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.json.JSONObject
import java.math.BigDecimal
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class AtomicPromoResponse(val status: Int, val body: String, val serverTime: Long, val localRevision: Long = 0L)

/** Existing REST permissions only. No administrative credential or owner assertion. */
internal interface AtomicPromoTransport {
    suspend fun user(deviceId: String): AtomicPromoResponse
    suspend fun promo(code: String): AtomicPromoResponse
    suspend fun commit(patch: String): AtomicPromoResponse
}

internal enum class AtomicPromoStatus {
    APPLIED, ALREADY_RECORDED, BANNED, NOT_FOUND, INACTIVE, EXPIRED, FULL,
    INVALID, DATA_INVALID, NETWORK, UNKNOWN, DENIED, BUSY, LOCAL_SYNC,
}

internal data class AtomicPromoResult(val status: AtomicPromoStatus)

/**
 * Active compatibility fix, separate from the future verified-owner API.
 * A single multi-location PATCH writes marker, counter, expiry and last_update.
 * Existing rules reject the entire PATCH on duplicate marker/stale counter.
 * There is deliberately no fallback to the old three independent writes.
 */
internal class AtomicPromoClaim(
    private val scope: CoroutineScope,
    private val transport: AtomicPromoTransport,
    private val readRevision: () -> Long = { 0L },
    private val applyLicense: (PremiumLicense, Long) -> Boolean,
) {
    private val guard = Any()
    private var inFlight: Deferred<AtomicPromoResult>? = null
    private var input: Pair<String, String>? = null

    suspend fun submit(deviceId: String, value: String): AtomicPromoResult {
        val code = canonicalPromoCode(value)
        if (!validPromoDeviceId(deviceId) || code == null) return result(AtomicPromoStatus.INVALID)
        val requested = deviceId to code
        val job = synchronized(guard) {
            inFlight?.takeUnless { it.isCompleted }?.let {
                if (input != requested) return result(AtomicPromoStatus.BUSY)
                return@synchronized it
            }
            val next = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { perform(deviceId, code) }
            input = requested
            inFlight = next
            next.invokeOnCompletion {
                synchronized(guard) { if (inFlight === next) { inFlight = null; input = null } }
            }
            next.start()
            next
        }
        // A disappearing screen must not cancel the application-owned operation.
        return job.await()
    }

    private suspend fun perform(deviceId: String, code: String): AtomicPromoResult {
        var attempted = false
        return try {
            // One retry only after an explicit rejection (no ambiguous write retry).
            repeat(2) { attempt ->
                val userReply = readUser(deviceId)
                if (userReply.status != 200) return result(AtomicPromoStatus.NETWORK)
                val user = objectOrNull(userReply.body)
                if (user?.optString("status") == "banned") {
                    syncLicense(userReply)
                    return result(AtomicPromoStatus.BANNED)
                }
                if (user?.optJSONObject("redeemed_promos")?.has(code) == true) {
                    syncLicense(userReply)
                    // A legacy true marker alone cannot prove that its bonus was delivered.
                    return result(AtomicPromoStatus.ALREADY_RECORDED)
                }
                if (user != null && user.has("status") && user.optString("status") != "aktif") {
                    return result(AtomicPromoStatus.DATA_INVALID)
                }
                val promoReply = transport.promo(code)
                if (promoReply.status != 200) return result(AtomicPromoStatus.NETWORK)
                val promo = objectOrNull(promoReply.body) ?: return result(AtomicPromoStatus.NOT_FOUND)
                val now = promoReply.serverTime
                if (now <= 0L || userReply.serverTime <= 0L) return result(AtomicPromoStatus.DATA_INVALID)
                if (promo.optString("status") != "aktif") return result(AtomicPromoStatus.INACTIVE)
                val used = exactInteger(promo.opt("used_count")) ?: return result(AtomicPromoStatus.DATA_INVALID)
                val quota = exactInteger(promo.opt("max_quota")) ?: return result(AtomicPromoStatus.DATA_INVALID)
                if (used >= quota) return result(AtomicPromoStatus.FULL)
                val days = exactInteger(promo.opt("days")) ?: return result(AtomicPromoStatus.DATA_INVALID)
                if (days !in 1L..36_500L) return result(AtomicPromoStatus.DATA_INVALID)
                // Rules use valid_until. Do not silently accept conflicting Admin aliases.
                val validUntil = timestamp(promo.opt("valid_until")) ?: return result(AtomicPromoStatus.DATA_INVALID)
                if (promo.has("expired_at") && timestamp(promo.opt("expired_at")) != validUntil) {
                    return result(AtomicPromoStatus.DATA_INVALID)
                }
                if (validUntil <= now) return result(AtomicPromoStatus.EXPIRED)
                val oldExpiry = if (user == null || !user.has("expired_at")) 0L
                    else timestamp(user.opt("expired_at")) ?: return result(AtomicPromoStatus.DATA_INVALID)
                if (oldExpiry == 0L && user != null && user.has("code") && !user.has("expired_at")) {
                    return result(AtomicPromoStatus.DATA_INVALID)
                }
                val bonus = days * DAY
                val baseExpiry = maxOf(oldExpiry, now)
                if (baseExpiry > MAX_EXACT - bonus) return result(AtomicPromoStatus.DATA_INVALID)
                val expectedExpiry = baseExpiry + bonus
                val patch = JSONObject().apply {
                    put("users/$deviceId/redeemed_promos/$code", true)
                    put("promo_codes/$code/used_count", used + 1L)
                    // Numeric millisecond expiries can be incremented on the server. This
                    // preserves another compatible renewal/bonus committed after our GET.
                    val raw = user?.opt("expired_at")
                    val incrementable = raw is Number && exactInteger(raw) == oldExpiry && oldExpiry > now
                    put("users/$deviceId/expired_at", if (incrementable) JSONObject().put(
                        ".sv", JSONObject().put("increment", bonus)
                    ) else expectedExpiry)
                    put("users/$deviceId/last_update", isoTime(now))
                }.toString()
                attempted = true
                val response = try { transport.commit(patch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return verify(deviceId, code, expectedExpiry) }
                if (response.status in 200..299) return verify(deviceId, code, expectedExpiry)
                if (response.status !in setOf(401, 403)) {
                    // 5xx or a lost reply can follow an accepted write. Only read from here.
                    return verify(deviceId, code, expectedExpiry)
                }
                // Explicit denial is atomic: refresh marker, quota and user before retry.
                if (attempt == 1) return result(AtomicPromoStatus.DENIED)
            }
            result(AtomicPromoStatus.DENIED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result(if (attempted) AtomicPromoStatus.UNKNOWN else AtomicPromoStatus.NETWORK) }
    }

    private suspend fun verify(deviceId: String, code: String, expectedExpiry: Long): AtomicPromoResult = try {
        val reply = readUser(deviceId)
        if (reply.status != 200) result(AtomicPromoStatus.UNKNOWN) else {
            val user = objectOrNull(reply.body)
            val license = parseServerPremiumLicense(reply.body, reply.serverTime, null)
            when {
                license?.state == PremiumLicenseState.BANNED -> {
                    applyLicense(license.copy(serverTime = reply.serverTime), reply.localRevision)
                    result(AtomicPromoStatus.BANNED)
                }
                user?.optJSONObject("redeemed_promos")?.opt(code) != true || license == null ||
                    reply.serverTime <= 0L || license.state != PremiumLicenseState.PREMIUM ||
                    license.expiresAt < expectedExpiry -> result(AtomicPromoStatus.UNKNOWN)
                !applyLicense(license.copy(serverTime = reply.serverTime), reply.localRevision) -> result(AtomicPromoStatus.LOCAL_SYNC)
                else -> result(AtomicPromoStatus.APPLIED)
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { result(AtomicPromoStatus.UNKNOWN) }

    private suspend fun readUser(deviceId: String): AtomicPromoResponse {
        val revision = readRevision()
        return transport.user(deviceId).copy(localRevision = revision)
    }

    private fun syncLicense(reply: AtomicPromoResponse) {
        parseServerPremiumLicense(reply.body, reply.serverTime, null)?.let {
            applyLicense(it.copy(serverTime = reply.serverTime), reply.localRevision)
        }
    }

    private fun result(status: AtomicPromoStatus) = AtomicPromoResult(status)

    private fun objectOrNull(body: String): JSONObject? =
        if (body.trim() == "null") null else JSONObject(body)

    private fun exactInteger(value: Any?): Long? {
        if (value !is Byte && value !is Short && value !is Int && value !is Long && value !is java.math.BigInteger) return null
        return try { BigDecimal(value.toString()).longValueExact().takeIf { it in 0L..MAX_EXACT } }
        catch (_: Exception) { null }
    }

    private fun timestamp(value: Any?): Long? {
        val numeric = if (value is String) value.toLongOrNull()?.takeIf { it in 0L..MAX_EXACT }
            else exactInteger(value)
        if (numeric != null) return (if (numeric < 10_000_000_000L) numeric * 1000L else numeric)
            .takeIf { it <= MAX_EXACT }
        if (value !is String) return null
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
            }
            val position = ParsePosition(0)
            val date = parser.parse(value, position)
            if (date != null && position.index == value.length && date.time in 0L..MAX_EXACT) return date.time
        }
        return null
    }

    private fun isoTime(now: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(now))

    private companion object {
        const val DAY = 86_400_000L
        const val MAX_EXACT = 9_007_199_254_740_991L
    }
}
