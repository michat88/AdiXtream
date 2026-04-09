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
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.ktx.auth

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private var lastCheckTime = 0L
    private var firebaseToken: String? = null

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
    // FUNGSI INIT — Firebase Auth & Jembatan Keamanan UID
    // ==========================================================
    fun initFirebaseAuth(context: Context, onReady: () -> Unit) {
        val auth = Firebase.auth
        val deviceId = getDeviceId(context)

        fun afterLogin(uid: String) {
            auth.currentUser!!.getIdToken(false).addOnSuccessListener { result ->
                firebaseToken = result.token
                // Daftarkan mapping uid → deviceId ke Firebase (sekali seumur hidup)
                registerUidMapping(uid, deviceId) {
                    onReady()
                }
            }.addOnFailureListener { onReady() }
        }

        if (auth.currentUser != null) {
            afterLogin(auth.currentUser!!.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    result.user?.uid?.let { afterLogin(it) } ?: onReady()
                }
                .addOnFailureListener { onReady() }
        }
    }

    private fun registerUidMapping(uid: String, deviceId: String, onDone: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Cek dulu apakah mapping sudah ada
                val checkUrl = URL("${FIREBASE_BASE_URL}uid_map/$uid.json?auth=$firebaseToken")
                val checkConn = checkUrl.openConnection() as HttpURLConnection
                checkConn.requestMethod = "GET"
                checkConn.connectTimeout = 5000
                checkConn.readTimeout = 5000

                val response = checkConn.inputStream.bufferedReader().use { it.readText() }

                // Kalau belum ada, daftarkan sekarang (Diizinkan oleh rules data.val() == null)
                if (response == "null") {
                    val writeUrl = URL("${FIREBASE_BASE_URL}uid_map/$uid.json?auth=$firebaseToken")
                    val writeConn = writeUrl.openConnection() as HttpURLConnection
                    writeConn.requestMethod = "PUT"
                    writeConn.setRequestProperty("Content-Type", "application/json")
                    writeConn.doOutput = true
                    writeConn.outputStream.use {
                        it.write("\"$deviceId\"".toByteArray(Charsets.UTF_8))
                    }
                    writeConn.responseCode
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
        }
    }

    // ==========================================================
    // FUNGSI 1: AKTIVASI KODE VIP PERSONAL (DIBUAT DARI DASHBOARD)
    // ==========================================================
    fun activatePremiumWithCode(
        context: Context,
        code: String,
        deviceId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json?auth=$firebaseToken")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    if (response != "null") {
                        val json = JSONObject(response)
                        val dbStatus = json.optString("status", "")
                        val dbCode = json.optString("code", "")
                        val dbExpired = json.optLong("expired_at", 0L)

                        when {
                            dbStatus == "banned" -> {
                                Handler(Looper.getMainLooper()).post {
                                    onResult(false, "Device ini telah di-banned oleh Admin!")
                                }
                            }
                            dbCode != inputCode -> {
                                Handler(Looper.getMainLooper()).post {
                                    onResult(false, "Kode VIP tidak valid / bukan milik Device ini!")
                                }
                            }
                            System.currentTimeMillis() > dbExpired -> {
                                Handler(Looper.getMainLooper()).post {
                                    onResult(false, "Masa aktif kode VIP ini sudah kadaluarsa!")
                                }
                            }
                            else -> {
                                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                                prefs.edit()
                                    .putBoolean(PREF_IS_PREMIUM, true)
                                    .putLong(PREF_EXPIRY_DATE, dbExpired)
                                    .commit()
                                lastCheckTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).post {
                                    onResult(true, "Aktivasi Kode VIP Berhasil")
                                }
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, "Device belum terdaftar. Silakan beli akses VIP ke Admin.")
                        }
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onResult(false, "Gagal menghubungi Server (Error ${connection.responseCode})")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    onResult(false, "Koneksi Internet Error / Timeout")
                }
            }
        }
    }

    // ==========================================================
    // FUNGSI 2: AKTIVASI KODE PROMO UMUM (SUDAH ANTI-SINDROM CINDERELLA)
    // ==========================================================
    fun activatePromoWithCode(
        context: Context,
        code: String,
        deviceId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val inputCode = code.trim().uppercase()
        if (inputCode.isEmpty()) {
            onResult(false, "Kode Promo tidak boleh kosong!")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Cek data promo
                val promoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json?auth=$firebaseToken")
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
                val maxQuota = jsonPromo.optInt("max_quota", 0)
                val usedCount = jsonPromo.optInt("used_count", 0)
                val days = jsonPromo.optInt("days", 0)
                val validUntil = jsonPromo.optLong("valid_until", 0L)

                // 2. Validasi masa berlaku & Kuota promo
                if (validUntil > 0L && System.currentTimeMillis() > validUntil) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Batas waktu klaim kode promo ini sudah habis!") }
                    return@launch
                }
                if (usedCount >= maxQuota) {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Kuota kode promo ini sudah habis!") }
                    return@launch
                }

                // 3. Cek apakah user sudah pernah klaim promo yang sama
                val checkUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json?auth=$firebaseToken")
                val checkConn = checkUrl.openConnection() as HttpURLConnection
                checkConn.requestMethod = "GET"
                val userPromoRes = checkConn.inputStream.bufferedReader().use { it.readText() }

                if (userPromoRes == "true") {
                    Handler(Looper.getMainLooper()).post { onResult(false, "Anda sudah pernah mengklaim kode promo ini!") }
                    return@launch
                }

                // 4. Ambil data User saat ini (Untuk menyambung sisa hari & menyesuaikan dengan Rules)
                val userUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json?auth=$firebaseToken")
                val userConn = userUrl.openConnection() as HttpURLConnection
                userConn.requestMethod = "GET"
                
                var baseTimestamp = System.currentTimeMillis()
                var existingStatus = "aktif"
                var existingCode = "PROMOX" // Wajib 6 karakter agar lolos validate Rules untuk user baru
                var existingExtendCount = 0

                if (userConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val userRes = userConn.inputStream.bufferedReader().use { it.readText() }
                    if (userRes != "null") {
                        val jsonUser = JSONObject(userRes)
                        val dbExpired = jsonUser.optLong("expired_at", 0L)
                        if (dbExpired > baseTimestamp) {
                            baseTimestamp = dbExpired
                        }
                        existingStatus = jsonUser.optString("status", "aktif")
                        val codeDb = jsonUser.optString("code", "")
                        if (codeDb.length == 6) existingCode = codeDb // Pertahankan kode lama jika ada
                        existingExtendCount = jsonUser.optInt("extend_count", 0)
                    }
                }

                // Kalkulasi Expired Baru
                val newExpiredTimestamp = baseTimestamp + (days * 24L * 60L * 60L * 1000L)

                // 5. Update used_count di promo (Wajib +1 agar lolos Rules)
                val updatePromoUrl = URL("${FIREBASE_BASE_URL}promo_codes/$inputCode.json?auth=$firebaseToken")
                val updatePromoConn = updatePromoUrl.openConnection() as HttpURLConnection
                updatePromoConn.requestMethod = "PATCH"
                updatePromoConn.setRequestProperty("Content-Type", "application/json")
                updatePromoConn.doOutput = true
                updatePromoConn.outputStream.use {
                    it.write(JSONObject().apply { put("used_count", usedCount + 1) }.toString().toByteArray(Charsets.UTF_8))
                }
                updatePromoConn.responseCode

                // 6. Update Data User (Semua field wajib dikirim agar lolos Rules: newData.hasChildren)
                // Menggunakan PATCH agar tidak menghapus folder redeemed_promos milik user ini.
                val updateUserUrl = URL("${FIREBASE_BASE_URL}users/$deviceId.json?auth=$firebaseToken")
                val updateUserConn = updateUserUrl.openConnection() as HttpURLConnection
                updateUserConn.requestMethod = "PATCH"
                updateUserConn.setRequestProperty("Content-Type", "application/json")
                updateUserConn.doOutput = true
                val userPatch = JSONObject().apply {
                    put("status", existingStatus)
                    put("code", existingCode)
                    put("expired_at", newExpiredTimestamp)
                    put("extend_count", existingExtendCount)
                    put("last_update", "Redeemed Promo: $inputCode")
                }.toString()
                updateUserConn.outputStream.use { it.write(userPatch.toByteArray(Charsets.UTF_8)) }
                updateUserConn.responseCode

                // 7. Tandai promo sudah diklaim
                val markUrl = URL("${FIREBASE_BASE_URL}users/$deviceId/redeemed_promos/$inputCode.json?auth=$firebaseToken")
                val markConn = markUrl.openConnection() as HttpURLConnection
                markConn.requestMethod = "PUT"
                markConn.setRequestProperty("Content-Type", "application/json")
                markConn.doOutput = true
                markConn.outputStream.use { it.write("true".toByteArray(Charsets.UTF_8)) }
                markConn.responseCode

                // 8. Simpan ke lokal HP user
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                prefs.edit()
                    .putBoolean(PREF_IS_PREMIUM, true)
                    .putLong(PREF_EXPIRY_DATE, newExpiredTimestamp)
                    .commit()

                lastCheckTime = System.currentTimeMillis()
                Handler(Looper.getMainLooper()).post {
                    onResult(true, "Selamat! Promo $days Hari Berhasil Diklaim.")
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    onResult(false, "Koneksi Internet Error / Timeout")
                }
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
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_IS_PREMIUM, false)
            .putLong(PREF_EXPIRY_DATE, 0)
            .commit()
    }

    fun getExpiryDateString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val date = prefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Non-Premium"
               else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }

    // ==========================================================
    // FUNGSI 4: SINKRONISASI KEAMANAN (BACKGROUND)
    // ==========================================================
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${FIREBASE_BASE_URL}users/$deviceId.json?auth=$firebaseToken")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    if (response == "null") {
                        // Jika dihapus dari server, cabut premium lokal.
                        // Kita tidak meregister otomatis karena akan ditolak oleh Rules (expired_at harus > now)
                        deactivatePremium(context)
                    } else {
                        val json = JSONObject(response)
                        val dbStatus = json.optString("status", "")
                        val dbExpired = json.optLong("expired_at", 0L)

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val isBanned = dbStatus == "banned"
                        val isExpired = dbStatus == "aktif" &&
                                        dbExpired > 0L &&
                                        System.currentTimeMillis() > dbExpired

                        when {
                            isBanned || isExpired -> {
                                deactivatePremium(context)
                                Handler(Looper.getMainLooper()).post {
                                    val pesan = if (isBanned)
                                        "⛔ AKSES PREMIUM DICABUT OLEH ADMIN!"
                                    else
                                        "⚠️ Masa Aktif Premium Habis. Yuk perpanjang lagi!"
                                    Toast.makeText(context, pesan, Toast.LENGTH_LONG).show()
                                    val intent = context.packageManager
                                        .getLaunchIntentForPackage(context.packageName)
                                    context.startActivity(
                                        Intent.makeRestartActivityTask(intent?.component)
                                    )
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                            dbStatus == "aktif" && dbExpired > 0L -> {
                                // Update sisa waktu lokal jika server lebih baru
                                prefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan error jaringan saat sinkronisasi background
            }
        }
    }
}
