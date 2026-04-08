package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.preference.PreferenceManager
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
    
    // === URL FIREBASE YANG SUDAH TERSEMBUNYI ===
    val FIREBASE_BASE_URL = RepoProtector.decode(RepoProtector.FIREBASE_URL_ENCODED)

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    /**
     * VERIFIKASI KODE ONLINE KE FIREBASE (MENDUKUNG KODE PERSONAL & PROMO)
     */
    fun activatePremiumWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ==========================================================
                // TAHAP 1: CEK APAKAH INI KODE PROMO / REDEEM CODE
                // ==========================================================
                val promoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val promoConn = promoUrl.openConnection() as HttpURLConnection
                promoConn.requestMethod = "GET"
                promoConn.connectTimeout = 5000 
                promoConn.readTimeout = 5000

                if (promoConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val promoResponse = promoConn.inputStream.bufferedReader().use { it.readText() }
                    
                    if (promoResponse != "null") {
                        // KODE PROMO DITEMUKAN!
                        val jsonPromo = JSONObject(promoResponse)
                        val maxQuota = jsonPromo.optInt("max_quota", 0)
                        val usedCount = jsonPromo.optInt("used_count", 0)
                        val days = jsonPromo.optInt("days", 0)
                        
                        // Cek Kuota
                        if (usedCount >= maxQuota) {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Maaf, Kuota kode promo ini sudah habis!") }
                            return@launch
                        }

                        // Cek apakah user ini sudah pernah pakai promo yang sama (Anti Tuyul)
                        val checkUserPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json")
                        val checkUserPromoConn = checkUserPromoUrl.openConnection() as HttpURLConnection
                        checkUserPromoConn.requestMethod = "GET"
                        val userPromoRes = checkUserPromoConn.inputStream.bufferedReader().use { it.readText() }
                        
                        if (userPromoRes != "null" && userPromoRes == "true") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Anda sudah pernah mengklaim kode promo ini!") }
                            return@launch
                        }

                        // Proses Klaim Promo Berhasil!
                        // 1. Cek masa aktif user saat ini (biar sisa harinya ditambahkan/akumulasi)
                        val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                        val userConn = userUrl.openConnection() as HttpURLConnection
                        userConn.requestMethod = "GET"
                        var baseTimestamp = System.currentTimeMillis()
                        
                        if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                             val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                             if (userRes != "null") {
                                 val jsonUser = JSONObject(userRes)
                                 val dbExpired = jsonUser.optLong("expired_at", 0L)
                                 if (dbExpired > baseTimestamp) {
                                     baseTimestamp = dbExpired // Lanjutkan dari sisa hari sebelumnya
                                 }
                             }
                        }

                        val newExpiredTimestamp = baseTimestamp + (days * 24L * 60L * 60L * 1000L)

                        // 2. Tambah angka "Terpakai" (used_count) di Laci Promo
                        val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                        val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                        updatePromoConn.requestMethod = "PATCH"
                        updatePromoConn.setRequestProperty("Content-Type", "application/json")
                        updatePromoConn.doOutput = true
                        val promoPatch = JSONObject().apply { put("used_count", usedCount + 1) }.toString()
                        updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                        updatePromoConn.responseCode 

                        // 3. Update status VIP & masa aktif di Laci User
                        val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                        val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                        updateUserConn.requestMethod = "PATCH"
                        updateUserConn.setRequestProperty("Content-Type", "application/json")
                        updateUserConn.doOutput = true
                        val userPatch = JSONObject().apply {
                            put("status", "aktif")
                            put("expired_at", newExpiredTimestamp)
                            put("last_update", "Redeemed Promo: $inputCode")
                        }.toString()
                        updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                        updateUserConn.responseCode 

                        // 4. Tandai bahwa user ini sudah pakai promo ini
                        val markPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos.json")
                        val markPromoConn = markPromoUrl.openConnection() as HttpURLConnection
                        markPromoConn.requestMethod = "PATCH"
                        markPromoConn.setRequestProperty("Content-Type", "application/json")
                        markPromoConn.doOutput = true
                        val markPatch = JSONObject().apply { put(inputCode, true) }.toString()
                        markPromoConn.outputStream.use { it.write(markPatch.toByteArray(Charsets.UTF_8)) }
                        markPromoConn.responseCode

                        // 5. Simpan di Lokal & Selesai
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit().apply {
                            putBoolean(PREF_IS_PREMIUM, true)
                            putLong(PREF_EXPIRY_DATE, newExpiredTimestamp) 
                            apply()
                        }
                        lastCheckTime = System.currentTimeMillis()
                        Handler(Looper.getMainLooper()).post { onResult(true, "Selamat! Promo $days Hari Berhasil Diklaim.") }
                        
                        return@launch // Hentikan proses, jangan lanjut ke kode personal
                    }
                }

                // ==========================================================
                // TAHAP 2: JIKA BUKAN PROMO, CEK SEBAGAI KODE PERSONAL NORMAL
                // ==========================================================
                val urlString = "${FIREBASE_BASE_URL}users/$deviceId.json"
                val url = URL(urlString)
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
                                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                                prefs.edit().apply {
                                    putBoolean(PREF_IS_PREMIUM, true)
                                    putLong(PREF_EXPIRY_DATE, dbExpired) 
                                    apply()
                                }
                                lastCheckTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).post { onResult(true, "Aktivasi Kode Pribadi Berhasil") }
                            } else {
                                Handler(Looper.getMainLooper()).post { onResult(false, "Masa aktif kode ini sudah kadaluarsa!") }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Kode tidak valid / bukan milik Device ini!") }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post { onResult(false, "Device/Kode belum terdaftar. Hubungi Admin.") }
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal menghubungi Server (Error ${connection.responseCode})") }
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    fun isPremium(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0)
        
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().apply {
            putBoolean(PREF_IS_PREMIUM, false)
            putLong(PREF_EXPIRY_DATE, 0)
            apply()
        }
    }
    
    fun getExpiryDateString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val date = prefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Non-Premium" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }
    
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlString = "${FIREBASE_BASE_URL}users/$deviceId.json"
                val url = URL(urlString)
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

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val wasPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
                        
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" && dbExpired > 0L && System.currentTimeMillis() > dbExpired
                        
                        if (isBanned || isExpired) {
                            if (wasPremium) {
                                deactivatePremium(context) 
                                
                                Handler(Looper.getMainLooper()).post {
                                    val pesan = if (isBanned) {
                                        "⛔ AKSES PREMIUM DICABUT OLEH ADMIN!"
                                    } else {
                                        "⚠️ Masa Aktif Premium Habis. Yuk perpanjang lagi!"
                                    }
                                    
                                    Toast.makeText(context, pesan, Toast.LENGTH_LONG).show()
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                    context.startActivity(mainIntent)
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                        } else if (dbStatus == "aktif" && dbExpired > 0L) {
                            prefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun registerUserToServer(context: Context, deviceId: String) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val localExpiry = prefs.getLong(PREF_EXPIRY_DATE, 0L)

            val urlString = "${FIREBASE_BASE_URL}users/$deviceId.json"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH" 
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val jsonPayload = JSONObject().apply {
                put("status", "aktif")
                put("expired_at", localExpiry)
                put("last_update", "Auto-Sync from Android App")
            }.toString()
            
            connection.outputStream.use { os ->
                val input = jsonPayload.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            connection.responseCode 
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
