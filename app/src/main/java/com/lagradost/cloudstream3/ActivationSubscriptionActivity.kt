package com.lagradost.cloudstream3

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Presentation only. Plans never participate in activation or expiry validation. */
class ActivationSubscriptionActivity : AppCompatActivity() {
    private data class Plan(val name: Int, val price: String)
    private enum class AccountState { FREE, PREMIUM, EXPIRED }
    private val plans = listOf(
        Plan(R.string.adi_one_month, "Rp10.000"),
        Plan(R.string.adi_six_months, "Rp30.000"),
        Plan(R.string.adi_one_year, "Rp50.000")
    )
    private lateinit var request: ActivationRequest
    private lateinit var scroll: ScrollView
    private lateinit var back: Button
    private lateinit var status: TextView
    private lateinit var statusHelp: TextView
    private lateinit var expiry: TextView
    private lateinit var remaining: TextView
    private lateinit var copy: Button
    private lateinit var renew: Button
    private lateinit var planTitle: TextView
    private lateinit var summary: TextView
    private lateinit var paymentSummary: TextView
    private lateinit var admin: Button
    private lateinit var activationTitle: TextView
    private lateinit var code: EditText
    private lateinit var unlock: Button
    private lateinit var promo: Button
    private lateinit var qrPanel: PaymentQrPanel
    private lateinit var expand: Button
    private var planButtons = emptyList<RadioButton>()
    private var chosenPlan = 0
    private var accountState = AccountState.FREE
    private var displayInfo: PremiumManager.SubscriptionDisplayInfo? = null
    private var infoReceivedAt = 0L
    private var infoGeneration = 0
    private var paymentReturnTarget: View? = null
    private var resultDialog: AlertDialog? = null
    private val red = Color.rgb(229, 9, 20)
    private val muted = Color.rgb(180, 180, 185)
    private val tvMode get() = isLayout(TV or EMULATOR)
    private val deviceId by lazy { PremiumManager.getDeviceId(this) }
    private val locale get() = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
    private val renewing get() = accountState != AccountState.FREE || (displayInfo?.expiresAt ?: 0L) > 0L
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        val tag = PreferenceManager.getDefaultSharedPreferences(newBase)
            .getString(newBase.getString(R.string.locale_key), null)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        if (!tag.isNullOrBlank()) config.setLocale(Locale.forLanguageTag(tag))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = ViewModelProvider(this)[ActivationRequest::class.java]
        chosenPlan = (savedInstanceState?.getInt("selected_plan") ?: 0).coerceIn(plans.indices)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.rgb(10, 10, 12))
        }
        val root = column().apply { setPadding(dp(if (tvMode) 28 else 20), dp(12), dp(if (tvMode) 28 else 20), dp(24)) }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, maxOf(system.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        back = buttonText("← " + getString(R.string.adi_back)) { finish() }.apply {
            background = focusBackground(Color.TRANSPARENT)
        }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(label(getString(R.string.adi_activation_subscription), if (tvMode) 26f else 24f, true))
        root.addView(label(getString(R.string.adi_subscription_steps), 13f, color = muted))

        val paymentColumn = column()
        val statusColumn = column()
        val statusCard = card()
        status = label("", 22f, true)
        statusHelp = label("", 14f, color = muted)
        expiry = label("", 16f)
        remaining = label("", 14f, color = muted)
        statusCard.addView(status)
        statusCard.addView(statusHelp)
        statusCard.addView(expiry)
        statusCard.addView(remaining)
        statusCard.addView(label(getString(R.string.adi_device_id), 13f, color = muted))
        statusCard.addView(label(deviceId, 22f, true))
        copy = button(R.string.adi_copy_device) {
            copyText(getString(R.string.adi_device_id), deviceId)
            Toast.makeText(this, R.string.adi_id_copied, Toast.LENGTH_SHORT).show()
        }
        statusCard.addView(copy)
        renew = button(R.string.adi_renew_now, true) {
            scrollTo(planTitle)
            planButtons[chosenPlan].requestFocus()
        }
        statusCard.addView(renew)
        if (tvMode) addCard(statusColumn, statusCard) else addCard(root, statusCard)

        val planCard = card()
        planTitle = label("", 20f, true)
        planCard.addView(planTitle)
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        planButtons = plans.map { plan ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(plan.name) + "  ·  " + plan.price
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(red)
                textSize = 16f
                minHeight = dp(52)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = focusBackground(Color.rgb(32, 32, 36))
                isFocusable = true
                choices.addView(this, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        choices.check(planButtons[chosenPlan].id)
        planCard.addView(choices)
        summary = label("", 16f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        planCard.addView(summary)
        planCard.addView(label(getString(R.string.adi_plan_server_note), 12f, color = muted))
        choices.setOnCheckedChangeListener { _, checkedId ->
            val index = planButtons.indexOfFirst { it.id == checkedId }
            if (index >= 0) { chosenPlan = index; updatePlanSummary(); configureFocus() }
        }
        addCard(paymentColumn, planCard)

        val paymentCard = card()
        paymentCard.addView(label(getString(R.string.adi_pay_qris), 20f, true))
        paymentCard.addView(label(getString(R.string.adi_payment_help), 13f, color = muted))
        qrPanel = PaymentQrPanel(if (tvMode) 240 else 220)
        qrPanel.onChanged = { configureFocus() }
        paymentCard.addView(qrPanel.root)
        expand = button(R.string.adi_enlarge_qr) { showPaymentQr() }
        paymentCard.addView(expand)
        paymentSummary = label("", 15f, true)
        paymentCard.addView(paymentSummary)
        paymentCard.addView(label("OVO / DANA / GOPAY / SHOPEEPAY / BANK", 12f, color = muted))
        admin = buttonText("", true) { contactAdmin() }
        paymentCard.addView(admin)
        addCard(paymentColumn, paymentCard)

        val activationCard = card()
        activationTitle = label("", 20f, true)
        activationCard.addView(activationTitle)
        activationCard.addView(label(getString(R.string.adi_activation_help), 13f, color = muted))
        code = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.adi_code_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = focusBackground(Color.rgb(42, 42, 46))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(54)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        code.setText(savedInstanceState?.getString("activation_input") ?: "")
        activationCard.addView(code, LinearLayout.LayoutParams(-1, -2))
        unlock = button(R.string.adi_activate, true) { activate(false, code.text.toString()) }
        activationCard.addView(unlock)
        activationCard.addView(label(getString(R.string.adi_have_promo), 13f, color = muted))
        promo = button(R.string.adi_claim_promo) { showPromo() }
        activationCard.addView(promo)
        addCard(statusColumn, activationCard)

        if (tvMode) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                isBaselineAligned = false
            }
            columns.addView(paymentColumn, LinearLayout.LayoutParams(0, -2, 1.1f).apply { rightMargin = dp(16) })
            columns.addView(statusColumn, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(columns)
        } else {
            root.addView(paymentColumn)
            root.addView(statusColumn)
        }
        code.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { activate(false, code.text.toString()); true } else false
        }
        request.busy.observe(this) { busy ->
            code.isEnabled = !busy
            unlock.isEnabled = !busy
            promo.isEnabled = !busy
            unlock.setText(if (busy) R.string.adi_verifying else R.string.adi_activate)
            configureFocus()
        }
        request.outcome.observe(this) { outcome -> if (outcome != null) showOutcome(outcome) }
        updatePlanSummary()
        refreshStatus(false)
        configureFocus()
        if (tvMode) back.requestFocus()
        qrPanel.load()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus(true) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_plan", chosenPlan)
        outState.putString("activation_input", code.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        infoGeneration++
        resultDialog?.dismiss()
        super.onDestroy()
    }

    private fun updatePlanSummary() {
        val plan = plans[chosenPlan]
        summary.text = getString(R.string.adi_order_summary, getString(plan.name), plan.price)
        paymentSummary.text = getString(R.string.adi_payment_summary, getString(plan.name), plan.price)
    }

    private fun refreshStatus(fetchServer: Boolean) {
        // Capture display expiry before the unchanged authorization routine can clear it.
        val cached = PremiumManager.getCachedExpiryForDisplay(this)
        if (displayInfo == null || cached > 0L) {
            displayInfo = PremiumManager.SubscriptionDisplayInfo(cached, System.currentTimeMillis(), false)
            infoReceivedAt = SystemClock.elapsedRealtime()
        }
        renderStatus()
        if (!fetchServer) return
        val generation = ++infoGeneration
        PremiumManager.loadSubscriptionInfoForDisplay(applicationContext) { info ->
            if (generation != infoGeneration || isFinishing || isDestroyed) return@loadSubscriptionInfoForDisplay
            if (info != null) { displayInfo = info; infoReceivedAt = SystemClock.elapsedRealtime(); renderStatus() }
        }
    }

    private fun renderStatus() {
        val info = displayInfo
        val now = (info?.serverTime ?: System.currentTimeMillis()) +
            if (info != null) SystemClock.elapsedRealtime() - infoReceivedAt else 0L
        val expired = (info?.expiresAt ?: 0L) > 0L && info!!.expiresAt <= now
        val active = PremiumManager.isPremium(this) && info?.blocked != true && !expired
        accountState = when { active -> AccountState.PREMIUM; expired || info?.blocked == true -> AccountState.EXPIRED; else -> AccountState.FREE }
        status.setText(when (accountState) {
            AccountState.PREMIUM -> R.string.adi_state_active
            AccountState.FREE -> R.string.adi_state_free
            AccountState.EXPIRED -> if (expired) R.string.adi_state_expired else R.string.adi_premium_inactive
        })
        statusHelp.setText(when (accountState) {
            AccountState.PREMIUM -> R.string.adi_active_help
            AccountState.FREE -> R.string.adi_free_help
            AccountState.EXPIRED -> R.string.adi_expired_help
        })
        expiry.visibility = if ((info?.expiresAt ?: 0L) > 0L) View.VISIBLE else View.GONE
        if (expiry.visibility == View.VISIBLE) {
            expiry.text = getString(if (expired) R.string.adi_expired_on else R.string.adi_valid_until, dateString(info!!.expiresAt))
        }
        remaining.visibility = if (active && (info?.expiresAt ?: 0L) > now) View.VISIBLE else View.GONE
        if (remaining.visibility == View.VISIBLE) {
            val days = ceil((info!!.expiresAt - now).toDouble() / 86_400_000L).toInt().coerceAtLeast(1)
            remaining.text = resources.getQuantityString(R.plurals.adi_days_remaining, days, days)
        }
        renew.visibility = if (accountState == AccountState.EXPIRED) View.VISIBLE else View.GONE
        planTitle.setText(if (renewing) R.string.adi_renew_subscription else R.string.adi_choose_plan)
        admin.setText(if (renewing) R.string.adi_admin_renew else R.string.adi_admin_paid)
        activationTitle.setText(if (renewing) R.string.adi_activation_renew_title else R.string.adi_activation_title)
        code.setHint(if (renewing) R.string.adi_renew_code_hint else R.string.adi_code_hint)
        configureFocus()
    }

    private fun dateString(time: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(time))
    private fun copyText(title: String, value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, value))
    }
    private fun contactAdmin() {
        val plan = plans[chosenPlan]
        val planName = getString(plan.name)
        if (tvMode) {
            showMessage(R.string.adi_continue_phone, getString(R.string.adi_admin_tv_order, "@michat88", deviceId, planName, plan.price))
            return
        }
        val message = getString(if (renewing) R.string.adi_order_renew else R.string.adi_order_new, planName, plan.price, deviceId)
        copyText(getString(R.string.adi_activation_subscription), message)
        Toast.makeText(this, R.string.adi_order_copied, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=michat88")))
        } catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))) }
            catch (_: android.content.ActivityNotFoundException) {
                showMessage(R.string.adi_contact_admin, getString(R.string.adi_admin_tv_order, "@michat88", deviceId, planName, plan.price))
            }
        }
    }

    private fun activate(isPromo: Boolean, value: String) {
        if (request.busy.value == true || request.outcome.value != null) return
        val input = value.trim().uppercase()
        if (input.isEmpty()) { code.error = getString(R.string.adi_code_required); code.requestFocus(); return }
        infoGeneration++
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(code.windowToken, 0)
        request.start(applicationContext, input, deviceId, isPromo)
    }

    private fun showOutcome(outcome: ActivationOutcome) {
        if (resultDialog?.isShowing == true) return
        refreshStatus(outcome.success)
        if (outcome.success) {
            code.text?.clear()
            val timestamp = PremiumManager.getCachedExpiryForDisplay(this)
            val until = if (timestamp > 0L) getString(R.string.adi_valid_until, dateString(timestamp)) else ""
            val message = listOfNotNull(
                if (outcome.promo) getString(R.string.adi_promo_extended) else null,
                until.takeIf { it.isNotEmpty() }, getString(R.string.adi_restart_notice)
            ).joinToString("\n\n")
            resultDialog = AlertDialog.Builder(this)
                .setTitle(if (outcome.promo) R.string.adi_promo_success else R.string.adi_activated)
                .setMessage(message).setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ ->
                    request.outcome.value = null
                    val component = packageManager.getLaunchIntentForPackage(packageName)?.component
                    if (component != null) { startActivity(Intent.makeRestartActivityTask(component)); finishAffinity() }
                    else finish()
                }.create()
        } else {
            val error = friendlyError(outcome.message)
            resultDialog = AlertDialog.Builder(this).setTitle(R.string.adi_code_error)
                .setMessage(error).setPositiveButton(R.string.ok) { _, _ -> request.outcome.value = null }.create()
            resultDialog?.setOnCancelListener { request.outcome.value = null }
        }
        resultDialog?.setOnDismissListener { resultDialog = null }
        resultDialog?.show()
        if (tvMode) resultDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun friendlyError(message: String): String {
        val text = message.lowercase(Locale.ROOT)
        val key = when {
            "timeout" in text -> R.string.adi_request_timeout
            "jaringan" in text -> R.string.adi_error_network
            "banned" in text -> R.string.adi_error_blocked
            "belum terdaftar" in text -> R.string.adi_error_device
            "masa aktif" in text && "kadaluarsa" in text -> R.string.adi_error_expired
            "pernah diklaim" in text -> R.string.adi_error_promo_used
            "kuota" in text -> R.string.adi_error_promo_quota
            "promo" in text && "kadaluarsa" in text -> R.string.adi_error_promo_expired
            "promo tidak aktif" in text -> R.string.adi_error_promo_inactive
            "promo" in text && "tidak ditemukan" in text -> R.string.adi_error_promo_invalid
            "server promo sibuk" in text -> R.string.adi_error_server
            "sinkronisasi" in text -> R.string.adi_error_server
            "tidak valid" in text -> R.string.adi_error_invalid
            "kosong" in text -> R.string.adi_code_required
            else -> return message.ifBlank { getString(R.string.adi_error_server) }
        }
        return getString(key)
    }

    private fun showPromo() {
        if (request.busy.value == true || request.outcome.value != null) return
        val input = EditText(this).apply {
            hint = getString(R.string.adi_promo_hint)
            setSingleLine(true)
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(10))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.adi_promo_title).setView(input)
            .setPositiveButton(R.string.adi_claim, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isNullOrBlank()) input.error = getString(R.string.adi_promo_required)
                else { val value = input.text.toString(); dialog.dismiss(); activate(true, value) }
            }
            if (tvMode) input.requestFocus()
        }
        dialog.show()
    }

    private fun showPaymentQr() {
        val content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val height = (resources.configuration.screenHeightDp - 160).coerceIn(180, 640)
        val panel = PaymentQrPanel(height)
        content.addView(panel.root)
        val viewport = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.adi_pay_qris).setView(viewport)
            .setPositiveButton(R.string.adi_back, null).create()
        dialog.setOnShowListener { panel.load(); if (tvMode) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        dialog.show()
    }

    private inner class PaymentQrPanel(height: Int) {
        val root = column()
        private val frame = FrameLayout(this@ActivationSubscriptionActivity)
        private val image = ImageView(this@ActivationSubscriptionActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.adi_scan_payment)
            setBackgroundColor(Color.WHITE)
        }
        private val loading = ProgressBar(this@ActivationSubscriptionActivity)
        private val feedback = label(getString(R.string.adi_qr_loading), 13f, color = muted)
        val retry = button(R.string.adi_try_again) { load() }.apply { visibility = View.GONE }
        var onChanged: () -> Unit = {}
        init {
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            frame.addView(loading, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            root.addView(frame, LinearLayout.LayoutParams(-1, dp(height)))
            root.addView(feedback)
            root.addView(retry)
        }
        fun load() {
            image.setImageDrawable(null)
            frame.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            feedback.setText(R.string.adi_qr_loading)
            feedback.visibility = View.VISIBLE
            retry.visibility = View.GONE
            onChanged()
            image.loadImage(PAYMENT_QR_URL) {
                listener(onSuccess = { _, _ ->
                    if (!isDestroyed) { loading.visibility = View.GONE; feedback.visibility = View.GONE; onChanged() }
                }, onError = { _, _ ->
                    if (!isDestroyed) {
                        frame.visibility = View.GONE
                        loading.visibility = View.GONE
                        feedback.setText(R.string.adi_qr_error)
                        retry.visibility = View.VISIBLE
                        onChanged()
                    }
                })
            }
        }
    }

    private fun configureFocus() {
        if (!::promo.isInitialized || !::qrPanel.isInitialized) return
        val payment = planButtons + listOf(expand, qrPanel.retry, admin)
        val activation = listOf(copy, renew, code, unlock, promo)
        val all = listOf(back) + payment + activation
        val available = all.filter { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
        all.forEach { it.nextFocusUpId = View.NO_ID; it.nextFocusDownId = View.NO_ID; it.nextFocusLeftId = View.NO_ID; it.nextFocusRightId = View.NO_ID }
        available.zipWithNext().forEach { (before, after) ->
            before.nextFocusDownId = after.id
            after.nextFocusUpId = before.id
            before.nextFocusForwardId = after.id
        }
        available.lastOrNull()?.nextFocusForwardId = back.id
        if (tvMode) {
            val left = payment.filter { it in available }
            val right = activation.filter { it in available }
            val target = paymentReturnTarget?.takeIf { it in left } ?: planButtons[chosenPlan]
            left.forEach { it.nextFocusRightId = right.firstOrNull()?.id ?: back.id }
            right.forEach { it.nextFocusLeftId = target.id }
            available.forEach { view ->
                view.setOnFocusChangeListener { focused, hasFocus ->
                    focused.elevation = if (hasFocus) dp(6).toFloat() else 0f
                    if (hasFocus && focused in left) {
                        paymentReturnTarget = focused
                        right.forEach { it.nextFocusLeftId = focused.id }
                    }
                }
            }
        }
    }

    private fun scrollTo(view: View) {
        val bounds = Rect(); view.getDrawingRect(bounds)
        scroll.offsetDescendantRectToMyCoords(view, bounds)
        scroll.smoothScrollTo(0, (bounds.top - dp(12)).coerceAtLeast(0))
    }
    private fun showMessage(title: Int, message: String) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show()
        if (tvMode) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun addCard(parent: LinearLayout, child: View) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun card() = column().apply {
        setPadding(dp(20), dp(16), dp(20), dp(20))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 24, 28)); cornerRadius = dp(16).toFloat() }
    }
    private fun label(value: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(8))
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
        addState(intArrayOf(-android.R.attr.state_enabled), shape(false).apply { alpha = 110 })
        addState(intArrayOf(android.R.attr.state_focused), shape(true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(true))
        addState(intArrayOf(), shape(false))
    }

    data class ActivationOutcome(val success: Boolean, val message: String, val promo: Boolean)
    /** Keeps a single request through Activity recreation; it holds no Activity reference. */
    class ActivationRequest : ViewModel() {
        val busy = MutableLiveData(false)
        val outcome = MutableLiveData<ActivationOutcome?>(null)
        private val handler = Handler(Looper.getMainLooper())
        private var generation = 0
        fun start(context: Context, input: String, deviceId: String, promo: Boolean) {
            if (busy.value == true || outcome.value != null) return
            busy.value = true
            val token = ++generation
            val timeout = Runnable {
                if (generation == token) {
                    generation++
                    busy.value = false
                    outcome.value = ActivationOutcome(false, "UI_TIMEOUT", promo)
                }
            }
            handler.postDelayed(timeout, 30_000L)
            val result: (Boolean, String) -> Unit = callback@{ success, message ->
                if (generation != token) return@callback
                generation++
                handler.removeCallbacks(timeout)
                busy.value = false
                outcome.value = ActivationOutcome(success, message, promo)
            }
            if (promo) PremiumManager.activatePromoWithCode(context, input, deviceId, restartOnSuccess = false, onResult = result)
            else PremiumManager.activatePremiumWithCode(context, input, deviceId, result)
        }
        override fun onCleared() { generation++; handler.removeCallbacksAndMessages(null); super.onCleared() }
    }
    companion object {
        private const val PAYMENT_QR_URL = "https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png"
    }
}
