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

        // STYLE NETFLIX: Background Gelap Pekat (#141414)
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#141414"))
            cornerRadius = 16f.toPx // Sudut melengkung yang lebih modern dan tidak berlebihan
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(40, if(isTv) 40 else 60, 40, if(isTv) 40 else 60) 
            background = bgDrawable
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
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0,0,30,0) }
        }
        val rightPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(30,0,0,0) }
        }

        val scroll = ScrollView(activity).apply { 
            clipChildren = false
            clipToPadding = false
            addView(mainLayout) 
        }

        val icon = TextView(activity).apply {
            text = "👑"
            textSize = if (isTv) 35f else 45f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val title = TextView(activity).apply {
            text = "PREMIUM ACCESS"
            textSize = if (isTv) 20f else 24f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 5)
        }
        
        val subTitle = TextView(activity).apply {
            text = "Tonton tanpa batas. Buka semua fitur eksklusif.\nHubungi admin untuk berlangganan."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#B3B3B3")) // Abu-abu khas Netflix
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, if(isTv) 20 else 40)
        }

        // Kotak Harga ala Netflix: Dark grey dengan border tipis
        val priceBoxBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#222222"))
            setStroke(2, android.graphics.Color.parseColor("#333333")) 
            cornerRadius = 12f.toPx
        }
        val priceLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            background = priceBoxBg
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 0, 10, if(isTv) 20 else 30) }
             
            fun addPrice(dur: String, price: String) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 10)
                    weightSum = 2f
                }
                val t1 = TextView(activity).apply {
                    text = dur
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#E5E5E5"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val t2 = TextView(activity).apply {
                    text = price
                    textSize = 15f
                    setTextColor(android.graphics.Color.WHITE)
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

        val qrisTitle = TextView(activity).apply {
            text = "SCAN UNTUK BAYAR"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#8C8C8C"))
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
            setTextColor(android.graphics.Color.parseColor("#8C8C8C"))
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, if(isTv) 20 else 30)
        }

        // ==========================================
        // DEVICE ID KOTAK MINIMALIS
        // ==========================================
        val deviceIdVal = PremiumManager.getDeviceId(activity)
        
        val idBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#222222")) 
            setStroke(2, android.graphics.Color.parseColor("#333333"))
            cornerRadius = 12f.toPx
        }
        
        val idContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 25, 20, 25)
            background = idBackground
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 0, 10, if(isTv) 20 else 30) }
            isFocusable = true 
            isClickable = true
            
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
            
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceIdVal)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "ID Disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
        val idLabel = TextView(activity).apply {
            text = "DEVICE ID ANDA (Tap to copy)"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#8C8C8C"))
            gravity = Gravity.CENTER
        }
        val idValueRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 8, 0, 0) }
        val idValue = TextView(activity).apply {
            text = deviceIdVal
            textSize = if(isTv) 20f else 24f
            setTextColor(android.graphics.Color.WHITE) 
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 15, 0)
        }
        val copyIcon = TextView(activity).apply { text = "⎘"; setTextColor(android.graphics.Color.parseColor("#B3B3B3")); textSize = 18f }
        idValueRow.addView(idValue)
        idValueRow.addView(copyIcon)
        idContainer.addView(idLabel)
        idContainer.addView(idValueRow)

        // ==========================================
        // INPUT KODE: Box Solid Gelap ala Profil Netflix
        // ==========================================
        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#333333")) // Background input solid
            cornerRadius = 8f.toPx
            setStroke(2, android.graphics.Color.parseColor("#444444"))
        }

        val inputCode = EditText(activity).apply {
            hint = "Masukkan Kode Aktivasi"
            setHintTextColor(android.graphics.Color.parseColor("#8C8C8C"))
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 30, 20, 30)
            textSize = 15f
            setSingleLine()
            background = inputBg
            
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(20, 0, 20, if(isTv) 20 else 30) }
            isFocusable = true 
            isFocusableInTouchMode = true
        }

        // ==========================================
        // TOMBOL UNLOCK: Warna Merah Khas Netflix (#E50914)
        // ==========================================
        val btnBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#E50914"))
            cornerRadius = 8f.toPx 
        }

        val btnUnlock = Button(activity).apply {
            text = "AKTIFKAN SEKARANG"
            textSize = 15f
            background = btnBackground 
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, 55.toPx).apply { 
                setMargins(20, 0, 20, 20) 
            }
            
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
        }
        
        // ==========================================
        // TOMBOL TELEGRAM: Clean & Subtle
        // ==========================================
        val telBackground = android.graphics.drawable.GradientDrawable().apply { 
            setColor(android.graphics.Color.TRANSPARENT); 
            cornerRadius = 8f.toPx 
        }

        val btnAdminRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 15, 20, 15)
            background = telBackground 
            
            applyModernButtonEffects(this, isTv, scaleOnTv = 1.05f)
            
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))
                    activity.startActivity(intent)
                } catch (e: Exception) { Toast.makeText(activity, "Telegram tidak ditemukan", Toast.LENGTH_SHORT).show() }
            }
        }
        
        val textAdmin = TextView(activity).apply { text = "Hubungi Admin via Telegram"; setTextColor(android.graphics.Color.parseColor("#B3B3B3")); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val iconAdmin = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(18.toPx, 18.toPx).apply { setMargins(15, 0, 0, 0) }; scaleType = ImageView.ScaleType.FIT_CENTER; loadImage("https://raw.githubusercontent.com/michat88/AdiXtream/master/asset/telegram.png") }
        btnAdminRow.addView(textAdmin)
        btnAdminRow.addView(iconAdmin)

        btnUnlock.setOnClickListener {
            val code = inputCode.text.toString().trim().uppercase()
            val isSuccess = PremiumManager.activatePremiumWithCode(activity, code, deviceIdVal)
            
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
                Toast.makeText(activity, "⛔ Kode Salah / Sudah Expired!", Toast.LENGTH_SHORT).show()
            }
        }

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
            rightPanel.addView(btnUnlock) // Underline dihapus karena pakai box input

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
            mainLayout.addView(btnUnlock) // Underline dihapus karena pakai box input
            mainLayout.addView(btnAdminRow)
        }

        val alert = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(scroll)
            .setCancelable(true)
            .create()
        
        alert.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        alert.setOnShowListener {
            val displayMetrics = activity.resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt() 
            alert.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnUnlock.tag = alert
        alert.show()
    }

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

