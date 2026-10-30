package com.lagradost.cloudstream3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal data class CentralPremiumIntent(
    val requestId: String, val uid: String, val kind: String, val deviceId: String, val code: String,
) {
    fun validate() {
        if (!CENTRAL_REQUEST_ID.matches(requestId) || uid.isBlank() || uid.length > 128 ||
            !Regex("(?:[0-9]{1,8}|-2147483)").matches(deviceId) || kind !in listOf("promo", "unlock") ||
            !Regex(if (kind == "promo") "[A-Z0-9_-]{1,64}" else "[-A-Z0-9]{1,64}").matches(code)) throw CentralPremiumException("REQUEST_INVALID")
    }
    fun json(): String = JSONObject().put("version", 2).put("requestId", requestId).put("uid", uid)
        .put("kind", kind).put("deviceId", deviceId).put("code", code).toString()
    companion object {
        fun parse(text: String): CentralPremiumIntent {
            try {
                val o = JSONObject(text)
                if (o.length() != 6 || o.getInt("version") != 2) throw CentralPremiumException("STORAGE_UNAVAILABLE")
                return CentralPremiumIntent(o.getString("requestId"), o.getString("uid"), o.getString("kind"), o.getString("deviceId"), o.getString("code")).also { it.validate() }
            } catch (_: Exception) { throw CentralPremiumException("STORAGE_UNAVAILABLE") }
        }
    }
}
internal interface CentralPremiumIntentStore {
    fun read(): CentralPremiumIntent?
    fun compareAndSet(expected: CentralPremiumIntent?, next: CentralPremiumIntent?): Boolean
}
internal data class CentralPremiumResult(val applied: Boolean, val state: PremiumLicenseState?, val pending: Boolean)

/** A receipt proves an operation, not current Premium. Persist current server state before acknowledgement. */
internal class CentralPremiumCoordinator(
    private val client: CentralPremiumApi,
    private val store: CentralPremiumIntentStore,
    private val readRevision: () -> Long,
    private val applyLicense: (PremiumLicense, Long) -> Boolean,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    suspend fun submit(kind: String, deviceId: String, code: String): CentralPremiumResult = mutex.withLock {
        val old = store.read()
        if (old != null && (old.uid != client.uid || old.kind != kind || old.deviceId != deviceId || old.code != code)) throw CentralPremiumException("PENDING_OPERATION")
        val intent = old ?: CentralPremiumIntent(newRequestId(), client.uid, kind, deviceId, code).also {
            it.validate()
            if (!store.compareAndSet(null, it)) throw CentralPremiumException("PENDING_OPERATION")
        }
        val revision = readRevision()
        var result = if (old == null) client.submit(intent) else try { client.inspect(intent.requestId) }
            catch (error: CentralPremiumException) { if (error.code == "OPERATION_NOT_FOUND") client.submit(intent) else throw error }
        if (result.outcome == "PENDING") result = client.inspect(intent.requestId, retry = true)
        if (result.outcome != "APPLIED") return@withLock CentralPremiumResult(false, null, true)
        val license = result.license ?: return@withLock CentralPremiumResult(false, null, true)
        if (!applyLicense(license, revision)) return@withLock CentralPremiumResult(false, license.state, true)
        if (!store.compareAndSet(intent, null)) return@withLock CentralPremiumResult(false, license.state, true)
        CentralPremiumResult(license.state == PremiumLicenseState.PREMIUM, license.state, false)
    }
}
