package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

object AdiXtreamSupport {

    // --- GANTI LINK INI DENGAN LINK RAW GITHUB KAMU ---
    // Pastikan ini link ke file .json kamu (format Raw)
    private const val JSON_URL = "https://raw.githubusercontent.com/USERNAME_GITHUB/REPO_KAMU/main/pengumuman.json"
    
    // Link Saweria tetap (Default)
    private const val SAWERIA_URL = "https://saweria.co/michat88"

    fun showStartupPopup(activity: Activity) {
        // Cek dulu, jangan muncul kalau user buka dari "Lanjutkan Menonton" (Biar ga ganggu)
        if (activity.intent?.data != null) return

        thread {
            try {
                // 1. Baca data dari GitHub
                val jsonText = URL(JSON_URL).readText()
                val jsonObject = JSONObject(jsonText)

                val isAktif = jsonObject.optBoolean("aktif", false)
                
                // Ambil data pengumuman
                val judul = jsonObject.optString("judul", "Info AdiXtream")
                val pesan = jsonObject.optString("pesan", "Halo!")
                val linkTujuan = jsonObject.optString("link", SAWERIA_URL)
                val labelTombol = jsonObject.optString("label_tombol", "Cek Sekarang 🚀")

                activity.runOnUiThread {
                    if (isAktif) {
                        // KASUS A: Ada Pengumuman (Film Baru / Info Maintenance)
                        showDialog(activity, judul, pesan, linkTujuan, labelTombol)
                    } else {
                        // KASUS B: Gak ada pengumuman -> Munculin Saweria Biasa
                        showDefaultDonation(activity)
                    }
                }

            } catch (e: Exception) {
                // Kalau internet error atau file json ga ketemu,
                // Kita tetap munculin Saweria (opsional, atau diam saja)
                activity.runOnUiThread {
                    showDefaultDonation(activity)
                }
            }
        }
    }

    // Tampilan Popup Custom (Sesuai GitHub)
    private fun showDialog(context: Context, title: String, message: String, url: String, btnLabel: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(btnLabel) { _, _ ->
                openLink(context, url)
            }
            .setNegativeButton("Tutup") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // Tampilan Default (Minta Kopi)
    private fun showDefaultDonation(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle("☕ Misi Bosque!")
            .setMessage("Server butuh listrik, Developer butuh kopi ☕.\n\nAplikasi ini gratis, tapi kalau mau bantu kami tetap semangat update, boleh dong sawer dikit!")
            .setCancelable(false)
            .setPositiveButton("Gaskan Traktir! 🚀") { _, _ ->
                openLink(context, SAWERIA_URL)
            }
            .setNegativeButton("Doa Saja 🙏") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun openLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
