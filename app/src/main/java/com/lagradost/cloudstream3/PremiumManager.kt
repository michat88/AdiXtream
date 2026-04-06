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
    private const val PREF_IS_PREMIUM   = "is_premium_user"
    private const val PREF_EXPIRY_DATE  = "premium_expiry_date"

    // FIX BUG 3: Semua URL Firebase dipusatkan di satu konstanta.
    // Kalau URL berubah, cukup ganti di SINI saja.
    private const val FIREBASE_BASE_URL =
        "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/users"

    // Interval sync background: 5 menit
    private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L

    private var lastCheckTime = 0L

    val PREMIUM_REPO_URL = RepoProtector.decode(RepoProtector.PREMIUM_REPO_ENCODED)
    val FREE_REPO_URL    = RepoProtector.decode(RepoProtector.FREE_REPO_ENCODED)

    // =========================================================================
    // Device ID — dikembalikan ke format asli (8 digit angka)
    // agar kompatibel dengan data user yang sudah ada di Firebase.
    // Format baru (DV prefix) dibatalkan karena breaking change.
    // =========================================================================
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
        return abs(androidId.hashCode()).toString().take(8)
    }

    // =========================================================================
    // Fungsi helper: Buat URL per-device
    // =========================================================================
    private fun deviceUrl(deviceId: String) = "$FIREBASE_BASE_URL/${deviceId.trim().uppercase()}.json"

    // =========================================================================
    // Helper: Normalisasi status dari Firebase agar tidak case-sensitive
    // FIX BUG 4: Admin yang salah ketik "Aktif" (kapital) tetap dikenali
    // =========================================================================
    private fun String.normalizeStatus() = this.trim().lowercase()

    // =========================================================================
    // VERIFIKASI KODE ONLINE KE FIREBASE
    // Bekerja secara Asynchronous (Background) — tidak memblokir UI.
    // =========================================================================
    fun activatePremiumWithCode(
        context:  Context,
        code:     String,
        deviceId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (code.length != 6) {
            onResult(false, "Format kode harus 6 karakter")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = openGetConnection(deviceUrl(deviceId))

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    if (response.trim() == "null") {
                        // Device belum terdaftar sama sekali di Firebase
                        // FIX BUG 1: Kita TIDAK auto-register di sini.
                        // User yang belum terdaftar berarti belum bayar.
                        postMain { onResult(false, "Device belum terdaftar di Server. Hubungi Admin.") }
                        return@launch
                    }

                    val json      = JSONObject(response)
                    val dbStatus  = json.optString("status", "").normalizeStatus()
                    val dbCode    = json.optString("code", "")
                    val dbExpired = json.optLong("expired_at", 0L)

                    when {
                        dbStatus == "banned" -> {
                            postMain { onResult(false, "Device ini telah di-banned oleh Admin!") }
                        }
                        dbCode.trim().uppercase() != code.trim().uppercase() -> {
                            postMain { onResult(false, "Kode salah / tidak valid untuk Device ini!") }
                        }
                        System.currentTimeMillis() >= dbExpired -> {
                            postMain { onResult(false, "Masa aktif kode ini sudah kadaluarsa!") }
                        }
                        else -> {
                            // Semua valid → simpan lokal
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            prefs.edit().apply {
                                putBoolean(PREF_IS_PREMIUM, true)
                                putLong(PREF_EXPIRY_DATE, dbExpired)
                                apply()
                            }
                            lastCheckTime = System.currentTimeMillis()
                            postMain { onResult(true, "Aktivasi Berhasil") }
                        }
                    }
                } else {
                    postMain { onResult(false, "Gagal menghubungi Server (Error ${connection.responseCode})") }
                }
            } catch (e: Exception) {
                postMain { onResult(false, "Koneksi Internet Error / Timeout") }
            }
        }
    }

    // =========================================================================
    // CEK STATUS PREMIUM — Optimistic UI (disengaja, tidak blocking)
    //
    // Return langsung berdasarkan data lokal, lalu sync ke server
    // di background setiap 5 menit. Auto-kick bekerja 1-5 detik kemudian.
    // Ini trade-off UX yang disengaja agar tidak freeze saat pindah menu.
    // =========================================================================
    fun isPremium(context: Context): Boolean {
        val prefs      = PreferenceManager.getDefaultSharedPreferences(context)
        val isPremium  = prefs.getBoolean(PREF_IS_PREMIUM, false)
        val expiryDate = prefs.getLong(PREF_EXPIRY_DATE, 0L)

        if (!isPremium) return false

        // Cek expiry lokal dulu (tidak butuh network)
        if (System.currentTimeMillis() > expiryDate) {
            deactivatePremium(context)
            return false
        }

        // Background sync setiap SYNC_INTERVAL_MS
        if (System.currentTimeMillis() - lastCheckTime > SYNC_INTERVAL_MS) {
            lastCheckTime = System.currentTimeMillis()
            checkAndSyncWithServer(context, getDeviceId(context))
        }

        return true
    }

    // =========================================================================
    // DEAKTIVASI PREMIUM LOKAL
    // =========================================================================
    fun deactivatePremium(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            putBoolean(PREF_IS_PREMIUM, false)
            putLong(PREF_EXPIRY_DATE, 0L)
            apply()
        }
    }

    // =========================================================================
    // AMBIL TANGGAL EXPIRY SEBAGAI STRING
    // =========================================================================
    fun getExpiryDateString(context: Context): String {
        val date = PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(PREF_EXPIRY_DATE, 0L)
        return if (date == 0L) "Non-Premium"
        else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(date))
    }

    // =========================================================================
    // SYNC BACKGROUND KE SERVER
    //
    // FIX BUG 1: Fungsi registerUserToServer() DIHAPUS total.
    //   Sebelumnya, kalau user belum ada di Firebase, app otomatis mendaftarkan
    //   mereka dengan status "aktif" dan expired_at dari lokal (yang bisa = 0).
    //   Ini salah — user yang belum terdaftar berarti belum bayar.
    //   Sekarang: kalau Firebase return "null", kita cukup deactivate lokal.
    //
    // FIX BUG 4: Status dinormalisasi lowercase sebelum dibandingkan,
    //   sehingga "Aktif", "AKTIF", "aktif" semua dikenali dengan benar.
    // =========================================================================
    private fun checkAndSyncWithServer(context: Context, deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = openGetConnection(deviceUrl(deviceId))

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@launch

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                if (response.trim() == "null") {
                    // FIX BUG 1: User tidak ditemukan di server → cabut akses lokal
                    // (bukan auto-register seperti sebelumnya)
                    val wasPremium = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(PREF_IS_PREMIUM, false)
                    if (wasPremium) {
                        deactivatePremium(context)
                        postMain {
                            Toast.makeText(
                                context,
                                "⛔ Data tidak ditemukan di Server. Akses dicabut.",
                                Toast.LENGTH_LONG
                            ).show()
                            restartApp(context)
                        }
                    }
                    return@launch
                }

                val json      = JSONObject(response)
                // FIX BUG 4: normalizeStatus() → trim + lowercase
                val dbStatus  = json.optString("status", "").normalizeStatus()
                val dbExpired = json.optLong("expired_at", 0L)

                val prefs      = PreferenceManager.getDefaultSharedPreferences(context)
                val wasPremium = prefs.getBoolean(PREF_IS_PREMIUM, false)

                val isBanned  = dbStatus == "banned"
                val isExpired = dbStatus == "aktif" && dbExpired > 0L &&
                        System.currentTimeMillis() > dbExpired

                when {
                    isBanned || isExpired -> {
                        if (wasPremium) {
                            deactivatePremium(context)
                            postMain {
                                val pesan = if (isBanned)
                                    "⛔ AKSES PREMIUM DICABUT OLEH ADMIN!"
                                else
                                    "⚠️ Masa Aktif Premium Habis. Yuk perpanjang lagi!"
                                Toast.makeText(context, pesan, Toast.LENGTH_LONG).show()
                                restartApp(context)
                            }
                        }
                    }
                    dbStatus == "aktif" && dbExpired > 0L -> {
                        // Sinkronkan masa aktif lokal dengan server secara diam-diam
                        prefs.edit().putLong(PREF_EXPIRY_DATE, dbExpired).apply()
                    }
                    // Status lain yang tidak dikenali: abaikan saja (tidak restart, tidak crash)
                }
            } catch (_: Exception) {
                // Gagal koneksi saat sync background → abaikan, coba lagi nanti
            }
        }
    }

    // =========================================================================
    // HELPER: Buka HTTP GET Connection
    // =========================================================================
    private fun openGetConnection(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod  = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout    = 5000
        return connection
    }

    // =========================================================================
    // HELPER: Post ke Main Thread
    // =========================================================================
    private fun postMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    // =========================================================================
    // HELPER: Restart Aplikasi
    // =========================================================================
    private fun restartApp(context: Context) {
        val intent     = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
        context.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }
}
