package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.lagradost.cloudstream3.utils.RepoProtector

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private const val PREF_SNAPSHOT_VERSION = "premium_snapshot_version"
    private const val PREF_LICENSE_STATE = "premium_license_state"
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var recoveryInstance: PremiumRecovery? = null

    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)
    val FIREBASE_BASE_URL = RepoProtector.decode(RepoProtector.FIREBASE_URL_ENCODED)

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default_device"
        return abs(androidId.hashCode()).toString().take(8)
    }

    // Preserve existing encrypted data when Keystore is temporarily unavailable.
    private fun getSecurePrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "premium_secure_data",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // A temporary Keystore failure is not permission to delete a customer's license.
            Log.w("PremiumManager", "Secure license storage unavailable; retaining existing data")
            null
        }
    }

    private fun getBackupPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("premium_fallback_prefs", Context.MODE_PRIVATE)
    }

    private fun encodeObfuscated(data: String): String {
        return Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeObfuscated(data: String): String {
        return try {
            String(Base64.decode(data, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun readLicense(context: Context): PremiumLicense? {
        fun license(premium: Boolean, expiry: Long, version: Long, state: String?): PremiumLicense {
            val storedState = PremiumLicenseState.entries.firstOrNull { it.name == state }
                ?: if (premium) PremiumLicenseState.PREMIUM
                else if (expiry > 0L) PremiumLicenseState.EXPIRED else PremiumLicenseState.FREE
            return PremiumLicense(storedState, expiry, version)
        }
        val secure = try {
            getSecurePrefs(context)?.takeIf { it.contains(PREF_IS_PREMIUM) }?.let {
                license(it.getBoolean(PREF_IS_PREMIUM, false), it.getLong(PREF_EXPIRY_DATE, 0L),
                    it.getLong(PREF_SNAPSHOT_VERSION, 0L), it.getString(PREF_LICENSE_STATE, null))
            }
        } catch (_: Exception) { null }
        val fallback = try {
            val prefs = getBackupPrefs(context)
            val state = decodeObfuscated(prefs.getString("obf_state", "") ?: "")
            val expiry = decodeObfuscated(prefs.getString("obf_exp", "") ?: "").toLongOrNull()
            if (state !in setOf("ACTIVE_VIP", "INACTIVE") || expiry == null) null else {
                license(state == "ACTIVE_VIP", expiry, prefs.getLong(PREF_SNAPSHOT_VERSION, 0L),
                    prefs.getString(PREF_LICENSE_STATE, null))
            }
        } catch (_: Exception) { null }
        val legacy = try {
            PreferenceManager.getDefaultSharedPreferences(context).takeIf { it.contains(PREF_IS_PREMIUM) }?.let {
                license(it.getBoolean(PREF_IS_PREMIUM, false), it.getLong(PREF_EXPIRY_DATE, 0L), 0L, null)
            }
        } catch (_: Exception) { null }
        return newestPremiumLicense(secure, fallback, legacy)
    }

    private fun persistLicense(context: Context, license: PremiumLicense): Boolean {
        val premium = license.state == PremiumLicenseState.PREMIUM
        val secureSaved = try {
            getSecurePrefs(context)?.edit()?.run {
                putBoolean(PREF_IS_PREMIUM, premium)
                putLong(PREF_EXPIRY_DATE, license.expiresAt)
                putLong(PREF_SNAPSHOT_VERSION, license.version)
                putString(PREF_LICENSE_STATE, license.state.name)
                commit()
            } == true
        } catch (_: Exception) { false }
        val fallbackSaved = try {
            getBackupPrefs(context).edit().run {
                putString("obf_state", encodeObfuscated(if (premium) "ACTIVE_VIP" else "INACTIVE"))
                putString("obf_exp", encodeObfuscated(license.expiresAt.toString()))
                putLong(PREF_SNAPSHOT_VERSION, license.version)
                putString(PREF_LICENSE_STATE, license.state.name)
                commit()
            }
        } catch (_: Exception) { false }
        return secureSaved || fallbackSaved
    }

    private fun recovery(context: Context): PremiumRecovery = recoveryInstance ?: synchronized(this) {
        recoveryInstance ?: run {
            val appContext = context.applicationContext
            PremiumRecovery(
                scope = recoveryScope,
                load = { readLicense(appContext) },
                save = { persistLicense(appContext, it) },
                fetch = { fetchLicense(appContext) },
                nowMillis = System::currentTimeMillis,
                elapsedMillis = SystemClock::elapsedRealtime,
            ).also { recoveryInstance = it }
        }
    }

    private suspend fun fetchLicense(context: Context): PremiumLicense? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("${FIREBASE_BASE_URL}users/${getDeviceId(context)}.json")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val serverTime = connection.date
            parseServerPremiumLicense(body, serverTime, recoveryInstance?.cached())?.copy(
                serverTime = serverTime, receivedAtElapsed = SystemClock.elapsedRealtime()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never expose the endpoint, activation code, response body or exception URL.
            null
        } finally { connection?.disconnect() }
    }

    private fun saveLicenseLocally(context: Context, isPremium: Boolean, expiryDate: Long, serverTime: Long = 0L): Boolean =
        recovery(context).record(PremiumLicense(
            if (isPremium) PremiumLicenseState.PREMIUM else PremiumLicenseState.FREE,
            expiryDate, serverTime = serverTime,
            receivedAtElapsed = if (serverTime > 0L) SystemClock.elapsedRealtime() else 0L,
        ))

    enum class StartupEntitlement { PREMIUM, FREE, EXPIRED, BANNED, UNKNOWN }

    internal fun entitlementChanges(context: Context): StateFlow<PremiumLicense?> = recovery(context).changes

    internal fun hasResolvedEntitlement(context: Context): Boolean = recovery(context).hasResolvedLicense()

    fun refreshEntitlement(context: Context) { recovery(context).requestRefresh() }

    /** Await before repository selection. Automatic recovery only reads server data. */
    suspend fun resolveEntitlementForStartup(context: Context): StartupEntitlement = withContext(Dispatchers.IO) {
        val manager = recovery(context)
        val result = manager.resolveStartup()
        val license = result.license
        if (license == null || !result.persisted) return@withContext StartupEntitlement.UNKNOWN
        manager.requestRefresh()
        if (license.state == PremiumLicenseState.PREMIUM && !license.grantsPremium(manager.currentTimeMillis())) {
            StartupEntitlement.EXPIRED
        } else StartupEntitlement.valueOf(license.state.name)
    }

    private fun getIsoTime(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMillis))
    }

    // Parser tanggal fleksibel (Mendukung Long ms, Long sec, String ISO Date)
    private fun parseExpiryTimestamp(json: JSONObject): Long {
        return try {
            if (!json.has("expired_at")) return 0L
            val obj = json.get("expired_at")
            when (obj) {
                is Long -> if (obj < 10_000_000_000L) obj * 1000L else obj
                is Int -> obj.toLong() * 1000L
                is Number -> {
                    val l = obj.toLong()
                    if (l < 10_000_000_000L) l * 1000L else l
                }
                is String -> {
                    val num = obj.toLongOrNull()
                    if (num != null) {
                        if (num < 10_000_000_000L) num * 1000L else num
                    } else {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("UTC")
                            sdf.parse(obj)?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun validateDeviceTime(context: Context, serverTime: Long) {
        val localTime = System.currentTimeMillis()
        val difference = abs(serverTime - localTime)
        
        if (difference > 24L * 60L * 60L * 1000L) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ Peringatan: Tanggal/Jam HP Anda tidak akurat! Harap ubah ke Waktu Otomatis di Pengaturan HP.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkAndMigrateOldOfflineUser(context: Context) {
        try {
            val oldPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!oldPrefs.contains(PREF_IS_PREMIUM)) return
            // Versioned secure/fallback writes take precedence over any surviving legacy keys.
            val manager = recovery(context)
            val legacy = manager.cached() ?: return
            if (legacy.version > 0L) return
            val oldPremium = oldPrefs.getBoolean(PREF_IS_PREMIUM, false)
            val oldExpiry = oldPrefs.getLong(PREF_EXPIRY_DATE, 0L)
            val migrated = if (oldPremium && oldExpiry > System.currentTimeMillis()) {
                PremiumLicense(PremiumLicenseState.PREMIUM, oldExpiry)
            } else legacy
            if (manager.record(migrated)) {
                oldPrefs.edit().remove(PREF_IS_PREMIUM).remove(PREF_EXPIRY_DATE).commit()
            }
            // Never PATCH an old offline expiry over a newer renewal or ban on the server.
        } catch (_: Exception) {
            // Keep the old keys intact when their storage cannot be read or migrated.
        }
    }

    fun activatePremiumWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (response != "null") {
                        val jsonResponse = JSONObject(response)
                        val dbStatus = jsonResponse.optString("status", "")
                        val dbCode = jsonResponse.optString("code", "")
                        val dbExpired = parseExpiryTimestamp(jsonResponse)

                        if (dbStatus == "banned") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Device ini telah di-banned!") }
                            return@launch
                        }

                        if (dbCode == inputCode) {
                            val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()
                            
                            if (connection.date > 0) validateDeviceTime(context, serverTime)

                            if (serverTime < dbExpired) {
                                if (!saveLicenseLocally(context, true, dbExpired, serverTime)) {
                                    Handler(Looper.getMainLooper()).post {
                                        onResult(false, context.getString(R.string.adi_license_restore_retry))
                                    }
                                    return@launch
                                }
                                Handler(Looper.getMainLooper()).post { onResult(true, "Aktivasi Berhasil") }
                            } else {
                                Handler(Looper.getMainLooper()).post { onResult(false, "Masa aktif kadaluarsa!") }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Kode VIP tidak valid!") }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post { onResult(false, "Device belum terdaftar.") }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Kesalahan Jaringan") }
            }
        }
    }

    fun activatePromoWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        activatePromoWithCode(context, code, deviceId, restartOnSuccess = true, onResult = onResult)
    }

    // The subscription page owns its success dialog; legacy callers keep the original restart.
    fun activatePromoWithCode(context: Context, code: String, deviceId: String, restartOnSuccess: Boolean, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode Promo kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val promoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val promoConn = promoUrl.openConnection() as HttpURLConnection
                promoConn.requestMethod = "GET"

                if (promoConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Server Promo sibuk.") }
                    return@launch
                }

                val promoRes = promoConn.inputStream.bufferedReader().use { it.readText() }
                if (promoRes == "null") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kode Promo tidak ditemukan!") }
                    return@launch
                }

                val jsonPromo = JSONObject(promoRes)
                val promoValidUntil = parseExpiryTimestamp(jsonPromo)
                
                val serverTime = if (promoConn.date > 0) promoConn.date else System.currentTimeMillis()
                
                if (promoConn.date > 0) validateDeviceTime(context, serverTime)

                if (jsonPromo.optString("status") != "aktif") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Promo tidak aktif!") }
                    return@launch
                }
                if (jsonPromo.optInt("used_count") >= jsonPromo.optInt("max_quota")) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kuota promo habis!") }
                    return@launch
                }
                if (serverTime > promoValidUntil && promoValidUntil > 0L) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Promo sudah kadaluarsa!") }
                    return@launch
                }

                val markUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json")
                val markConn = markUrl.openConnection() as HttpURLConnection
                markConn.requestMethod = "PUT"
                markConn.setRequestProperty("Content-Type", "application/json")
                markConn.doOutput = true
                markConn.outputStream.use { it.write("true".toByteArray(Charsets.UTF_8)) }

                if (markConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal! Promo sudah pernah diklaim.") }
                    return@launch
                }

                val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                updatePromoConn.requestMethod = "POST"
                updatePromoConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updatePromoConn.setRequestProperty("Content-Type", "application/json")
                updatePromoConn.doOutput = true
                val promoPatch = JSONObject().apply { put("used_count", jsonPromo.optInt("used_count") + 1) }.toString()
                updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                updatePromoConn.responseCode

                val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val userConn = userUrl.openConnection() as HttpURLConnection
                var baseTimestamp = if (userConn.date > 0) userConn.date else System.currentTimeMillis()

                if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                     val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                     if (userRes != "null") {
                         val dbExp = parseExpiryTimestamp(JSONObject(userRes))
                         if (dbExp > baseTimestamp) baseTimestamp = dbExp 
                     }
                }
                
                val newExpiredTimestamp = baseTimestamp + (jsonPromo.optInt("days") * 24L * 60L * 60L * 1000L)

                val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                updateUserConn.requestMethod = "POST"
                updateUserConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updateUserConn.setRequestProperty("Content-Type", "application/json")
                updateUserConn.doOutput = true
                
                val userPatch = JSONObject().apply {
                    put("expired_at", newExpiredTimestamp)
                    put("last_update", getIsoTime(serverTime))
                }.toString()
                
                updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                
                val finalUserRes = updateUserConn.responseCode
                if (finalUserRes == HttpURLConnection.HTTP_OK) {
                    if (!saveLicenseLocally(context, true, newExpiredTimestamp, serverTime)) {
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, context.getString(R.string.adi_license_restore_retry))
                        }
                        return@launch
                    }
                    
                    Handler(Looper.getMainLooper()).post { 
                        if (!restartOnSuccess) {
                            onResult(true, "Selamat! Promo Berhasil Diklaim.")
                            return@post
                        }
                        Toast.makeText(context, "Selamat! Promo Berhasil Diklaim.", Toast.LENGTH_LONG).show()
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        context.startActivity(Intent.makeRestartActivityTask(intent?.component))
                        Runtime.getRuntime().exit(0)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal Sinkronisasi User (Error $finalUserRes)") }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Timeout/Kesalahan Jaringan") }
            }
        }
    }

    fun isPremium(context: Context): Boolean {
        val manager = recovery(context)
        manager.requestRefresh()
        return manager.cached()?.grantsPremium(manager.currentTimeMillis()) == true
    }

    fun deactivatePremium(context: Context) {
        saveLicenseLocally(context, false, 0L)
    }

    fun getExpiryDateString(context: Context): String {
        val expiry = getCachedExpiryForDisplay(context)
        return if (expiry == 0L) "Gratis" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expiry))
    }

    /** Read-only presentation metadata. Never grants access or writes license/preferences. */
    data class SubscriptionDisplayInfo(val expiresAt: Long, val serverTime: Long, val blocked: Boolean)

    fun getCachedExpiryForDisplay(context: Context): Long = recovery(context).cached()?.expiresAt ?: 0L

    // Share the authoritative recovery GET with startup and foreground refresh.
    fun loadSubscriptionInfoForDisplay(context: Context, onResult: (SubscriptionDisplayInfo?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val manager = recovery(context)
            val result = manager.refresh(force = true)
            val info = result.license?.let {
                SubscriptionDisplayInfo(it.expiresAt, manager.currentTimeMillis(), it.state == PremiumLicenseState.BANNED)
            }
            Handler(Looper.getMainLooper()).post { onResult(info) }
        }
    }

}
