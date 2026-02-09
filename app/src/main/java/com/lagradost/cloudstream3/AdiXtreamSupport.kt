package com.lagradost.cloudstream3 // ⚠️ Sesuaikan dengan nama package di paling atas MainActivity kamu

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AdiXtreamSupport {

    fun showStartupPopup(context: Context) {
        val saweriaUrl = "https://saweria.co/michat88"

        MaterialAlertDialogBuilder(context)
            .setTitle("☕ Misi Bosque!") 
            .setMessage("Server butuh listrik, Developer butuh kopi, dan Kucing developer butuh Whiskas 🐱.\n\nAplikasi ini gratis, tapi kalau mau bantu kami tetap waras ngoding fitur baru, boleh dong sawer dikit biar semangat!")
            .setCancelable(false) // User tidak bisa klik luar
            .setPositiveButton("Gaskan Traktir! 🚀") { dialog, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(saweriaUrl))
                    // Menambahkan flag agar aman dipanggil dari berbagai konteks
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Doa Saja Dulu 🙏") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
