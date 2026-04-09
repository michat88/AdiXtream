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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
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
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    // ==========================================================
    // FUNGSI KHUSUS: ENCRYPTED SHARED PREFERENCES (ANTI-HACK)
    // ==========================================================
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

    // ==========================================================
    // FUNGSI MIGRASI: MENYELAMATKAN USER OFFLINE LAMA
    // ==========================================================
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun checkAndMigrateOldOfflineUser(context: Context) {
        val deviceId = getDeviceId(context)
        
        // ⚠️ PERHATIAN: Ganti "premium_code_offline" dengan nama Key yang kamu 
        // gunakan di versi sebelumnya saat menyimpan kode VIP ke SharedPreferences!
        val oldPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val oldCode = oldPrefs.getString("premium_code_offline", null) ?: return 

        if (oldCode.length != 6) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Ekstrak data dari kode lama (Sesuai logika HTML)
                val dateHex = oldCode.substring(0, 3)
                val signatureHex = oldCode.substring(3, 6)

                val salt = "ADIXTREAM_SECRET_KEY_2026_SECURE"
                val signatureInput = "$deviceId$dateHex$salt"
                val expectedSignature = md5(signatureInput).uppercase(Locale.getDefault()).substring(0, 3)

                // 2. Validasi Keaslian Kode
                if (signatureHex != expectedSignature) return@launch // Kode palsu

                // 3. Hitung Tanggal Expired dari dateHex
                val daysFromEpoch = dateHex.toInt(16)
                
                // Set Epoch Date ke 1 Januari 2025 (Sesuai HTML)
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calendar.set(2025, 0, 1, 0, 0, 0)
                val epochMillis = calendar.timeInMillis

                val expiredTimestamp = epochMillis + (daysFromEpoch * 24L * 60L * 60L * 1000L)

                // 4. Cek apakah sudah hangus
                if (System.currentTimeMillis() > expiredTimestamp) {
                    oldPrefs.edit().remove("premium_code_offline").apply()
                    return@launch
                }

                // 5. EKSEKUSI MIGRASI KE FIREBASE SECARA DIAM-DIAM
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PATCH"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonPayload = JSONObject().apply {
                    put("status", "aktif")
                    put("code", oldCode) // Masukkan kode lama agar admin tau
                    put("account_type", "vip")
                    put("expired_at", expiredTimestamp)
                    put("last_update", "Migrasi Otomatis dari Offline (Android)")
                }.toString()

                connection.outputStream.use { os ->
                    val input = jsonPayload.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    // 6. SUKSES! Simpan ke sistem Encrypted baru
                    val securePrefs = getSecurePrefs(context)
                    securePrefs.edit()
                        .putBoolean(PREF_IS_PREMIUM, true)
                        .putLong(PREF_EXPIRY_DATE, expiredTimestamp)
                        .apply()

                    // 7. Hapus kode lama agar fungsi ini tidak berjalan berulang-ulang
                    oldPrefs.edit().remove("premium_code_offline").apply()
                    
                    println("AdiXtream: Migrasi sukses untuk user $deviceId")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ==========================================================
    // FUNGSI 1: AKTIVASI KODE VIP PERSONAL
    // ==========================================================
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
    // FUNGSI 2: AKTIVASI KODE PROMO UMUM (SUDAH ANTI RACE-CONDITION)
    // ==========================================================
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

                val markPromoUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json")
                val markPromoConn = markPromoUrl.openConnection() as HttpURLConnection
                markPromoConn.requestMethod = "PUT"
                markPromoConn.setRequestProperty("Content-Type", "application/json")
                markPromoConn.doOutput = true
                markPromoConn.outputStream.use { it.write("true".toByteArray(Charsets.UTF_8)) }
                
                if (markPromoConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal! Promo sudah pernah diklaim, habis kuota, atau kadaluarsa.") }
                    return@launch
                }

                val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json")
                val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                updatePromoConn.requestMethod = "PATCH"
                updatePromoConn.setRequestProperty("Content-Type", "application/json")
                updatePromoConn.doOutput = true
                
                val promoPatch = JSONObject().apply { 
                    put("used_count", usedCount + 1)
                    put("days", days)
                    put("max_quota", maxQuota)
                    put("valid_until", validUntil)
                    put("status", status)
                }.toString()
                
                updatePromoConn.outputStream.use { it.write(promoPatch.toByteArray(Charsets.UTF_8)) }
                
                if (updatePromoConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal sinkronisasi kuota promo. Coba lagi.") }
                    return@launch
                }

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
                             baseTimestamp = dbExpired 
                         }
                     }
                }

                val newExpiredTimestamp = baseTimestamp + (days * 24L * 60L * 60L * 1000L)

                val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
                val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                updateUserConn.requestMethod = "PATCH"
                updateUserConn.setRequestProperty("Content-Type", "application/json")
                updateUserConn.doOutput = true
                
                val userPatch = JSONObject().apply {
                    put("expired_at", newExpiredTimestamp)
                    put("last_update", "Redeemed Promo: $inputCode")
                }.toString()
                
                updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                
                if (updateUserConn.responseCode != HttpURLConnection.HTTP_OK) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Gagal mengupdate masa aktif di server!") }
                    return@launch
                }

                val securePrefs = getSecurePrefs(context)
                securePrefs.edit()
                    .putBoolean(PREF_IS_PREMIUM, true)
                    .putLong(PREF_EXPIRY_DATE, newExpiredTimestamp)
                    .apply() 

                lastCheckTime = System.currentTimeMillis()
                Handler(Looper.getMainLooper()).post { onResult(true, "Selamat! Promo $days Hari Berhasil Diklaim.") }
                
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    // ==========================================================
    // FUNGSI 3: CEK STATUS PREMIUM
    // ==========================================================
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
            } catch (e: Exception) {
                // Abaikan error jaringan saat sinkronisasi
            }
        }
    }

    // ==========================================================
    // FUNGSI 5: DAFTARKAN DEVICE ID BARU
    // ==========================================================
    private fun registerUserToServer(context: Context, deviceId: String) {
        try {
            val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH" 
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
