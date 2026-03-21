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
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        
        // Memilih file video berdasarkan tipe perangkat
        val videoResource = if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            // Memanggil intro_video_tv.mp4
            R.raw.intro_video_tv
        } else {
            // Memanggil intro_video_mobile.mp4
            R.raw.intro_video_mobile
        }

        // Mengatur jalur file video
        val videoPath = "android.resource://" + packageName + "/" + videoResource
        val uri = Uri.parse(videoPath)
        videoView.setVideoURI(uri)

        // Memutar videonya!
        videoView.start()

        // Pindah halaman saat video tamat
        videoView.setOnCompletionListener {
            // SUNTIKAN PERBAIKAN UNTUK TV: Hentikan dan bersihkan memori layar video
            videoView.stopPlayback()
            videoView.suspend()
            videoView.visibility = View.GONE
            
            // LANGSUNG tembak ke MainActivity, lewati AccountSelectActivity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            
            // Memberikan transisi memudar agar perpindahan layar lebih halus
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            
            // Tutup Splash secara tuntas
            finish()
        }
    }
}
