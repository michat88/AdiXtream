package com.lagradost.cloudstream3

import android.content.Context
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object PremiumManager {
    private const val PREF_IS_PREMIUM = "is_premium_user"
    private const val PREF_EXPIRY_DATE = "premium_expiry_date"
    private const val SALT = "ADIXTREAM_SECRET_KEY_2026" // Ganti dengan kata sandi rahasia kamu

    // URL Repo Premium kamu (yang berisi SEMUA plugin)
    const val PREMIUM_REPO_URL = "https://raw.githubusercontent.com/michat88/AdiManuLateri3/refs/heads/builds/repo.json"
    
    // URL Repo Gratis (Cuma LayarKacaProvider) - Harus kamu siapkan JSON-nya terpisah
    // Jika belum ada, biarkan kosong dulu, nanti kita logic manual.
    const val FREE_REPO_URL = "URL_REPO_VERSI_GRATIS_DISINI" 

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        // Ambil 8 angka pertama dari Hash biar mirip screenshot kamu (misal: 14276009)
        return kotlin.math.abs(androidId.hashCode()).toString().take(8)
    }

    // Generate kode unlock berdasarkan Device ID
    // Admin (Kamu) pakai rumus ini untuk bikin kode buat user
    fun generateUnlockCode(deviceId: String): String {
        val input = "$deviceId$SALT"
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        // Convert ke Hex string, ambil 6 karakter pertama biar gampang diketik user
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 6).uppercase()
    }

    fun activatePremium(context: Context, durationDays: Int = 30) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val expiryTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(durationDays.toLong())
        
        prefs.edit().apply {
            putBoolean(PREF_IS_PREMIUM, true)
            putLong(PREF_EXPIRY_DATE, expiryTime)
            apply()
        }
    }

    fun isPremium(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0)
        
        if (isPremium) {
            if (System.currentTimeMillis() > expiryDate) {
                // Sudah kadaluarsa
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
        // HAPUS PLUGIN DISINI
        // Nanti kita panggil logic hapus repo di MainActivity biar thread aman
    }
    
    fun getExpiryDateString(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val date = prefs.getLong(PREF_EXPIRY_DATE, 0)
        return if (date == 0L) "Non-Premium" else java.text.SimpleDateFormat("dd MMM yyyy").format(java.util.Date(date))
    }
}
