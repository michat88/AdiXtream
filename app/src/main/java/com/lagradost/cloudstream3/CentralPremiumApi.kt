package com.lagradost.cloudstream3

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/** Deliberately cannot be enabled remotely. Owner enrollment/recovery is a release gate. */
internal object CentralPremiumFeature { const val ENABLED = false }
internal interface CentralPremiumSession {
    val uid: String
    suspend fun firebaseIdToken(): String
}
internal object CentralPremiumRuntime {
    @Volatile var session: CentralPremiumSession? = null
    fun client() = CentralPremiumApi(session ?: throw CentralPremiumException("OWNERSHIP_REQUIRED"))
}
internal class CentralPremiumException(val code: String) : Exception(code)
internal data class CentralPremiumResponse(
    val requestId: String?, val outcome: String?, val license: PremiumLicense?,
)
internal fun interface CentralPremiumTransport {
    suspend fun exchange(path: String, token: String, body: String?): String
}

/** Fixed HTTPS origin, bounded I/O, no redirects, retry, raw error logs or Firebase fallback. */
internal class CentralPremiumHttp : CentralPremiumTransport {
    override suspend fun exchange(path: String, token: String, body: String?): String {
        if (!Regex("/api/v2/premium/[a-z0-9/-]+").matches(path)) throw CentralPremiumException("REQUEST_INVALID")
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (bytes.size > 4096) throw CentralPremiumException("REQUEST_INVALID")
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw CentralPremiumException(when (status) {
                401 -> "AUTH_REQUIRED"
                403 -> "FORBIDDEN"
                404 -> "OPERATION_NOT_FOUND"
                409 -> "REVIEW_REQUIRED"
                429 -> "RATE_LIMITED"
                else -> "UNKNOWN_RESULT"
            })
            return connection.inputStream.use { input ->
                val buffer = ByteArray(65537)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 65536) throw CentralPremiumException("INVALID_RESPONSE")
                buffer.copyOf(size).toString(Charsets.UTF_8)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CentralPremiumException) { throw error }
        catch (_: Exception) { throw CentralPremiumException("UNKNOWN_RESULT") }
        finally { connection.disconnect() }
    }
    private companion object { const val BASE = "https://adixtream-premium-api.trinity-zanetamanu.workers.dev" }
}
internal class CentralPremiumApi(
    private val session: CentralPremiumSession,
    private val transport: CentralPremiumTransport = CentralPremiumHttp(),
) {
    val uid get() = session.uid
    private suspend fun call(path: String, body: JSONObject? = null): JSONObject {
        if (session.uid.isBlank()) throw CentralPremiumException("AUTH_REQUIRED")
        val token = session.firebaseIdToken()
        if (token.length > 8192 || !Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(token)) throw CentralPremiumException("AUTH_REQUIRED")
        val json = try { JSONObject(transport.exchange(path, token, body?.toString())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CentralPremiumException) { throw error }
        catch (_: Exception) { throw CentralPremiumException("INVALID_RESPONSE") }
        if (json.optInt("contractVersion") != 2) throw CentralPremiumException("INVALID_RESPONSE")
        return json
    }
    suspend fun current(device: String): PremiumLicense? {
        device(device)
        return license(call("/api/v2/premium/entitlement", JSONObject().put("device_id", device)))
    }
    suspend fun submit(intent: CentralPremiumIntent): CentralPremiumResponse {
        if (intent.uid != uid) throw CentralPremiumException("FORBIDDEN")
        intent.validate()
        return response(call("/api/v2/premium/" + intent.kind,
            JSONObject().put("request_id", intent.requestId).put("device_id", intent.deviceId)
                .put(if (intent.kind == "promo") "promo_code" else "unlock_code", intent.code)), intent.requestId)
    }
    suspend fun inspect(requestId: String, retry: Boolean = false): CentralPremiumResponse {
        requestId(requestId)
        return response(call("/api/v2/premium/operations/$requestId" + if (retry) "/retry" else "", if (retry) JSONObject() else null), requestId)
    }
    private fun response(json: JSONObject, id: String): CentralPremiumResponse {
        if (json.optString("requestId") != id || json.optString("outcome") !in listOf("APPLIED", "PENDING")) throw CentralPremiumException("INVALID_RESPONSE")
        if (json.optString("outcome") == "APPLIED") {
            val receipt = json.optJSONObject("receipt") ?: throw CentralPremiumException("INVALID_RESPONSE")
            if (receipt.optString("requestId") != id) throw CentralPremiumException("INVALID_RESPONSE")
            number(receipt, "appliedAt")
        }
        return CentralPremiumResponse(id, json.getString("outcome"), license(json))
    }
    private fun license(json: JSONObject): PremiumLicense? {
        val current = json.optJSONObject("currentEntitlement") ?: return null
        if (current.optString("state") == "UNKNOWN") return null
        val state = when (current.optString("state")) {
            "PREMIUM" -> PremiumLicenseState.PREMIUM
            "FREE" -> PremiumLicenseState.FREE
            "EXPIRED" -> PremiumLicenseState.EXPIRED
            "BANNED" -> PremiumLicenseState.BANNED
            else -> throw CentralPremiumException("INVALID_RESPONSE")
        }
        val expiry = if (state == PremiumLicenseState.BANNED && current.isNull("expiresAt")) 0L else number(current, "expiresAt")
        val time = number(json, "serverTime")
        if (state != PremiumLicenseState.BANNED || !current.isNull("revision")) number(current, "revision")
        if (time <= 0L || (state == PremiumLicenseState.PREMIUM && expiry <= time)) throw CentralPremiumException("INVALID_RESPONSE")
        return PremiumLicense(state, expiry, serverTime = time)
    }
    private fun number(json: JSONObject, key: String): Long {
        val raw = json.opt(key)
        if (raw !is Number) throw CentralPremiumException("INVALID_RESPONSE")
        val result = try { BigDecimal(raw.toString()).longValueExact() } catch (_: Exception) { throw CentralPremiumException("INVALID_RESPONSE") }
        if (result < 0L || result > 4102444800000L) throw CentralPremiumException("INVALID_RESPONSE")
        return result
    }
    private fun device(value: String) { if (!Regex("(?:[0-9]{1,8}|-2147483)").matches(value)) throw CentralPremiumException("REQUEST_INVALID") }
    private fun requestId(value: String) { if (!CENTRAL_REQUEST_ID.matches(value)) throw CentralPremiumException("REQUEST_INVALID") }
}
internal val CENTRAL_REQUEST_ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-4[a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}")
