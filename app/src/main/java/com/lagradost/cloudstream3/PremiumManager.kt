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
    // Kunci untuk menyimpan data di penyimpanan lokal HP (SharedPreferences)
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private var lastCheckTime = 0L

    // Mengambil URL asli dengan men-decode Base64 dari RepoProtector
    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)
    val FIREBASE_BASE_URL = RepoProtector.decode(RepoProtector.FIREBASE_URL_ENCODED)

    /**
     * Membuat ID Unik untuk setiap HP pengguna berdasarkan ANDROID_ID.
     * Dibatasi hanya 8 karakter agar mudah dibaca di Dashboard Admin.
     */
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    // ==========================================================
    // FUNGSI 1: AKTIVASI KODE VIP PERSONAL (DIBUAT DARI DASHBOARD)
    // ==========================================================
    fun activatePremiumWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Mengecek data user di Firebase
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

                        // Cek apakah user dibanned
                        if (dbStatus == "banned") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Device ini telah di-banned oleh Admin!") }
                            return@launch
                        }

                        // Validasi Kode dan Masa Aktif
                        if (dbCode == inputCode) {
                            if (System.currentTimeMillis() < dbExpired) {
                                // Simpan status premium ke HP user
                                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                                prefs.edit()
                                    .putBoolean(PREF_IS_PREMIUM, true)
                                    .putLong(PREF_EXPIRY_DATE, dbExpired)
                                    .commit() 
                                
                                lastCheckTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).post { onResult(true, "Aktivasi Kode VIP Berhasil") }
                            } else {
                                Handler(Looper.getMainLooper()).post { onResult(false, "Masa aktif kode VIP ini sudah kadaluarsa!") }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Kode VIP tidak valid / bukan milik Device ini!") }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post { onResult(false, "Device belum terdaftar. Silakan beli akses VIP ke Admin.") }
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal menghubungi Server (Error ${connection.responseCode})") }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    // ==========================================================
    // FUNGSI 2: AKTIVASI KODE PROMO UMUM
    // ==========================================================
    fun activatePromoWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode Promo tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Cek Data Promo di Firebase
                val promoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val promoConn = promoUrl.openConnection() as HttpURLConnection
                promoConn.requestMethod = "GET"
                promoConn.connectTimeout = 5000 
                promoConn.readTimeout = 5000

                if (promoConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val promoResponse = promoConn.inputStream.bufferedReader().use { it.readText() }
                    
                    if (promoResponse != "null") {
                        val jsonPromo = JSONObject(promoResponse)
                        val maxQuota = jsonPromo.optInt("max_quota", 0)
                        val usedCount = jsonPromo.optInt("used_count", 0)
                        val days = jsonPromo.optInt("days", 0)
                        val validUntil = jsonPromo.optLong("valid_until", 0L)
                        
                        // 2. Cek apakah promo sudah hangus waktunya
                        if (validUntil > 0L && System.currentTimeMillis() > validUntil) {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Maaf, batas waktu klaim kode promo ini sudah habis!") }
                            return@launch
                        }

                        // 3. Cek apakah kuota promo sudah penuh
                        if (usedCount >= maxQuota) {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Maaf, Kuota kode promo ini sudah habis!") }
                            return@launch
                        }

                        // 4. Cek apakah User ini sudah pernah klaim promo yang sama
                        val checkUserPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json")
                        val checkUserPromoConn = checkUserPromoUrl.openConnection() as HttpURLConnection
                        checkUserPromoConn.requestMethod = "GET"
                        val userPromoRes = checkUserPromoConn.inputStream.bufferedReader().use { it.readText() }
                        
                        if (userPromoRes != "null" && userPromoRes == "true") {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Anda sudah pernah mengklaim kode promo ini!") }
                            return@launch
                        }

                        // 5. Kalkulasi Sisa Waktu VIP User
                        val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                        val userConn = userUrl.openConnection() as HttpURLConnection
                        userConn.requestMethod = "GET"
                        var baseTimestamp = System.currentTimeMillis()
                        
                        if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                             val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                             if (userRes != "null") {
                                 val jsonUser = JSONObject(userRes)
                                 val dbExpired = jsonUser.optLong("expired_at", 0L)
                                 // Jika masih aktif, tambahkan hari promo ke sisa hari saat ini
                                 if (dbExpired > baseTimestamp) {
                                     baseTimestamp = dbExpired 
                                 }
                             }
                        }

                        val newExpiredTimestamp = baseTimestamp + (days * 24L * 60L * 60L * 1000L)

                        // 6. Update Kuota Promo (+1)
                        val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                        val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                        updatePromoConn.requestMethod = "PATCH"
                        updatePromoConn.setRequestProperty("Content-Type", "application/json")
                        updatePromoConn.doOutput = true
                        val promoPatch = JSONObject().apply { put("used_count", usedCount + 1) }.toString()
                        updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                        updatePromoConn.responseCode 

                        // 7. Update Masa Aktif User
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

                        // 8. Tandai bahwa user ini sudah klaim promo ini (agar tidak bisa klaim 2 kali)
                        val markPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos.json")
                        val markPromoConn = markPromoUrl.openConnection() as HttpURLConnection
                        markPromoConn.requestMethod = "PATCH"
                        markPromoConn.setRequestProperty("Content-Type", "application/json")
                        markPromoConn.doOutput = true
                        val markPatch = JSONObject().apply { put(inputCode, true) }.toString()
                        markPromoConn.outputStream.use { it.write(markPatch.toByteArray(Charsets.UTF_8)) }
                        markPromoConn.responseCode

                        // 9. Simpan ke HP User
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                            .putBoolean(PREF_IS_PREMIUM, true)
                            .putLong(PREF_EXPIRY_DATE, newExpiredTimestamp)
                            .commit() 

                        lastCheckTime = System.currentTimeMillis()
                        Handler(Looper.getMainLooper()).post { onResult(true, "Selamat! Promo $days Hari Berhasil Diklaim.") }
                    } else {
                        Handler(Looper.getMainLooper()).post { onResult(false, "Kode Promo tidak ditemukan atau salah ketik!") }
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal menghubungi Server Promo.") }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    // ==========================================================
    // FUNGSI 3: CEK STATUS PREMIUM SAAT APLIKASI DIBUKA
    // ==========================================================
    fun isPremium(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            // Jika masa aktif lokal sudah lewat, langsung cabut premium
            if (System.currentTimeMillis() > expiryDate) {
                deactivatePremium(context) 
                return false
            }

            // Sync dengan server setiap 5 menit untuk mencegah bypass
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
        prefs.edit()
            .putBoolean(PREF_IS_PREMIUM, false)
            .putLong(PREF_EXPIRY_DATE, 0)
            .commit() 
    }
    
    fun getExpiryDateString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val date = prefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Non-Premium" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }
    
    // ==========================================================
    // FUNGSI 4: SINKRONISASI KEAMANAN (BACKGROUND)
    // ==========================================================
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
                        // Jika tidak ada di database, daftarkan otomatis
                        registerUserToServer(context, deviceId)
                    } else {
                        val jsonResponse = JSONObject(response)
                        val dbStatus = jsonResponse.optString("status", "")
                        val dbExpired = jsonResponse.optLong("expired_at", 0L)

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val wasPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
                        
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" && dbExpired > 0L && System.currentTimeMillis() > dbExpired
                       
                        // Jika di-banned atau sudah habis waktunya di server, matikan akses
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
                                    // Restart aplikasi untuk menerapkan perubahan
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                    context.startActivity(mainIntent)
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                        } else if (dbStatus == "aktif" && dbExpired > 0L) {
                            // Update sisa waktu lokal jika server lebih baru
                            prefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan error jaringan saat sinkronisasi background
            }
        }
    }

    // ==========================================================
    // FUNGSI 5: DAFTARKAN DEVICE ID BARU KE SERVER
    // ==========================================================
    private fun registerUserToServer(context: Context, deviceId: String) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val localExpiry = prefs.getLong(PREF_EXPIRY_DATE, 0L)

            val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
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
