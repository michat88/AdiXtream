package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "premium_secure_data",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun checkAndMigrateOldOfflineUser(context: Context) {
        val oldPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wasPremium = oldPrefs.getBoolean(PREF_IS_PREMIUM, false)
        val oldExpiryDate = oldPrefs.getLong(PREF_EXPIRY_DATE, 0L)

        if (wasPremium && oldExpiryDate > System.currentTimeMillis()) {
            val securePrefs = getSecurePrefs(context)
            securePrefs.edit()
                .putBoolean(PREF_IS_PREMIUM, true)
                .putLong(PREF_EXPIRY_DATE, oldExpiryDate)
                .apply()

            oldPrefs.edit()
                .remove(PREF_IS_PREMIUM)
                .remove(PREF_EXPIRY_DATE)
                .apply()

            val deviceId = getDeviceId(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                    val connection = url.openConnection() as HttpURLConnection
                    // FIX: Gunakan POST dengan Override PATCH agar stabil di semua Android
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true

                    val jsonPayload = JSONObject().apply {
                        put("status", "aktif")
                        put("expired_at", oldExpiryDate)
                        put("last_update", "Migrasi Otomatis dari Aplikasi Lama")
                    }.toString()

                    connection.outputStream.use { os ->
                        val input = jsonPayload.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }
                    
                    // FIX: Wajib panggil responseCode agar request benar-benar dikirim!
                    val res = connection.responseCode
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    if (response != "null") {
                        val jsonResponse = JSONObject(response)
                        val dbStatus = jsonResponse.optString("status", "")
                        val dbCode = jsonResponse.optString("code", "")
                        val dbExpired = jsonResponse.optLong("expired_at", 0L)

                        if (dbStatus == "banned") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Device ini telah di-banned oleh Admin!") }
                            return@launch
                        }

                        if (dbCode == inputCode) {
                            if (System.currentTimeMillis() < dbExpired) {
                                val securePrefs = getSecurePrefs(context)
                                securePrefs.edit()
                                    .putBoolean(PREF_IS_PREMIUM, true)
                                    .putLong(PREF_EXPIRY_DATE, dbExpired)
                                    .apply() 
                                
                                lastCheckTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).post { 
                                    onResult(true, "Aktivasi Kode VIP Berhasil")
                                }
                            } else {
                                Handler(Looper.getMainLooper()).post { onResult(false, "Masa aktif kode VIP ini sudah kadaluarsa!") }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Kode VIP tidak valid / bukan milik Device ini!") }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post { onResult(false, "Device belum terdaftar. Silakan beli akses VIP ke Admin.") }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    fun activatePromoWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode Promo tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val promoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val promoConn = promoUrl.openConnection() as HttpURLConnection
                promoConn.requestMethod = "GET"
                promoConn.connectTimeout = 5000 
                promoConn.readTimeout = 5000

                if (promoConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal menghubungi Server Promo.") }
                    return@launch
                }

                val promoResponse = promoConn.inputStream.bufferedReader().use { it.readText() }
                if (promoResponse == "null") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kode Promo tidak ditemukan!") }
                    return@launch
                }

                val jsonPromo = JSONObject(promoResponse)
                val status = jsonPromo.optString("status", "")
                val maxQuota = jsonPromo.optInt("max_quota", 0)
                val usedCount = jsonPromo.optInt("used_count", 0)
                val days = jsonPromo.optInt("days", 0)
                val validUntil = jsonPromo.optLong("valid_until", 0L)

                // === VALIDASI SINKRONISASI ADMIN PANEL ===
                if (status != "aktif") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kode Promo sedang tidak aktif!") }
                    return@launch
                }
                if (usedCount >= maxQuota) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Maaf, Kuota Kode Promo ini sudah habis!") }
                    return@launch
                }
                if (System.currentTimeMillis() > validUntil) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Maaf, Masa berlaku Kode Promo ini sudah habis!") }
                    return@launch
                }
                // =========================================

                // 1. Tandai Promo Digunakan di Akun User
                val markPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json")
                val markPromoConn = markPromoUrl.openConnection() as HttpURLConnection
                markPromoConn.requestMethod = "PUT"
                markPromoConn.setRequestProperty("Content-Type", "application/json")
                markPromoConn.doOutput = true
                markPromoConn.outputStream.use { it.write("true".toByteArray(Charsets.UTF_8)) }
           
                if (markPromoConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal! Promo sudah pernah diklaim.") }
                    return@launch
                }

                // 2. Potong Kuota di Database Promo
                val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                // FIX: Gunakan POST dengan Override PATCH + Panggil Response Code
                updatePromoConn.requestMethod = "POST"
                updatePromoConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updatePromoConn.setRequestProperty("Content-Type", "application/json")
                updatePromoConn.doOutput = true
                
                val promoPatch = JSONObject().apply { 
                    put("used_count", usedCount + 1)
                }.toString()
   
                updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                
                // FIX: Ini sangat wajib agar koneksi pool tidak menggantung!
                val promoUpdateRes = updatePromoConn.responseCode
                if (promoUpdateRes != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal memotong kuota promo (Error: $promoUpdateRes)") }
                    return@launch
                }
                
                // 3. Ambil Expiry Date User Saat Ini
                val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val userConn = userUrl.openConnection() as HttpURLConnection
                userConn.requestMethod = "GET"
                var baseTimestamp = System.currentTimeMillis()
                
                if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                     val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                     if (userRes != "null") {
                         val jsonUser = JSONObject(userRes)
                         val dbExpired = jsonUser.optLong("expired_at", 0L)
                         if (dbExpired > baseTimestamp) baseTimestamp = dbExpired 
                     }
                }

                val newExpiredTimestamp = baseTimestamp + (days * 24L * 60L * 60L * 1000L)

                // 4. Update Status User menjadi Aktif & VIP
                val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                // FIX: Gunakan POST dengan Override PATCH 
                updateUserConn.requestMethod = "POST"
                updateUserConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updateUserConn.setRequestProperty("Content-Type", "application/json")
                updateUserConn.doOutput = true
                
                val userPatch = JSONObject().apply {
                    put("status", "aktif")
                    put("account_type", "vip")
                    put("expired_at", newExpiredTimestamp)
                    put("last_update", "Redeemed Promo: $inputCode")
                }.toString()
                
                updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                
                val finalUserRes = updateUserConn.responseCode
                if (finalUserRes == HttpURLConnection.HTTP_OK) {
                    val securePrefs = getSecurePrefs(context)
                    securePrefs.edit()
                        .putBoolean(PREF_IS_PREMIUM, true)
                        .putLong(PREF_EXPIRY_DATE, newExpiredTimestamp)
                        .apply() 

                    lastCheckTime = System.currentTimeMillis()
                    
                    // Force Restart UI & Load Plugins
                    Handler(Looper.getMainLooper()).post { 
                        Toast.makeText(context, "Selamat! Promo $days Hari Berhasil Diklaim.", Toast.LENGTH_LONG).show()
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                        context.startActivity(mainIntent)
                        Runtime.getRuntime().exit(0)
                    }
                } else {
                    // FIX: Tampilkan error code spesifik agar mudah dilacak jika terjadi lagi
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal sinkronisasi user! (Error: $finalUserRes)") }
                }
                
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout: ${e.message}") }
            }
        }
    }

    fun isPremium(context: Context): Boolean {
        val securePrefs = getSecurePrefs(context)
        val isPremium = securePrefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = securePrefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            if (System.currentTimeMillis() > expiryDate) {
                deactivatePremium(context) 
                return false
            }

            if (System.currentTimeMillis() - lastCheckTime > 5 * 60 * 1000) {
                lastCheckTime = System.currentTimeMillis()
                checkAndSyncWithServer(context, getDeviceId(context))
            }
            return true
        }
        return false
    }

    fun deactivatePremium(context: Context) {
        val securePrefs = getSecurePrefs(context)
        securePrefs.edit()
            .putBoolean(PREF_IS_PREMIUM, false)
            .putLong(PREF_EXPIRY_DATE, 0)
            .apply() 
    }
    
    fun getExpiryDateString(context: Context): String {
        val securePrefs = getSecurePrefs(context)
        val date = securePrefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Gratis" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }
    
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    if (response == "null") {
                        registerUserToServer(context, deviceId)
                    } else {
                        val jsonResponse = JSONObject(response)
                        val dbStatus = jsonResponse.optString("status", "")
                        val dbExpired = jsonResponse.optLong("expired_at", 0L)

                        val securePrefs = getSecurePrefs(context)
                        val wasPremium = securePrefs.getBoolean(PREF_IS_PREMIUM, false)
                        
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" && dbExpired > 0L && System.currentTimeMillis() > dbExpired
                       
                        if (isBanned || isExpired) {
                            if (wasPremium) {
                                deactivatePremium(context) 
                                Handler(Looper.getMainLooper()).post {
                                    val pesan = if (isBanned) "⛔ AKSES PREMIUM DICABUT OLEH ADMIN!" else "⚠️ Masa Aktif Premium Habis."
                                    Toast.makeText(context, pesan, Toast.LENGTH_LONG).show()
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                    context.startActivity(mainIntent)
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                        } else if (dbStatus == "aktif" && dbExpired > 0L) {
                            securePrefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
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
            // FIX: Gunakan POST dengan Override PATCH 
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val jsonPayload = JSONObject().apply {
                put("status", "aktif")
                put("last_update", "Auto-Sync from Android App")
            }.toString()
            
            connection.outputStream.use { os ->
                val input = jsonPayload.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                println("PremiumManager: Registrasi awal gagal dengan kode $responseCode")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
