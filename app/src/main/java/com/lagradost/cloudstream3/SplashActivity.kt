package com.lagradost.cloudstream3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Mengambil referensi ke layout utama dan pemutar video berdasarkan ID
        val splashRoot = findViewById<View>(R.id.splashRoot)
        val videoView = findViewById<VideoView>(R.id.videoView)

        // --- EFEK FADE-IN UNTUK TRANSISI YANG HALUS ---
        // 1. Mengatur layar menjadi transparan (hilang) pada detik pertama
        splashRoot.alpha = 0f

        // 2. Memulai animasi memudar masuk ke opasitas penuh
        splashRoot.animate()
            .alpha(1f) // 1f berarti 100% terlihat
            .setDuration(1000) // Durasi animasi memudar adalah 1 detik (1000 milidetik)
            .start()
        // ----------------------------------------------

        // Mengatur jalur file video animasi dari folder raw
        val videoPath = "android.resource://" + packageName + "/" + R.raw.intro_video
        val uri = Uri.parse(videoPath)
        videoView.setVideoURI(uri)

        // Memutar videonya!
        videoView.start()

        // Mendeteksi kapan video selesai diputar
        videoView.setOnCompletionListener {
            // Berpindah ke halaman utama (AccountSelectActivity) setelah video tamat
            val intent = Intent(this, AccountSelectActivity::class.java)
            startActivity(intent)
            
            // Menutup halaman Splash ini agar pengguna tidak bisa kembali ke video dengan tombol "Back" HP
            finish()
        }
    }
}
