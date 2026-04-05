package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.toPx

object PremiumDialogManager {

    fun showPremiumUnlockDialog(activity: Activity) {
        val isTv = isLayout(TV or EMULATOR)

        // Tema Gelap khas Netflix
        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(android.graphics.Color.parseColor("#141414"), android.graphics.Color.parseColor("#000000"))
        )
        gradient.cornerRadius = 16f.toPx

        // ==========================================
        // ADIXTREAM MOD: MANTRA PEMBEKU LAYAR TV
        // ==========================================
        val mainLayout = object : LinearLayout(activity) {
            // Memblokir paksa request scroll dari layout utama
            override fun requestRectangleOnScreen(rect: android.graphics.Rect?, immediate: Boolean): Boolean {
                return if (isTv) false else super.requestRectangleOnScreen(rect, immediate)
            }
            
            // PERBAIKAN ERROR: child menggunakan View, rectangle menggunakan Rect?
            override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect?, immediate: Boolean): Boolean {
                return if (isTv) false else super.requestChildRectangleOnScreen(child, rectangle, immediate)
            }
        }.apply {
            orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(30, if(isTv) 30 else 60, 30, if(isTv) 30 else 60) 
            background = gradient
            gravity = Gravity.CENTER
            weightSum = if (isTv) 2f else 1f
            clipChildren = false
            clipToPadding = false
        }

        val leftPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0,0,20,0) }
        }
        val rightPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(20,0,0,0) }
        }

        // Ikon dan Teks
        val icon = TextView(activity).apply {
            text = "🍿" // Popcorn
            textSize = if (isTv) 40f else 50f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val title = TextView(activity).apply {
            text = "PREMIUM ACCESS"
            textSize = if (isTv) 20f else 24f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        
        val subTitle = TextView(activity).apply {
            text = "Fitur ini terkunci.\nSilakan hubungi admin untuk\nberlangganan."
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#B3B3B3")) // Abu Netflix
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, if(isTv) 15 else 30)
        }

        // Kotak Harga Netflix
        val priceBoxBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#222222")) // Dark grey solid
            cornerRadius = 8f.toPx
        }
        val priceLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            background = priceBoxBg
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 0, 10, if(isTv) 15 else 30) }
            
            fun addPrice(dur: String, price: String) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                    weightSum = 2f
                }
                val t1 = TextView(activity).apply {
                    text = dur
                    textSize = 15f
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val t2 = TextView(activity).apply {
                    text = price
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#E50914")) // Merah Netflix
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    gravity = Gravity.END
                }
                row.addView(t1)
                row.addView(t2)
                addView(row)
            }
            
            addPrice("1 Bulan", "Rp 10.000")
            addPrice("6 Bulan", "Rp 30.000")
            addPrice("1 Tahun", "Rp 50.000")
        }

        // SCAN UNTUK BAYAR
        val qrisTitle = TextView(activity).apply {
            text = "SCAN UNTUK BAYAR"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#B3B3B3"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        val qrisImage = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, if(isTv) 180.toPx else -2).apply { setMargins(40, 0, 40, 0) }
            adjustViewBounds = true 
            scaleType = ImageView.ScaleType.FIT_CENTER
            loadImage("https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png") 
        }
        val qrisFooter = TextView(activity).apply {
            text = "OVO / DANA / GOPAY / SHOPEEPAY / BANK"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#B3B3B3"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, if(isTv) 15 else 30)
        }

        // Kotak Device ID Polos
        val deviceIdVal = PremiumManager.getDeviceId(activity)
        
        val idBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#222222")) 
            cornerRadius = 8f.toPx
        }
        
        val idContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = idBackground
            
            // Simetris 30px
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { 
                setMargins(30.toPx, 0, 30.toPx, if(isTv) 15 else 30) 
            }
            
            isFocusable = true 
            isClickable = true
            
            // Animasi untuk TV/HP
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
            
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceIdVal)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "ID Disalin!", Toast.LENGTH_SHORT).show()
            }
        }
        val idLabel = TextView(activity).apply {
            text = "DEVICE ID ANDA (Tap to copy):"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#B3B3B3"))
            gravity = Gravity.CENTER
        }
        val idValueRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 5, 0, 0) }
        val idValue = TextView(activity).apply {
            text = deviceIdVal
            textSize = if(isTv) 20f else 24f
            setTextColor(android.graphics.Color.WHITE) 
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 15, 0)
        }
        val copyIcon = TextView(activity).apply { text = "⎘"; setTextColor(android.graphics.Color.parseColor("#E50914")); textSize = 20f }
        idValueRow.addView(idValue)
        idValueRow.addView(copyIcon)
        idContainer.addView(idLabel)
        idContainer.addView(idValueRow)

        // Input Kode Ala Netflix
        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#333333")) 
            cornerRadius = 4f.toPx
        }
        
        val inputCode = EditText(activity).apply {
            hint = "Masukkan KODE di sini"
            setHintTextColor(android.graphics.Color.parseColor("#8C8C8C"))
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 30, 20, 30)
            textSize = 16f
            setSingleLine()
            
            background = inputBg
            
            // Simetris 30px
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { 
                setMargins(30.toPx, 0, 30.toPx, if(isTv) 15 else 30) 
            }
            
            isFocusable = true 
            isFocusableInTouchMode = true
            
            // Animasi untuk TV/HP
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.02f)
        }

        // Tombol Unlock Merah Netflix
        val btnBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#E50914"))
            cornerRadius = 4f.toPx 
        }

        val btnUnlock = Button(activity).apply {
            text = "UNLOCK NOW"
            textSize = 16f
            
            background = btnBackground 
            
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, 55.toPx).apply { 
                // Simetris 30px
                setMargins(30.toPx, 0, 30.toPx, 20) 
            }
            
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
        }
        
        // Tombol Telegram 
        val telBackground = android.graphics.drawable.GradientDrawable().apply { 
            setColor(android.graphics.Color.TRANSPARENT); cornerRadius = 16f.toPx 
        }

        val btnAdminRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
            
            background = telBackground 
            
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
            
            // PENANGANAN KHUSUS TV UNTUK TELEGRAM
            setOnClickListener {
                if (isTv) {
                    Toast.makeText(activity, "Gunakan HP Anda untuk menghubungi Admin via Telegram: @michat88", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))
                        activity.startActivity(intent)
                    } catch (e: Exception) { 
                        Toast.makeText(activity, "Telegram tidak ditemukan", Toast.LENGTH_SHORT).show() 
                    }
                }
            }
        }
        
        val textAdmin = TextView(activity).apply { text = "HUBUNGI ADMIN "; setTextColor(android.graphics.Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val iconAdmin = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(18.toPx, 18.toPx).apply { setMargins(10, 0, 0, 0) }; scaleType = ImageView.ScaleType.FIT_CENTER; loadImage("https://raw.githubusercontent.com/michat88/AdiXtream/master/asset/telegram.png") }
        btnAdminRow.addView(textAdmin)
        btnAdminRow.addView(iconAdmin)

        // Logika Klik Unlock (ONLINE SYSTEM)
        btnUnlock.setOnClickListener {
            val code = inputCode.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(activity, "Masukkan kode terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Ubah teks tombol jadi proses loading
            btnUnlock.text = "MEMVERIFIKASI..."
            btnUnlock.isEnabled = false

            PremiumManager.activatePremiumWithCode(activity, code, deviceIdVal) { isSuccess, message ->
                // Kembalikan tombol seperti semula setelah server menjawab
                btnUnlock.isEnabled = true
                btnUnlock.text = "UNLOCK NOW"
                
                if (isSuccess) {
                    (btnUnlock.tag as? Dialog)?.dismiss()
                    val expiryDate = PremiumManager.getExpiryDateString(activity)
                    
                    AlertDialog.Builder(activity)
                        .setTitle("✅ PREMIUM DIAKTIFKAN")
                        .setMessage("Terima kasih telah berlangganan!\n\n📅 Masa Aktif: $expiryDate\n\nAplikasi akan direstart...")
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ ->
                            val packageManager = activity.packageManager
                            val intent = packageManager.getLaunchIntentForPackage(activity.packageName)
                            val componentName = intent?.component
                            val mainIntent = Intent.makeRestartActivityTask(componentName)
                            activity.startActivity(mainIntent)
                            Runtime.getRuntime().exit(0)
                        }
                        .show()
                } else {
                    Toast.makeText(activity, "⛔ $message", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Menyusun View ke Panels
        if (isTv) {
            leftPanel.addView(icon)
            leftPanel.addView(title)
            leftPanel.addView(subTitle)
            leftPanel.addView(priceLayout)
            leftPanel.addView(btnAdminRow)

            rightPanel.addView(qrisTitle)
            rightPanel.addView(qrisImage)
            rightPanel.addView(qrisFooter)
            rightPanel.addView(idContainer)
            rightPanel.addView(inputCode)
            rightPanel.addView(btnUnlock)

            mainLayout.addView(leftPanel)
            mainLayout.addView(rightPanel)
        } else {
            mainLayout.addView(icon)
            mainLayout.addView(title)
            mainLayout.addView(subTitle)
            mainLayout.addView(priceLayout)
            mainLayout.addView(qrisTitle)
            mainLayout.addView(qrisImage)
            mainLayout.addView(qrisFooter)
            mainLayout.addView(idContainer)
            mainLayout.addView(inputCode)
            mainLayout.addView(btnUnlock)
            mainLayout.addView(btnAdminRow)
        }

        // Membuat Dialog murni (tanpa embel-embel ScrollView bawaan AlertDialog)
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        if (isTv) {
            dialog.setContentView(mainLayout)
        } else {
            val scroll = ScrollView(activity).apply { 
                clipChildren = false
                clipToPadding = false
                addView(mainLayout) 
            }
            dialog.setContentView(scroll)
        }

        dialog.setOnShowListener {
            val displayMetrics = activity.resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt() 
            // Untuk TV, paksa juga height-nya agar tidak melebihi layar yang memicu scroll OS
            val height = if (isTv) (displayMetrics.heightPixels * 0.90).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            dialog.window?.setLayout(width, height)
        }

        btnUnlock.tag = dialog
        dialog.show()
    }

    /**
     * ADIXTREAM MOD: Fungsi Pembantu untuk Animasi Tombol Modern
     */
    private fun applyModernButtonEffects(button: View, isTv: Boolean, scaleOnTv: Float = 1.05f) {
        button.isFocusable = true
        button.isClickable = true

        if (isTv) {
            button.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(scaleOnTv)
                        .scaleY(scaleOnTv)
                        .translationZ(10f)
                        .setDuration(150)
                        .start()
                } else {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(150)
                        .start()
                }
            }
        } else {
            button.translationZ = 4f
        }

        button.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val targetScale = if (isTv && view.hasFocus()) scaleOnTv else 1f
                    view.animate().scaleX(targetScale).scaleY(targetScale).setDuration(100).start()
                }
            }
            false 
        }
    }
}
