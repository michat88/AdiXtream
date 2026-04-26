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
                .commit() 

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
                        // FIX: Gunakan Long (angka) agar React Native bisa membacanya
                        put("last_update", System.currentTimeMillis())
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
                        val dbExpired = jsonResponse.optLong("expired_at", 0L)

                        if (dbStatus == "banned") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Device ini telah di-banned!") }
                            return@launch
                        }

                        if (dbCode == inputCode) {
                            // FIX: Ambil waktu server untuk mencegah hack jam HP dimundurkan
                            val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()

                            if (serverTime < dbExpired) {
                                val securePrefs = getSecurePrefs(context)
                                securePrefs.edit()
                                    .putBoolean(PREF_IS_PREMIUM, true)
                                    .putLong(PREF_EXPIRY_DATE, dbExpired)
                                    .commit()
                                
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
                // 1. Ambil data promo
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
                val promoValidUntil = jsonPromo.optLong("valid_until")
                
                // FIX: Curi waktu dari server internet agar kebal terhadap HP dengan tahun 1970/2099
                val serverTime = if (promoConn.date > 0) promoConn.date else System.currentTimeMillis()

                if (jsonPromo.optString("status") != "aktif") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Promo tidak aktif!") }
                    return@launch
                }
                if (jsonPromo.optInt("used_count") >= jsonPromo.optInt("max_quota")) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kuota promo habis!") }
                    return@launch
                }
                if (serverTime > promoValidUntil) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Promo sudah kadaluarsa!") }
                    return@launch
                }

                // 2. Tandai Promo Digunakan
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

                // 3. Potong kuota di database
                val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                updatePromoConn.requestMethod = "POST"
                updatePromoConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updatePromoConn.setRequestProperty("Content-Type", "application/json")
                updatePromoConn.doOutput = true
                val promoPatch = JSONObject().apply { put("used_count", jsonPromo.optInt("used_count") + 1) }.toString()
                updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                updatePromoConn.responseCode

                // 4. Hitung masa aktif baru (Berdasarkan Waktu Server)
                val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val userConn = userUrl.openConnection() as HttpURLConnection
                var baseTimestamp = if (userConn.date > 0) userConn.date else System.currentTimeMillis()

                if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                     val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                     if (userRes != "null") {
                         val dbExp = JSONObject(userRes).optLong("expired_at", 0L)
                         if (dbExp > baseTimestamp) baseTimestamp = dbExp 
                     }
                }
                
                val newExpiredTimestamp = baseTimestamp + (jsonPromo.optInt("days") * 24L * 60L * 60L * 1000L)

                // 5. Update user
                val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                updateUserConn.requestMethod = "POST"
                updateUserConn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                updateUserConn.setRequestProperty("Content-Type", "application/json")
                updateUserConn.doOutput = true
                
                val userPatch = JSONObject().apply {
                    put("expired_at", newExpiredTimestamp)
                    // FIX: Kirim data berupa angka (bukan teks) agar React Native tidak error
                    put("last_update", serverTime) 
                }.toString()
                
                updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                
                val finalUserRes = updateUserConn.responseCode
                if (finalUserRes == HttpURLConnection.HTTP_OK) {
                    val securePrefs = getSecurePrefs(context)
                    securePrefs.edit()
                        .putBoolean(PREF_IS_PREMIUM, true)
                        .putLong(PREF_EXPIRY_DATE, newExpiredTimestamp)
                        .commit() 

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
        val securePrefs = getSecurePrefs(context)
        val isPremium = securePrefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = securePrefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            // Cek lokal sementara
            if (System.currentTimeMillis() > expiryDate) {
                deactivatePremium(context) 
                return false
            }
            // Cek silang dengan server tiap 5 menit untuk memastikan tidak ada hack jam
            if (System.currentTimeMillis() - lastCheckTime > 5 * 60 * 1000) {
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
            .commit() 
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
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (response == "null") {
                        registerUserToServer(context, deviceId)
                    } else {
                        val json = JSONObject(response)
                        val dbStatus = json.optString("status", "")
                        val dbExpired = json.optLong("expired_at", 0L)
                        
                        // FIX: Validasi masa kadaluwarsa pakai jam server
                        val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()
                        val securePrefs = getSecurePrefs(context)
                        val wasPremium = securePrefs.getBoolean(PREF_IS_PREMIUM, false)
                        
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
                            securePrefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
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
            
            // Ambil waktu server
            val serverTime = if (connection.date > 0) connection.date else System.currentTimeMillis()

            val jsonPayload = JSONObject().apply {
                put("status", "aktif")
                // FIX: Menambahkan "created_at" agar data terdaftar tampil di dashboard Admin
                put("created_at", serverTime)
                put("last_update", serverTime)
            }.toString()
            
            connection.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (e: Exception) { }
    }
}
