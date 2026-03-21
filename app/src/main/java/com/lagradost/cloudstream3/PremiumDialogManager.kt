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

        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(android.graphics.Color.parseColor("#271D42"), android.graphics.Color.parseColor("#120E1E"))
        )
        gradient.cornerRadius = 24f.toPx

        val mainLayout = LinearLayout(activity).apply {
            orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(30, if(isTv) 30 else 60, 30, if(isTv) 30 else 60) 
            background = gradient
            gravity = Gravity.CENTER
            weightSum = if (isTv) 2f else 1f
        }

        val leftPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0,0,20,0) }
        }
        val rightPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (isTv) layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(20,0,0,0) }
        }

        val scroll = ScrollView(activity).apply { addView(mainLayout) }

        val icon = TextView(activity).apply {
            text = "👑"
            textSize = if (isTv) 40f else 50f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val title = TextView(activity).apply {
            text = "PREMIUM ACCESS"
            textSize = if (isTv) 18f else 21f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        
        val subTitle = TextView(activity).apply {
            text = "Fitur ini terkunci.\nSilakan hubungi admin untuk\nberlangganan."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#A0A0B5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, if(isTv) 15 else 30)
        }

        val priceBoxBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(2, android.graphics.Color.parseColor("#6A629B")) 
            cornerRadius = 24f.toPx
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
                    setTextColor(android.graphics.Color.parseColor("#4CAF50"))
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
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#A0A0B5"))
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
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#A0A0B5"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, if(isTv) 15 else 30)
        }

        val deviceIdVal = PremiumManager.getDeviceId(activity)
        val idNormalBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#221D36")) 
            setStroke(2, android.graphics.Color.parseColor("#443D61"))
            cornerRadius = 24f.toPx
        }
        val idFocusedBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#332D56")) 
            setStroke(4, android.graphics.Color.WHITE) 
            cornerRadius = 24f.toPx
        }
        val idStates = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), idFocusedBg)
            addState(intArrayOf(), idNormalBg)
        }
        
        val idContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = idStates 
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 0, 10, if(isTv) 15 else 30) }
            isFocusable = true 
            isClickable = true
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceIdVal)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "ID Disalin!", Toast.LENGTH_SHORT).show()
            }
        }
        val idLabel = TextView(activity).apply {
            text = "DEVICE ID ANDA (Tap to copy):"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#A0A0B5"))
            gravity = Gravity.CENTER
        }
        val idValueRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 5, 0, 0) }
        val idValue = TextView(activity).apply {
            text = deviceIdVal
            textSize = if(isTv) 20f else 24f
            setTextColor(android.graphics.Color.parseColor("#FFCA28")) 
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 15, 0)
        }
        val copyIcon = TextView(activity).apply { text = "⎘"; setTextColor(android.graphics.Color.parseColor("#FFCA28")); textSize = 20f }
        idValueRow.addView(idValue)
        idValueRow.addView(copyIcon)
        idContainer.addView(idLabel)
        idContainer.addView(idValueRow)

        val inputNormalBg = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) }
        val inputFocusedBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#33FFFFFF")) 
            cornerRadius = 16f.toPx
            setStroke(4, android.graphics.Color.parseColor("#FFCA28")) 
        }
        val inputStates = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), inputFocusedBg)
            addState(intArrayOf(), inputNormalBg)
        }

        val inputCode = EditText(activity).apply {
            hint = "Masukkan KODE di sini"
            setHintTextColor(android.graphics.Color.parseColor("#7A7A95"))
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 10)
            textSize = 16f
            setSingleLine()
            background = inputStates 
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(30, 0, 30, 0) }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val underline = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 2.toPx).apply { setMargins(40, 0, 40, if(isTv) 15 else 30) }
            setBackgroundColor(android.graphics.Color.parseColor("#6A629B"))
        }

        val btnNormalBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#FFCA28"))
            cornerRadius = 100f.toPx 
        }
        val btnFocusedBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#FFE066")) 
            setStroke(6, android.graphics.Color.WHITE) 
            cornerRadius = 100f.toPx 
        }
        val btnStates = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), btnFocusedBg)
            addState(intArrayOf(android.R.attr.state_pressed), btnFocusedBg)
            addState(intArrayOf(), btnNormalBg)
        }

        val btnUnlock = Button(activity).apply {
            text = "UNLOCK NOW"
            textSize = 15f
            background = btnStates 
            setTextColor(android.graphics.Color.parseColor("#120E1E"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, 50.toPx).apply { setMargins(10, 0, 10, 20) }
            isFocusable = true
        }
        
        val telNormalBg = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); cornerRadius = 16f.toPx }
        val telFocusedBg = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.parseColor("#33FFFFFF")); cornerRadius = 16f.toPx }
        val telStates = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), telFocusedBg)
            addState(intArrayOf(), telNormalBg)
        }

        val btnAdminRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
            background = telStates 
            isFocusable = true
            isClickable = true
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))
                    activity.startActivity(intent)
                } catch (e: Exception) { Toast.makeText(activity, "Telegram tidak ditemukan", Toast.LENGTH_SHORT).show() }
            }
        }
        val textAdmin = TextView(activity).apply { text = "TELEGRAM ADMIN "; setTextColor(android.graphics.Color.parseColor("#00E5FF")); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val iconAdmin = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(18.toPx, 18.toPx).apply { setMargins(10, 0, 0, 0) }; scaleType = ImageView.ScaleType.FIT_CENTER; loadImage("https://raw.githubusercontent.com/michat88/AdiXtream/master/asset/telegram.png") }
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
            rightPanel.addView(underline)
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
            mainLayout.addView(underline)
            mainLayout.addView(btnUnlock)
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
}
