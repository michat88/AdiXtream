package com.lagradost.cloudstream3

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/** Presentation only: all identity, validation, expiry and storage stay in PremiumManager. */
class ActivationSubscriptionActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var unlock: Button
    private lateinit var promo: Button
    private var chosenPlan = 0
    private var requestGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private val red = Color.rgb(229, 9, 20)
    private val muted = Color.rgb(180, 180, 185)
    private val tvMode get() = isLayout(TV or EMULATOR)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        // Same locale preference and locale application as the main application.
        val tag = PreferenceManager.getDefaultSharedPreferences(newBase)
            .getString(newBase.getString(R.string.locale_key), null)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        if (!tag.isNullOrBlank()) config.setLocale(java.util.Locale.forLanguageTag(tag))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        chosenPlan = savedInstanceState?.getInt("selected_plan") ?: 0
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(10, 10, 12))
            clipToPadding = false
        }
        val root = column().apply { setPadding(dp(20), dp(16), dp(20), dp(24)) }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, maxOf(system.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        val back = button(R.string.adi_back) { finish() }
        root.addView(back, LinearLayout.LayoutParams(dp(140), dp(48)))
        root.addView(label(getString(R.string.adi_activation_subscription), 26f, true))
        root.addView(label(getString(R.string.adi_subscription_intro), 14f, color = muted))
        val wide = tvMode || resources.configuration.screenWidthDp >= 720
        val cards = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
            isBaselineAligned = false
        }
        root.addView(cards)
        val plans = card()
        val activation = card()
        for ((index, panel) in listOf(plans, activation).withIndex()) {
            cards.addView(panel, LinearLayout.LayoutParams(
                if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (wide) 1f else 0f
            ).apply {
                topMargin = dp(16)
                if (wide && index == 0) rightMargin = dp(16)
            })
        }
        activation.addView(label(getString(R.string.adi_account_status), 18f, true))
        status = label("", 16f)
        activation.addView(status)
        val deviceId = PremiumManager.getDeviceId(this)
        activation.addView(label(getString(R.string.adi_device_id), 13f, color = muted))
        val copy = buttonText(deviceId + "  ⎘") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
            Toast.makeText(this, R.string.adi_id_copied, Toast.LENGTH_SHORT).show()
        }
        activation.addView(copy)
        activation.addView(label(getString(R.string.adi_activation_code), 14f, true))
        code = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.adi_code_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            background = focusBackground(Color.rgb(42, 42, 46))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(54)
            // Avoid using the activation code as a keyboard learning/autofill value.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        code.setText(savedInstanceState?.getString("activation_input") ?: "")
        activation.addView(code, LinearLayout.LayoutParams(-1, -2))
        unlock = button(R.string.adi_activate, true) { activate(false) }
        activation.addView(unlock)
        promo = button(R.string.adi_claim_promo) { showPromo() }
        activation.addView(promo)
        val admin = button(R.string.adi_contact_admin) {
            if (tvMode) {
                AlertDialog.Builder(this).setTitle(R.string.adi_contact_admin)
                    .setMessage(getString(R.string.adi_admin_tv, "@michat88"))
                    .setPositiveButton(R.string.ok, null).show()
            } else {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))) }
                catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, getString(R.string.adi_admin_tv, "@michat88"), Toast.LENGTH_LONG).show()
                }
            }
        }
        activation.addView(admin)
        plans.addView(label(getString(R.string.adi_choose_plan), 18f, true))
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val durations = listOf(R.string.adi_one_month, R.string.adi_six_months, R.string.adi_one_year)
        val prices = listOf("Rp 10.000", "Rp 30.000", "Rp 50.000")
        val planButtons = durations.mapIndexed { index, duration ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(duration) + "  ·  " + prices[index]
                setTextColor(Color.WHITE)
                textSize = 16f
                minHeight = dp(52)
                background = focusBackground(Color.rgb(32, 32, 36))
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { chosenPlan = index }
                choices.addView(this, RadioGroup.LayoutParams(-1, -2))
            }
        }
        choices.check(planButtons[chosenPlan.coerceIn(0, 2)].id)
        plans.addView(choices)
        plans.addView(label(getString(R.string.adi_payment_help), 13f, color = muted))
        plans.addView(label(getString(R.string.adi_scan_payment), 15f, true))
        val qr = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.adi_scan_payment)
            loadImage("https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png")
        }
        plans.addView(qr, LinearLayout.LayoutParams(-1, dp(if (tvMode) 240 else 400)))
        plans.addView(label("OVO / DANA / GOPAY / SHOPEEPAY / BANK", 12f, color = muted))
        val expand = button(R.string.adi_enlarge_qr) { showPaymentQr() }
        plans.addView(expand)
        // Every actionable element participates in vertical D-pad traversal; payment can be reached left/right.
        val actions = listOf<View>(back) + planButtons + expand + listOf(copy, code, unlock, promo, admin)
        actions.zipWithNext().forEach { (before, after) ->
            before.nextFocusDownId = after.id
            after.nextFocusUpId = before.id
            before.nextFocusForwardId = after.id
        }
        if (wide) {
            (planButtons + listOf(expand)).forEach { it.nextFocusRightId = copy.id }
            listOf(copy, code, unlock, promo, admin).forEach { it.nextFocusLeftId = planButtons[chosenPlan.coerceIn(0, 2)].id }
        }
        if (tvMode) copy.requestFocus()
        code.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { unlock.performClick(); true } else false
        }
        refreshStatus()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_plan", chosenPlan)
        outState.putString("activation_input", code.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        requestGeneration++
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private fun refreshStatus() {
        val active = PremiumManager.isPremium(this)
        status.text = if (active) getString(R.string.adi_premium_active, PremiumManager.getExpiryDateString(this))
            else getString(R.string.adi_premium_inactive)
    }
    private fun activate(isPromo: Boolean, promoCode: String? = null) {
        val input = (promoCode ?: code.text.toString()).trim().uppercase()
        if (input.isEmpty()) { code.error = getString(R.string.adi_code_required); code.requestFocus(); return }
        unlock.isEnabled = false
        promo.isEnabled = false
        unlock.setText(R.string.adi_verifying)
        val token = ++requestGeneration
        // Existing backend can return no callback for non-200 responses. Recover UI without changing validation.
        handler.postDelayed({
            if (token == requestGeneration && !isFinishing && !isDestroyed) {
                requestGeneration++
                resetButtons()
                Toast.makeText(this, R.string.adi_request_timeout, Toast.LENGTH_LONG).show()
            }
        }, 30_000L)
        val result: (Boolean, String) -> Unit = callback@{ success, message ->
            if (token != requestGeneration || isFinishing || isDestroyed) return@callback
            requestGeneration++
            resetButtons()
            if (success) {
                refreshStatus()
                code.text?.clear()
                AlertDialog.Builder(this).setTitle(R.string.adi_activated)
                    .setMessage(getString(R.string.adi_restart_info, PremiumManager.getExpiryDateString(this)))
                    .setCancelable(false).setPositiveButton(R.string.ok) { _, _ ->
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        val component = launch?.component
                        if (component != null) {
                            startActivity(Intent.makeRestartActivityTask(component))
                            finishAffinity()
                        } else finish()
                    }.show()
            } else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        val deviceId = PremiumManager.getDeviceId(this)
        if (isPromo) PremiumManager.activatePromoWithCode(applicationContext, input, deviceId, result)
        else PremiumManager.activatePremiumWithCode(applicationContext, input, deviceId, result)
    }
    private fun resetButtons() {
        unlock.isEnabled = true
        promo.isEnabled = true
        unlock.setText(R.string.adi_activate)
    }
    private fun showPromo() {
        val input = EditText(this).apply {
            hint = getString(R.string.adi_code_hint)
            setSingleLine(true)
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(10))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.adi_claim_promo).setView(input)
            .setPositiveButton(R.string.adi_claim, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isNullOrBlank()) input.error = getString(R.string.adi_code_required)
                else { val value = input.text.toString(); dialog.dismiss(); activate(true, value) }
            }
        }
        dialog.show()
    }
    private fun showPaymentQr() {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = getString(R.string.adi_scan_payment)
            loadImage("https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png")
        }
        val scroll = ScrollView(this).apply { addView(image, ViewGroup.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle(R.string.adi_scan_payment).setView(scroll)
            .setPositiveButton(R.string.adi_back, null).show()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        setPadding(dp(20), dp(16), dp(20), dp(20))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 24, 28)); cornerRadius = dp(16).toFloat() }
    }
    private fun label(value: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(10))
    }
    private fun button(text: Int, primary: Boolean = false, click: () -> Unit) = buttonText(getString(text), primary, click)
    private fun buttonText(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        id = View.generateViewId(); text = value; isAllCaps = false
        setTextColor(Color.WHITE); textSize = 15f
        background = focusBackground(if (primary) red else Color.rgb(42, 42, 46))
        setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(52)
        isFocusable = true; isFocusableInTouchMode = tvMode
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { click() }
    }
    private fun focusBackground(fill: Int) = StateListDrawable().apply {
        fun shape(focused: Boolean) = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(10).toFloat()
            if (focused) setStroke(dp(3), Color.WHITE)
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(true))
        addState(intArrayOf(), shape(false))
    }
}
