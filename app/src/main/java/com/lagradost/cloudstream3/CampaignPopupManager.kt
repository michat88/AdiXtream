package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CampaignPopupManager {
    private const val TAG = "CampaignPopup"

    fun checkAndShowCampaignPopup(activity: Activity) {
        val repoUrl = "https://raw.githubusercontent.com/michat88/Zaneta/main/popups.json" 
        
        ioSafe {
            try {
                // app.get sudah tersedia dari com.lagradost.cloudstream3.app
                val response = app.get(repoUrl).text
                val jsonArray = JSONArray(response)
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val now = Date()
                val isDeviceTv = isLayout(TV or EMULATOR) 
                
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val isActive = item.optBoolean("isActive", false)
                    if (!isActive) continue

                    val target = item.optString("targetPlatform", "all")
                    if (isDeviceTv && target == "hp") continue 
                    if (!isDeviceTv && target == "tv") continue 
                    
                    val start = format.parse(item.optString("startDate", "2000-01-01 00:00"))
                    val end = format.parse(item.optString("endDate", "2099-01-01 00:00"))
                    
                    if (start != null && end != null && now.after(start) && now.before(end)) {
                        val id = item.optString("id")
                        val freq = item.optString("frequency", "always")
                        val lastShown = prefs.getLong("campaign_popup_$id", 0L)
                        val nowMs = System.currentTimeMillis()
                        
                        val shouldShow = when (freq) {
                            "always" -> true
                            "once_per_day" -> (nowMs - lastShown) > 86400000L 
                            "once_ever" -> lastShown == 0L
                            else -> true
                        }
                        
                        if (shouldShow) {
                            main {
                                showBeautifulCampaignPopup(activity, item)
                                prefs.edit().putLong("campaign_popup_$id", nowMs).apply()
                            }
                            break 
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memuat campaign popup", e)
            }
        }
    }

    private fun showBeautifulCampaignPopup(activity: Activity, item: JSONObject) {
        val titleStr = item.optString("title")
        val messageStr = item.optString("message")
        val actionText = item.optString("actionText", "Tutup")
        val actionLink = item.optString("actionLink")
        val imageUrl = item.optString("imageUrl")
        val isDeviceTv = isLayout(TV or EMULATOR)

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#D905080F"))) 
        
        val rootLayout = RelativeLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            gravity = Gravity.CENTER
        }
        
        val tvWidthPercent = 0.38
        val fullScreenWidth = activity.resources.displayMetrics.widthPixels
        
        val popupWidth = if (isDeviceTv) {
            (fullScreenWidth * tvWidthPercent).toInt() 
        } else {
            (fullScreenWidth * 0.85).toInt() 
        }

        val dynamicImageHeight = (popupWidth * (3.0 / 4.0)).toInt() 

        val card = CardView(activity).apply {
            radius = 24f.toPx.toFloat()
            setCardBackgroundColor(Color.parseColor("#1E293B"))
            cardElevation = 20f
            layoutParams = RelativeLayout.LayoutParams(popupWidth, -2).apply { 
                addRule(RelativeLayout.CENTER_IN_PARENT) 
            }
        }
        
        val cardContent = RelativeLayout(activity)
        
        val imageView = ImageView(activity).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(-1, dynamicImageHeight)
            scaleType = ImageView.ScaleType.CENTER_CROP 
        }

        if (imageUrl.startsWith("data:image")) {
            try {
                val base64Raw = imageUrl.substringAfter(",")
                val decodedBytes = Base64.decode(base64Raw, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                imageView.setImageBitmap(bitmap)
            } catch (e: Exception) { imageView.setBackgroundColor(Color.parseColor("#334155")) }
        } else { imageView.setBackgroundColor(Color.parseColor("#334155")) }
        
        val gradientView = View(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(-1, 80.toPx).apply { 
                addRule(RelativeLayout.ALIGN_BOTTOM, imageView.id) 
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#1E293B"))
            )
        }
        
        val closeBtn = ImageView(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(36.toPx, 36.toPx).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                setMargins(0, 16.toPx, 16.toPx, 0)
            }
            setImageResource(R.drawable.ic_baseline_close_24) 
            setColorFilter(Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66000000")) 
            }
            setPadding(8.toPx, 8.toPx, 8.toPx, 8.toPx)
            setOnClickListener { dialog.dismiss() }
            visibility = if (isDeviceTv) View.GONE else View.VISIBLE
        }
        
        val textContainer = LinearLayout(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(-1, -2).apply { 
                addRule(RelativeLayout.BELOW, imageView.id) 
            }
            orientation = LinearLayout.VERTICAL
            setPadding(24.toPx, 10.toPx, 24.toPx, 16.toPx) 
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val titleText = TextView(activity).apply {
            text = titleStr
            textSize = 22f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12.toPx)
            maxLines = 2 
            ellipsize = TextUtils.TruncateAt.END
        }
        textContainer.addView(titleText) 
        
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 15.toPx) 
                height = ViewGroup.LayoutParams.WRAP_CONTENT 
            }
        }

        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val messageText = TextView(activity).apply {
            text = messageStr
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0.toPx) 
            setLineSpacing(0f, 1.2f)
            val maxTextHeight = if (isDeviceTv) 120.toPx else 180.toPx
            maxHeight = maxTextHeight
        }
        
        scrollContent.addView(messageText)
        scrollView.addView(scrollContent)
        textContainer.addView(scrollView)
        
        val btnContainer = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 0.toPx 
            }
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f 
        }

        val actionBtn = Button(activity).apply {
            text = actionText
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = 16f.toPx.toFloat()
                setColor(Color.parseColor("#E50914"))
            }
            
            setOnClickListener {
                dialog.dismiss()
                if (actionLink.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(actionLink))
                        activity.startActivity(intent)
                    } catch (e: Exception) { Log.e(TAG, "Gagal membuka link", e) }
                }
            }
        }

        if (isDeviceTv) {
            actionBtn.layoutParams = LinearLayout.LayoutParams(0, 48.toPx, 1f).apply { rightMargin = 6.toPx }
            val closeTvBtn = Button(activity).apply {
                text = "Tutup"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                background = GradientDrawable().apply {
                    cornerRadius = 16f.toPx.toFloat()
                    setColor(Color.parseColor("#475569")) 
                }
                layoutParams = LinearLayout.LayoutParams(0, 48.toPx, 1f).apply { leftMargin = 6.toPx }
                setOnClickListener { dialog.dismiss() }
            }
            
            btnContainer.addView(actionBtn)
            btnContainer.addView(closeTvBtn)
            textContainer.addView(btnContainer) 
        } else {
            actionBtn.layoutParams = LinearLayout.LayoutParams(-1, 52.toPx)
            textContainer.addView(actionBtn) 
        }
        
        cardContent.addView(imageView)
        cardContent.addView(gradientView)
        cardContent.addView(closeBtn)
        cardContent.addView(textContainer)
        
        card.addView(cardContent)
        rootLayout.addView(card)
        dialog.setContentView(rootLayout)
        
        dialog.show()
    }
}
