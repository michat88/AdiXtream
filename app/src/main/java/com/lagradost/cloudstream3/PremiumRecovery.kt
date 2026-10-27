package com.lagradost.cloudstream3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.math.BigDecimal
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal enum class PremiumLicenseState { PREMIUM, FREE, EXPIRED, BANNED }

internal data class PremiumLicense(
    val state: PremiumLicenseState,
    val expiresAt: Long,
    val version: Long = 0L,
    val serverTime: Long = 0L,
    val receivedAtElapsed: Long = 0L,
) {
    fun grantsPremium(now: Long) = state == PremiumLicenseState.PREMIUM && expiresAt > now
}

internal data class PremiumRecoveryResult(val license: PremiumLicense?, val persisted: Boolean)

/** One application-scoped recovery. Reads never erase an expired license or start nested GETs. */
internal class PremiumRecovery(
    private val scope: CoroutineScope,
    load: () -> PremiumLicense?,
    private val save: (PremiumLicense) -> Boolean,
    private val fetch: suspend () -> PremiumLicense?,
    private val nowMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val refreshIntervalMillis: Long = 5 * 60 * 1000L,
) {
    private val guard = Any()
    private var current = try { load() } catch (_: Exception) { null }
    private var durable = current != null
    private var generation = 0L
    private var inFlight: Deferred<PremiumRecoveryResult>? = null
    private var lastAttempt: Long? = null
    private var lastResult: PremiumRecoveryResult? = null
    private val updates = MutableStateFlow(current)
    val changes = updates.asStateFlow()

    fun cached(): PremiumLicense? = synchronized(guard) { current }

    fun hasResolvedLicense(): Boolean = synchronized(guard) {
        lastResult?.license != null && lastResult?.persisted == true
    }

    fun currentTimeMillis(): Long = synchronized(guard) {
        val cached = current
        val elapsed = elapsedMillis()
        if (cached != null && cached.serverTime > 0L && elapsed >= cached.receivedAtElapsed) {
            cached.serverTime + (elapsed - cached.receivedAtElapsed)
        } else nowMillis()
    }

    /** Activation/promo and recovery share the same generation and persistence ordering. */
    fun record(license: PremiumLicense): Boolean = synchronized(guard) {
        store(license).persisted
    }

    private fun store(license: PremiumLicense): PremiumRecoveryResult {
        generation++
        val version = maxOf(nowMillis(), (current?.version ?: 0L) + 1L)
        val previous = current
        val updated = if (license.serverTime == 0L && previous != null) {
            license.copy(version = version, serverTime = previous.serverTime, receivedAtElapsed = previous.receivedAtElapsed)
        } else license.copy(version = version)
        durable = try { save(updated) } catch (_: Exception) { false }
        // Keep a verified server decision for this session even if both disk writes fail.
        current = updated
        updates.value = updated
        return PremiumRecoveryResult(updated, durable).also { lastResult = it }
    }

    suspend fun resolveStartup(): PremiumRecoveryResult {
        synchronized(guard) {
            val cached = current
            if (cached?.grantsPremium(currentTimeMillis()) == true) {
                if (!durable) durable = try { save(cached) } catch (_: Exception) { false }
                return PremiumRecoveryResult(cached, durable).also { lastResult = it }
            }
        }
        // A stored FREE/expired flag must not hide a renewal already accepted by the server.
        return refresh(force = true)
    }

    fun requestRefresh() { startRefresh(force = false) }

    suspend fun refresh(force: Boolean = false): PremiumRecoveryResult {
        val job = startRefresh(force)
        return job?.await() ?: synchronized(guard) {
            lastResult ?: PremiumRecoveryResult(null, false)
        }
    }

    private fun startRefresh(force: Boolean): Deferred<PremiumRecoveryResult>? = synchronized(guard) {
        inFlight?.takeUnless { it.isCompleted }?.let { return it }
        val elapsed = elapsedMillis()
        val previousAttempt = lastAttempt
        if (!force && previousAttempt != null && elapsed - previousAttempt < refreshIntervalMillis) return null
        lastAttempt = elapsed
        val startedAtGeneration = generation
        val job = scope.async(start = CoroutineStart.LAZY) {
            val response = try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            synchronized(guard) {
                if (generation != startedAtGeneration) {
                    // A newer activation/promo/revocation won while this GET was in flight.
                    PremiumRecoveryResult(current, durable)
                } else if (response == null) {
                    // UNKNOWN: preserve every stored field; do not emit/store an implicit FREE.
                    PremiumRecoveryResult(null, false).also { lastResult = it }
                } else {
                    store(response)
                }
            }
        }
        inFlight = job
        job.invokeOnCompletion { synchronized(guard) { if (inFlight === job) inFlight = null } }
        job.start()
        job
    }
}

/** Select the latest complete local write, never a stale true flag in the other store. */
internal fun newestPremiumLicense(vararg licenses: PremiumLicense?): PremiumLicense? =
    licenses.filterNotNull().maxByOrNull { it.version }

/** Missing/invalid data is unresolved, rather than an expired timestamp fabricated as zero. */
internal fun parseServerPremiumLicense(
    body: String,
    serverTime: Long,
    previous: PremiumLicense?,
): PremiumLicense? = try {
    if (body.trim() == "null") {
        if ((previous?.expiresAt ?: 0L) > 0L) null else PremiumLicense(PremiumLicenseState.FREE, 0L)
    } else {
        val json = JSONObject(body)
        val status = json.optString("status")
        if (status == "banned") {
            val expiry = if (json.has("expired_at")) compatiblePremiumExpiry(json.get("expired_at")) else null
            PremiumLicense(PremiumLicenseState.BANNED, expiry ?: previous?.expiresAt ?: 0L)
        } else if (status != "aktif") {
            null
        } else {
            val expiry = if (json.has("expired_at")) compatiblePremiumExpiry(json.get("expired_at")) else {
                // An incomplete paid record must not erase an existing paid entitlement.
                if ((previous?.expiresAt ?: 0L) > 0L) null else 0L
            }
            when {
                expiry == null -> null
                expiry == 0L -> PremiumLicense(PremiumLicenseState.FREE, 0L)
                serverTime <= 0L -> null
                expiry <= serverTime -> PremiumLicense(PremiumLicenseState.EXPIRED, expiry)
                else -> PremiumLicense(PremiumLicenseState.PREMIUM, expiry)
            }
        }
    }
} catch (_: Exception) { null }

private fun compatiblePremiumExpiry(value: Any): Long? {
    val number = when (value) {
        is Number -> try {
            val numeric = BigDecimal(value.toString())
            // Legacy Android JSON reads oversized numeric expiries as Double; toLong()
            // saturated them to Long.MAX_VALUE. Retain that read-only compatibility.
            if (numeric > BigDecimal.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else numeric.longValueExact()
        } catch (_: Exception) { return null }
        is String -> value.toLongOrNull() ?: run {
            for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                val position = ParsePosition(0)
                val parsed = parser.parse(value, position)
                if (parsed != null && position.index == value.length && parsed.time >= 0L) return parsed.time
            }
            return null
        }
        else -> return null
    }
    if (number < 0L) return null
    return if (number < 10_000_000_000L) number * 1000L else number
}
