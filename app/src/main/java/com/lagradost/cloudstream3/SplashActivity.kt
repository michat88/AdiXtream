package com.lagradost.cloudstream3

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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

        val splashRoot = findViewById<View>(R.id.splashRoot)
        val videoView = findViewById<VideoView>(R.id.videoView)

        // --- EFEK FADE-IN UNTUK TRANSISI YANG HALUS ---
        splashRoot.alpha = 0f
        splashRoot.animate()
            .alpha(1f)
            .setDuration(1000)
            .start()

        // --- LOGIKA MENDETEKSI TV ATAU HP ---
        // 1. Memanggil layanan sistem Android untuk mengecek mode perangkat
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        
        // 2. Memilih file video berdasarkan tipe perangkat
        val videoResource = if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            // Jika perangkat terdeteksi sebagai Android TV
            // WAJIB ADA: File bernama intro_video_tv.mp4 di dalam folder res/raw/
            R.raw.intro_video_tv
        } else {
            // Jika perangkat terdeteksi sebagai HP
            // Menggunakan file intro_video.mp4 yang lama agar tidak error
            R.raw.intro_video
        }

        // 3. Mengatur jalur file video yang sudah dipilih di atas
        val videoPath = "android.resource://" + packageName + "/" + videoResource
        val uri = Uri.parse(videoPath)
        videoView.setVideoURI(uri)

        // Memutar videonya!
        videoView.start()

        // Mendeteksi kapan video selesai diputar
        videoView.setOnCompletionListener {
            // Berpindah ke halaman utama tanpa ada tombol skip
            val intent = Intent(this, AccountSelectActivity::class.java)
            startActivity(intent)
            
            // Menutup halaman Splash ini agar pengguna tidak bisa kembali dengan tombol "Back" HP
            finish()
        }
    }
}
