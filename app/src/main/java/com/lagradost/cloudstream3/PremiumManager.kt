package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
import com.lagradost.cloudstream3.utils.RepoProtector

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private var lastCheckTime = 0L

    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)
    val FIREBASE_BASE_URL = RepoProtector.decode(RepoProtector.FIREBASE_URL_ENCODED)

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default_device"
        return abs(androidId.hashCode()).toString().take(8)
    }

    // Handled Try-Catch: Hapus file terenkripsi jika corrupt (AEADBadTagException)
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
        } catch (e: Exception) {
            Log.e("PremiumManager", "EncryptedSharedPreferences Corrupt! Auto-resetting Encrypted file: ${e.message}")
            try {
                context.deleteSharedPreferences("premium_secure_data")
            } catch (_: Exception) {}
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

    private fun saveLicenseLocally(context: Context, isPremium: Boolean, expiryDate: Long) {
        // 1. Simpan ke EncryptedStorage jika bisa
        getSecurePrefs(context)?.edit()?.apply {
            putBoolean(PREF_IS_PREMIUM, isPremium)
            putLong(PREF_EXPIRY_DATE, expiryDate)
            commit()
        }

        // 2. Selalu simpan ke Fallback Preferences (Sangat stabil & anti Android Keystore Crash)
        val obfuscatedState = encodeObfuscated(if (isPremium) "ACTIVE_VIP" else "INACTIVE")
        val obfuscatedExp = encodeObfuscated(expiryDate.toString())

        getBackupPrefs(context).edit().apply {
            putString("obf_state", obfuscatedState)
            putString("obf_exp", obfuscatedExp)
            commit()
        }
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
        val oldPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wasPremium = oldPrefs.getBoolean(PREF_IS_PREMIUM, false)
        val oldExpiryDate = oldPrefs.getLong(PREF_EXPIRY_DATE, 0L)

        if (wasPremium && oldExpiryDate > System.currentTimeMillis()) {
            saveLicenseLocally(context, true, oldExpiryDate)

            oldPrefs.edit()
                .remove(PREF_IS_PREMIUM)
                .remove(PREF_EXPIRY_DATE)
                .apply()

            val deviceId = getDeviceId(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true

                    val jsonPayload = JSONObject().apply {
                        put("status", "aktif")
                        put("expired_at", oldExpiryDate)
                        put("last_update", getIsoTime(System.currentTimeMillis()))
                    }.toString()

                    connection.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } catch (e: Exception) { }
            }
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
                                saveLicenseLocally(context, true, dbExpired)
                                lastCheckTime = serverTime
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
                    saveLicenseLocally(context, true, newExpiredTimestamp)
                    lastCheckTime = serverTime
                    
                    Handler(Looper.getMainLooper()).post { 
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
        var isPrem = false
        var expDate = 0L

        // 1. Coba baca dari Encrypted preferences
        try {
            val secure = getSecurePrefs(context)
            if (secure != null) {
                isPrem = secure.getBoolean(PREF_IS_PREMIUM, false)
                expDate = secure.getLong(PREF_EXPIRY_DATE, 0L)
            }
        } catch (e: Exception) {
            isPrem = false
        }

        // 2. Jika Encrypted gagal/kosong, fallback baca dari Obfuscated Backup Preferences
        if (!isPrem) {
            try {
                val backup = getBackupPrefs(context)
                val rawState = decodeObfuscated(backup.getString("obf_state", "") ?: "")
                val rawExp = decodeObfuscated(backup.getString("obf_exp", "") ?: "")
                
                isPrem = rawState == "ACTIVE_VIP"
                expDate = rawExp.toLongOrNull() ?: 0L

                // Jika backup valid, sinkronkan kembali ke Encrypted Storage
                if (isPrem && expDate > System.currentTimeMillis()) {
                    saveLicenseLocally(context, true, expDate)
                }
            } catch (e: Exception) { }
        }

        Log.d("PremiumDebug", "isPremium: stored=$isPrem, exp=$expDate, now=${System.currentTimeMillis()}")

        if (isPrem) {
            if (System.currentTimeMillis() > expDate) {
                deactivatePremium(context) 
                return false
            }
            if (System.currentTimeMillis() - lastCheckTime > 5 * 60 * 1000) {
                checkAndSyncWithServer(context, getDeviceId(context))
            }
            return true
        }
        return false
    }

    fun deactivatePremium(context: Context) {
        saveLicenseLocally(context, false, 0L)
    }
    
    fun getExpiryDateString(context: Context): String {
        var date = 0L
        try {
            val backup = getBackupPrefs(context)
            val rawExp = decodeObfuscated(backup.getString("obf_exp", "") ?: "")
            date = rawExp.toLongOrNull() ?: 0L
        } catch (_: Exception) {}

        if (date == 0L) {
            try {
                date = getSecurePrefs(context)?.getLong(PREF_EXPIRY_DATE, 0L) ?: 0L
            } catch (_: Exception) {}
        }

        return if (date == 0L) "Gratis" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }
    
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val connection = url.openConnection() as HttpURLConnection
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (response == "null") {
                        registerUserToServer(context, deviceId)
                    } else {
                        val json = JSONObject(response)
                        val dbStatus = json.optString("status", "")
                        val dbExpired = parseExpiryTimestamp(json)
                        
                        val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()
                        
                        if (connection.date > 0) validateDeviceTime(context, serverTime)

                        val wasPremium = isPremium(context)
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" && dbExpired > 0L && serverTime > dbExpired
                       
                        if (isBanned || isExpired) {
                            if (wasPremium) {
                                deactivatePremium(context) 
                                Handler(Looper.getMainLooper()).post {
                                    val pesan = if (isBanned) "⛔ AKSES DICABUT ADMIN!" else "⚠️ Masa Aktif Habis."
                                    Toast.makeText(context, pesan, Toast.LENGTH_LONG).show()
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    context.startActivity(Intent.makeRestartActivityTask(intent?.component))
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                        } else if (dbStatus == "aktif" && dbExpired > 0L) {
                            saveLicenseLocally(context, true, dbExpired)
                            lastCheckTime = serverTime
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun registerUserToServer(context: Context, deviceId: String) {
        try {
            val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()
            
            if (connection.date > 0) validateDeviceTime(context, serverTime)

            val jsonPayload = JSONObject().apply {
                put("status", "aktif")
                put("created_at", getIsoTime(serverTime))
                put("last_update", getIsoTime(serverTime))
            }.toString()
            
            connection.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (e: Exception) { }
    }
}
