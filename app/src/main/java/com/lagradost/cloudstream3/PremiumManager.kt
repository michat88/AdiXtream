package com.lagradost.cloudstream3

import android.content.Context
import android.provider.Settings
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
    // Mengambil URL yang sudah didecode dari RepoProtector agar tidak terbaca plain text
    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)
    // -----------------------

    /**
     * Mendapatkan ID Unik Perangkat User
     * Menggunakan Android ID yang di-hash agar lebih pendek (8 karakter).
     */
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    /**
     * GENERATE CODE (Fungsi ini biasanya hanya dipakai di sisi Admin/Generator)
     * Membuat kode unik berdasarkan Device ID dan durasi hari.
     * @param deviceId ID Device User
     * @param daysValid Mau aktif berapa hari dari HARI INI?
     */
    fun generateUnlockCode(deviceId: String, daysValid: Int): String {
        // 1. Hitung Tanggal Target Expired
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, daysValid)
        val targetDate = calendar.time

        // 2. Hitung selisih hari dari EPOCH (1 Jan 2025)
        val epochCal = Calendar.getInstance()
        epochCal.set(EPOCH_YEAR, Calendar.JANUARY, 1, 0, 0, 0)
        
        val diffMillis = targetDate.time - epochCal.timeInMillis
        val daysFromEpoch = TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()

        // 3. Konversi hari ke Hex (3 digit). Max mencakup sekitar 11 tahun.
        val dateHex = "%03X".format(daysFromEpoch)

        // 4. Buat Signature Keamanan (3 digit)
        // Hash kombinasi DeviceID + DateHex + Salt
        val signatureInput = "$deviceId$dateHex$SALT"
        val signatureHash = MessageDigest.getInstance("MD5").digest(signatureInput.toByteArray())
        val signatureHex = signatureHash.joinToString("") { "%02x".format(it) }
            .substring(0, 3).uppercase()

        // 5. Gabungkan: 3 digit Tanggal + 3 digit Signature
        return "$dateHex$signatureHex"
    }

    /**
     * FUNGSI AKTIVASI (Dipanggil saat user menekan tombol Unlock)
     * Memverifikasi kode yang dimasukkan user.
     */
    fun activatePremiumWithCode(context: Context, code: String, deviceId: String): Boolean {
        // Validasi panjang kode harus 6 karakter
        if (code.length != 6) return false

        val inputCode = code.uppercase()
        val datePartHex = inputCode.substring(0, 3) // 3 Digit pertama (Data Tanggal)
        val sigPartHex = inputCode.substring(3, 6)  // 3 Digit terakhir (Signature Keamanan)

        // 1. Cek Validitas Signature (Anti Cheat)
        // Hitung ulang hash berdasarkan input, apakah cocok dengan signature?
        val checkInput = "$deviceId$datePartHex$SALT"
        val checkHashBytes = MessageDigest.getInstance("MD5").digest(checkInput.toByteArray())
        val expectedSig = checkHashBytes.joinToString("") { "%02x".format(it) }
            .substring(0, 3).uppercase()

        if (sigPartHex != expectedSig) {
            return false // Kode Salah / Palsu / Milik Device Lain
        }

        // 2. Jika Kode Benar, Dekripsi Tanggalnya
        try {
            val daysFromEpoch = datePartHex.toInt(16) // Hex ke Int
            
            // Hitung Tanggal Expired Sebenarnya
            val expiryCal = Calendar.getInstance()
            expiryCal.set(EPOCH_YEAR, Calendar.JANUARY, 1, 0, 0, 0)
            expiryCal.add(Calendar.DAY_OF_YEAR, daysFromEpoch)
            
            val expiryTime = expiryCal.timeInMillis

            // Cek apakah tanggal itu sudah lewat (Expired)?
            if (System.currentTimeMillis() > expiryTime) {
                return false // Kode benar secara format, tapi masa aktifnya sudah lewat
            }

            // 3. Simpan Ke Preferences (Save Permanent Date)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().apply {
                putBoolean(PREF_IS_PREMIUM, true)
                putLong(PREF_EXPIRY_DATE, expiryTime) // Simpan tanggal mati yang absolut
                apply()
            }
            return true

        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Cek Status Premium
     * Mengembalikan true jika user premium DAN belum expired DAN tidak di-Banned.
     */
    fun isPremium(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            // 1. CEK LOKAL: Apakah hari ini sudah melewati tanggal expiry?
            if (System.currentTimeMillis() > expiryDate) {
                deactivatePremium(context) // Otomatis matikan premium jika lewat tanggal
                return false
            }

            // 2. CEK ONLINE SERVER (Sistem Blacklist & Auto-Registration)
            // Hanya mengecek setiap 5 menit agar tidak membebani server/kuota user
            if (System.currentTimeMillis() - lastCheckTime > 5 * 60 * 1000) {
                lastCheckTime = System.currentTimeMillis()
                checkAndSyncWithServer(context, getDeviceId(context))
            }

            return true
        }
        return false
    }

    /**
     * Nonaktifkan Premium (Logout/Expired/Reset)
     */
    fun deactivatePremium(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().apply {
            putBoolean(PREF_IS_PREMIUM, false)
            putLong(PREF_EXPIRY_DATE, 0)
            apply()
        }
    }
    
    /**
     * Helper untuk menampilkan tanggal expiry ke UI (String format)
     */
    fun getExpiryDateString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val date = prefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Non-Premium" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }

    // =======================================================
    // MODIFIKASI ADIXTREAM: FUNGSI KONEKSI DATABASE FIREBASE
    // =======================================================
    
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        // Dijalankan di background thread agar tidak membuat aplikasi lag/hang
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pastikan URL Firebase ini benar!
                val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 // Maksimal nunggu 5 detik
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    if (response == "null") {
                        // KASUS 1: User belum ada di Database. Lakukan AUTO-REGISTRATION!
                        registerUserToServer(deviceId)
                    } else if (response.contains("\"status\":\"banned\"") || response.contains("\"status\": \"banned\"")) {
                        // KASUS 2: User terdeteksi BANNED di Database Admin!
                        // Langsung hancurkan data premium lokalnya secara permanen
                        deactivatePremium(context)
                    }
                    // Jika status "aktif", biarkan saja berjalan normal.
                }
            } catch (e: Exception) {
                // Jika error jaringan (misal user offline / mode pesawat), abaikan pengecekan (Premium tetap jalan sementara)
            }
        }
    }

    private fun registerUserToServer(deviceId: String) {
        try {
            val urlString = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users/$deviceId.json"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH" // Buat/Update data baru
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Tulis status aktif ke Firebase
            val jsonInputString = "{\"status\": \"aktif\", \"last_update\": \"Auto-Sync from Android App\"}"
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            connection.responseCode // Jalankan eksekusi
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
