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

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private const val SALT = "ADIXTREAM_SECRET_KEY_2026_SECURE" 
    
    // TAHUN PATOKAN (Jangan diubah setelah rilis, atau kode lama tidak valid)
    private const val EPOCH_YEAR = 2025 

    const val PREMIUM_REPO_URL = "https://raw.githubusercontent.com/aldry84/Repo_Premium/refs/heads/builds/repo.json"
    const val FREE_REPO_URL = "https://raw.githubusercontent.com/michat88/Repo_Gratis/refs/heads/builds/repo.json"

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return abs(androidId.hashCode()).toString().take(8)
    }

    /**
     * GENERATE CODE (Hanya untuk Admin)
     * @param deviceId ID Device User
     * @param daysValid Mau aktif berapa hari dari HARI INI? (Misal 30)
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

        // 3. Konversi hari ke Hex (3 digit). Max 4095 hari (sekitar 11 tahun)
        // Contoh: Hari ke-400 -> "190"
        val dateHex = "%03X".format(daysFromEpoch)

        // 4. Buat Signature Keamanan (3 digit)
        // Kita hash DeviceID + DateHex + Salt supaya user gak bisa ngasal ubah DateHex
        val signatureInput = "$deviceId$dateHex$SALT"
        val signatureHash = MessageDigest.getInstance("MD5").digest(signatureInput.toByteArray())
        val signatureHex = signatureHash.joinToString("") { "%02x".format(it) }
            .substring(0, 3).uppercase()

        // 5. Gabungkan: 3 digit Tanggal + 3 digit Signature
        return "$dateHex$signatureHex"
    }

    /**
     * FUNGSI AKTIVASI BARU
     * Sekarang fungsi ini butuh 'code' untuk mendekripsi tanggalnya.
     */
    fun activatePremiumWithCode(context: Context, code: String, deviceId: String): Boolean {
        // Validasi panjang kode
        if (code.length != 6) return false

        val inputCode = code.uppercase()
        val datePartHex = inputCode.substring(0, 3) // 3 Digit pertama (Tanggal)
        val sigPartHex = inputCode.substring(3, 6)  // 3 Digit terakhir (Keamanan)

        // 1. Cek Validitas Signature (Anti Cheat)
        // Kita hitung ulang hash-nya, apakah cocok dengan 3 digit terakhir?
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
                // Kode benar, tapi masa aktifnya sudah habis
                return false 
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

    fun isPremium(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            if (System.currentTimeMillis() > expiryDate) {
                deactivatePremium(context)
                return false
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
}
