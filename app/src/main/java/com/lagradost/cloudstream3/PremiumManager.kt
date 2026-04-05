package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Import untuk Coroutine (Background Task) & Koneksi HTTP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

// Import wajib agar bisa membaca URL terenkripsi dari RepoProtector
import com.lagradost.cloudstream3.utils.RepoProtector

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    
    // Kunci Rahasia untuk hashing (SALT). Pastikan ini sama dengan yang ada di Generator Admin.
    private const val SALT = "ADIXTREAM_SECRET_KEY_2026_SECURE" 
    
    // TAHUN PATOKAN (Epoch). Jangan diubah setelah aplikasi rilis ke user.
    private const val EPOCH_YEAR = 2025 

    // Timer untuk membatasi request ke Firebase agar tidak spam (Debounce)
    private var lastCheckTime = 0L

    // --- REPOSITORY URLS ---
    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)
    // -----------------------

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    fun generateUnlockCode(deviceId: String, daysValid: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, daysValid)
        val targetDate = calendar.time

        val epochCal = Calendar.getInstance()
        epochCal.set(EPOCH_YEAR, Calendar.JANUARY, 1, 0, 0, 0)
        
        val diffMillis = targetDate.time - epochCal.timeInMillis
        val daysFromEpoch = TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()

        val dateHex = "%03X".format(daysFromEpoch)

        val signatureInput = "$deviceId$dateHex$SALT"
        val signatureHash = MessageDigest.getInstance("MD5").digest(signatureInput.toByteArray())
        val signatureHex = signatureHash.joinToString("") { "%02x".format(it) }
            .substring(0, 3).uppercase()

        return "$dateHex$signatureHex"
    }

    fun activatePremiumWithCode(context: Context, code: String, deviceId: String): Boolean {
        if (code.length != 6) return false

        val inputCode = code.uppercase()
        val datePartHex = inputCode.substring(0, 3) 
        val sigPartHex = inputCode.substring(3, 6)  

        val checkInput = "$deviceId$datePartHex$SALT"
        val checkHashBytes = MessageDigest.getInstance("MD5").digest(checkInput.toByteArray())
        val expectedSig = checkHashBytes.joinToString("") { "%02x".format(it) }
            .substring(0, 3).uppercase()

        if (sigPartHex != expectedSig) {
            return false 
        }

        try {
            val daysFromEpoch = datePartHex.toInt(16) 
            val expiryCal = Calendar.getInstance()
            expiryCal.set(EPOCH_YEAR, Calendar.JANUARY, 1, 0, 0, 0)
            expiryCal.add(Calendar.DAY_OF_YEAR, daysFromEpoch)
            
            val expiryTime = expiryCal.timeInMillis

            if (System.currentTimeMillis() > expiryTime) {
                return false 
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().apply {
                putBoolean(PREF_IS_PREMIUM, true)
                putLong(PREF_EXPIRY_DATE, expiryTime) 
                apply()
            }
            return true

        } catch (e: Exception) {
            return false
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
                        registerUserToServer(deviceId)
                    } else if (response.contains("\"status\":\"banned\"") || response.contains("\"status\": \"banned\"")) {
                        
                        // === ADIXTREAM SECURITY: AUTO-KICK SYSTEM ===
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val wasPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
                        
                        if (wasPremium) {
                            deactivatePremium(context) // Matikan lisensi
                            
                            // Tendang user keluar dan restart aplikasi secara paksa
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "⛔ AKSES PREMIUM DICABUT OLEH ADMIN!", Toast.LENGTH_LONG).show()
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                context.startActivity(mainIntent)
                                Runtime.getRuntime().exit(0)
                            }
                        }
                        // ============================================
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun registerUserToServer(deviceId: String) {
        try {
            val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH" 
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val jsonInputString = "{\"status\": \"aktif\", \"last_update\": \"Auto-Sync from Android App\"}"
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            connection.responseCode 
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
