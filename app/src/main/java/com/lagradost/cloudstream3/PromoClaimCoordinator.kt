package com.lagradost.cloudstream3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.UUID

/** No live implementation is supplied until verified owner sessions exist. */
internal interface PromoClaimTransport {
    suspend fun claim(intent: PendingPromoClaim): PromoHttpReply
    suspend fun lookup(intent: PendingPromoClaim): PromoHttpReply
}

internal sealed interface PromoClaimResult {
    data class Completed(val intent: PendingPromoClaim, val reply: PromoServerReply.Completed) : PromoClaimResult
    data class Rejected(val intent: PendingPromoClaim, val code: String) : PromoClaimResult
    data class Pending(val code: String) : PromoClaimResult
}

/**
 * Preparation only: not called by PremiumManager/UI. The future caller must use
 * an application scope and verified-session transport, then apply CURRENT
 * entitlement through the existing recovery generation guard before acknowledge.
 */
internal class PromoClaimCoordinator(
    private val scope: CoroutineScope,
    private val store: PromoClaimIntentStore,
    private val transport: PromoClaimTransport,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val guard = Any()
    private var inFlight: Deferred<PromoClaimResult>? = null
    private var inFlightInput: Pair<String, String>? = null

    suspend fun submit(deviceId: String, promoCode: String): PromoClaimResult {
        val code = canonicalPromoCode(promoCode)
        if (!validPromoDeviceId(deviceId) || code == null) return PromoClaimResult.Pending("REQUEST_INVALID")
        val input = deviceId to code
        val job = synchronized(guard) {
            inFlight?.takeUnless { it.isCompleted }?.let {
                if (inFlightInput != input) return PromoClaimResult.Pending("ANOTHER_CLAIM_PENDING")
                return@synchronized it
            }
            val next = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { perform(deviceId, code) }
            inFlight = next
            inFlightInput = input
            next.invokeOnCompletion {
                synchronized(guard) { if (inFlight === next) { inFlight = null; inFlightInput = null } }
            }
            next.start()
            next
        }
        return job.await()
    }

    private suspend fun perform(deviceId: String, code: String): PromoClaimResult {
        val intent = try {
            val saved = store.read()
            if (saved != null && (saved.deviceId != deviceId || saved.promoCode != code)) {
                return PromoClaimResult.Pending("ANOTHER_CLAIM_PENDING")
            }
            saved ?: PendingPromoClaim(deviceId, code, newKey()).also {
                if (!validPromoIntentKey(it.idempotencyKey) || !store.compareAndSet(null, it)) {
                    return PromoClaimResult.Pending("INTENT_NOT_PERSISTED")
                }
            }
        } catch (_: Exception) { return PromoClaimResult.Pending("INTENT_STORAGE_UNAVAILABLE") }

        if (intent.dispatched) {
            val lookup = exchange(intent, lookup = true) ?: return PromoClaimResult.Pending("UNKNOWN_RESULT")
            // Only a well-formed absent-receipt result permits retry of this SAME intent.
            if (lookup !is PromoServerReply.Rejected || lookup.code != "UNKNOWN_RESULT") return result(intent, lookup)
        }
        val dispatched = intent.copy(dispatched = true)
        try {
            // Persist-before-network, including retry. A false write is not a successful save.
            if (!store.compareAndSet(intent, dispatched)) return PromoClaimResult.Pending("INTENT_NOT_PERSISTED")
        } catch (_: Exception) { return PromoClaimResult.Pending("INTENT_STORAGE_UNAVAILABLE") }
        val reply = exchange(dispatched, lookup = false) ?: return PromoClaimResult.Pending("UNKNOWN_RESULT")
        return result(dispatched, reply)
    }

    private suspend fun exchange(intent: PendingPromoClaim, lookup: Boolean): PromoServerReply? = try {
        parsePromoServerReply(if (lookup) transport.lookup(intent) else transport.claim(intent))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun result(intent: PendingPromoClaim, reply: PromoServerReply): PromoClaimResult = when (reply) {
        is PromoServerReply.Completed -> if (reply.currentState == "UNKNOWN") PromoClaimResult.Pending("ENTITLEMENT_UNRESOLVED")
            else PromoClaimResult.Completed(intent, reply)
        is PromoServerReply.Rejected -> if (reply.retryable || reply.code in setOf("AUTH_REQUIRED", "OWNERSHIP_REQUIRED")) {
            PromoClaimResult.Pending(reply.code)
        } else PromoClaimResult.Rejected(intent, reply.code)
    }

    /** Future caller invokes only after handling/persisting the authoritative result. */
    fun acknowledge(result: PromoClaimResult): Boolean {
        val intent = when (result) {
            is PromoClaimResult.Completed -> result.intent
            is PromoClaimResult.Rejected -> result.intent
            is PromoClaimResult.Pending -> return false
        }
        return synchronized(guard) {
            if (inFlight?.isCompleted == false) return false
            try { store.compareAndSet(intent, null) } catch (_: Exception) { false }
        }
    }
}
