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

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    /**
     * VERIFIKASI KODE ONLINE KE FIREBASE
     * Fungsi ini sekarang bekerja secara Asynchronous (Background).
     */
    fun activatePremiumWithCode(context: Context, code: String, deviceId: String, onResult: (Boolean, String) -> Unit) {
        if (code.length != 6) {
            onResult(false, "Format kode harus 6 karakter")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
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

                        // Cek apakah kode cocok dengan database online
                        if (dbCode == code.uppercase()) {
                            if (System.currentTimeMillis() < dbExpired) {
                                // BERHASIL! Simpan data lokal
                                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                                prefs.edit().apply {
                                    putBoolean(PREF_IS_PREMIUM, true)
                                    putLong(PREF_EXPIRY_DATE, dbExpired) 
                                    apply()
                                }
                                lastCheckTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).post { onResult(true, "Aktivasi Berhasil") }
                            } else {
                                Handler(Looper.getMainLooper()).post { onResult(false, "Masa aktif kode ini sudah kadaluarsa!") }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post { onResult(false, "Kode salah / tidak valid untuk Device ini!") }
                        }
                    } else {
                         Handler(Looper.getMainLooper()).post { onResult(false, "Device belum terdaftar di Server. Hubungi Admin.") }
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
                val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
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
                        
                        // KITA PISAHKAN LOGIKA BANNED DAN EXPIRED
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" && dbExpired > 0L && System.currentTimeMillis() > dbExpired
                        
                        // AUTO-KICK SYSTEM 
                        if (isBanned || isExpired) {
                            if (wasPremium) {
                                deactivatePremium(context) // Matikan lisensi lokal
                                
                                Handler(Looper.getMainLooper()).post {
                                    // Pesan dibedakan berdasarkan statusnya
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
                            // Sinkronkan masa aktif lokal dengan server diam-diam
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

            val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
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
