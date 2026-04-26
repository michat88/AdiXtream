package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.MainSettingsBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.errorProfilePic
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash 
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.widget.Toast

// --- IMPORT TAMBAHAN ADIXTREAM & REDEEM UI ---
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.lagradost.cloudstream3.PremiumManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.text.InputFilter
import android.widget.FrameLayout
import com.lagradost.cloudstream3.utils.UIHelper.toPx
// ----------------------------------------------

class SettingsFragment : BaseFragment<MainSettingsBinding>(
    BaseFragment.BindingCreator.Inflate(MainSettingsBinding::inflate)
) {
    companion object {
        fun PreferenceFragmentCompat?.getPref(id: Int): Preference? {
            if (this == null) return null
            return try {
                findPreference(getString(id))
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        fun PreferenceFragmentCompat?.hidePrefs(ids: List<Int>, layoutFlags: Int) {
            if (this == null) return
            try {
                ids.forEach {
                    getPref(it)?.isVisible = !isLayout(layoutFlags)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        fun Preference?.hideOn(layoutFlags: Int): Preference? {
            if (this == null) return null
            this.isVisible = !isLayout(layoutFlags)
            return if(this.isVisible) this else null
        }

        fun PreferenceFragmentCompat.setPaddingBottom() {
            if (isLayout(TV or EMULATOR)) {
                listView?.setPadding(0, 0, 0, 0)
            }
        }

        fun PreferenceFragmentCompat.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = this?.view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setUpToolbar(title: String) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    setNavigationOnClickListener {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
            }
        }

        fun Fragment?.setUpToolbar(@StringRes title: Int) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    children.firstOrNull { it is ImageView }?.tag = getString(R.string.tv_no_focus_tag)
                    setNavigationOnClickListener {
                        safe { activity?.onBackPressedDispatcher?.onBackPressed() }
                    }
                }
            }
        }

        fun Fragment.setSystemBarsPadding() {
            view?.let {
                fixSystemBarsPadding(
                    it,
                    padLeft = isLayout(TV or EMULATOR),
                    padBottom = if (isLayout(TV or EMULATOR)) false else isLandscape()
                )
            }
        }

        fun getFolderSize(dir: File): Long {
            var size: Long = 0
            dir.listFiles()?.let {
                for (file in it) {
                    size += if (file.isFile) file.length() else getFolderSize(file)
                }
            }
            return size
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = if (isLayout(TV or EMULATOR)) false else isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: MainSettingsBinding) {
        fun navigate(id: Int) {
            activity?.navigate(id, Bundle())
        }

        fun hasProfilePictureFromAccountManagers(accountManagers: Array<AuthRepo>): Boolean {
            for (syncApi in accountManagers) {
                val login = syncApi.authUser()
                val pic = login?.profilePicture ?: continue
                binding.settingsProfilePic.let { imageView ->
                    imageView.loadImage(pic) {
                        error { getImageFromDrawable(context ?: return@error null, errorProfilePic) }
                    }
                }
                binding.settingsProfileText.text = login.name
                return true 
            }
            return false 
        }

        if (!hasProfilePictureFromAccountManagers(AccountManager.allApis)) {
            val activity = activity ?: return
            val currentAccount = try {
                DataStoreHelper.accounts.firstOrNull {
                    it.keyIndex == DataStoreHelper.selectedKeyIndex
                } ?: activity.let { DataStoreHelper.getDefaultAccount(activity) }
            } catch (t: IllegalStateException) {
                Log.e("AccountManager", "Activity not found", t)
                null
            }
            binding.settingsProfilePic.loadImage(currentAccount?.image)
            binding.settingsProfileText.text = currentAccount?.name
        }

        binding.apply {
            settingsExtensions.setOnClickListener {
                try {
                    val bundle = Bundle()
                    val context = requireContext()
                    val isPremium = PremiumManager.isPremium(context)
                    val repoName = if (isPremium) "Repository Premium" else "Repository Gratis"
                    val repoUrl = if (isPremium) PremiumManager.PREMIUM_REPO_URL else PremiumManager.FREE_REPO_URL
                    bundle.putString("name", repoName)
                    bundle.putString("url", repoUrl)
                    bundle.putBoolean("isLocal", false)
                    activity?.navigate(R.id.navigation_settings_plugins, bundle)
                } catch (e: Exception) {
                    logError(e)
                }
            }

            appVersionInfo.setOnClickListener {
                val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                builder.setTitle("Tentang AdiXtream")
                builder.setMessage("AdiXtream dikembangkan oleh michat88.\n\nAplikasi ini berbasis pada proyek open-source CloudStream.\n\nTerima kasih kepada Developer CloudStream (Lagradost & Tim) atas kode sumber yang luar biasa ini.")
                builder.setNeutralButton("Kunjungi Website") { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://michat88.github.io/adixtream-web/"))
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                builder.setPositiveButton("Tutup") { dialog, _ -> dialog.dismiss() }
                val dialog: AlertDialog = builder.create()
                dialog.show()

                val webButton: Button? = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                webButton?.let { button ->
                    val fullText = "Kunjungi Website"
                    val spannable = android.text.SpannableString(fullText)
                    spannable.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FF0000")), 0, 8, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 8, fullText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    button.text = spannable
                }
            }

            listOf(
                settingsGeneral to R.id.action_navigation_global_to_navigation_settings_general,
                settingsPlayer to R.id.action_navigation_global_to_navigation_settings_player,
                settingsCredits to R.id.action_navigation_global_to_navigation_settings_account,
                settingsUi to R.id.action_navigation_global_to_navigation_settings_ui,
                settingsProviders to R.id.action_navigation_global_to_navigation_settings_providers,
                settingsUpdates to R.id.action_navigation_global_to_navigation_settings_updates,
            ).forEach { (view, navigationId) ->
                view.apply {
                    setOnClickListener { navigate(navigationId) }
                    if (isLayout(TV)) {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
            }
            if (isLayout(TV)) settingsGeneral.requestFocus()
        }

        // ==========================================================
        // --- LOGIKA VERSI, STATUS LANGGANAN & DEVICE ID ---
        // ==========================================================
        val appVersion = BuildConfig.VERSION_NAME
        val commitInfo = activity?.currentCommitHash() ?: ""
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG, Locale.getDefault()).apply { 
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        binding.commitHash.text = commitInfo 
        
        val context = context
        val premiumStatus = if (context != null) {
            val dateStr = PremiumManager.getExpiryDateString(context)
            if (dateStr == "Gratis") "Gratis" else "Aktif s/d $dateStr"
        } else {
            "Gagal Memuat"
        }
        val deviceId = context?.let { PremiumManager.getDeviceId(it) } ?: "-"

        context?.let { ctx ->
            val isTvMode = isLayout(TV)

            // --- A1. SUNTIK TEKS DI SEBELAH PROFIL "DEFAULT" (TV & HP) ---
            val profileParent = binding.settingsProfileText.parent as? ViewGroup
            profileParent?.let { pParent ->
                val topTag = "status_tv_tag"
                if (pParent.findViewWithTag<TextView>(topTag) == null) {
                    val tvTopRight = TextView(ctx).apply {
                        tag = topTag
                        // Teks beda untuk TV dan HP
                        text = if (isTvMode) "ID: $deviceId   •   Status: $premiumStatus" else "ID: $deviceId 📋"
                        textSize = if (isTvMode) 13f else 12f 
                        setTextColor(Color.parseColor("#94a3b8"))
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL

                        // Fitur Copy Khusus HP
                        if (!isTvMode) {
                            setOnClickListener {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(ctx, "Device ID ($deviceId) berhasil disalin!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    
                    if (pParent is ConstraintLayout) {
                        tvTopRight.id = View.generateViewId()
                        pParent.addView(tvTopRight)
                        val set = ConstraintSet()
                        set.clone(pParent)
                        set.connect(tvTopRight.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 32.toPx)
                        set.connect(tvTopRight.id, ConstraintSet.TOP, binding.settingsProfileText.id, ConstraintSet.TOP)
                        set.connect(tvTopRight.id, ConstraintSet.BOTTOM, binding.settingsProfileText.id, ConstraintSet.BOTTOM)
                        set.applyTo(pParent)
                    } else {
                        binding.settingsProfileText.text = "${binding.settingsProfileText.text}   •   ID: $deviceId" + (if (isTvMode) "   •   $premiumStatus" else " 📋")
                    }
                }
            }

            // --- A2. STATUS LANGGANAN DI BAWAH (KHUSUS HP) ---
            val versionParent = binding.appVersionInfo.parent as? ViewGroup
            versionParent?.let { parent ->
                if (!isTvMode) {
                    val tagStatus = "status_langganan_tag"
                    var statusView = parent.findViewWithTag<TextView>(tagStatus)
                    
                    if (statusView == null) {
                        statusView = TextView(ctx).apply {
                            tag = tagStatus
                            gravity = Gravity.CENTER 
                            textSize = 12f 
                            setTextColor(Color.parseColor("#94a3b8")) 
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = 12.toPx 
                            }
                        }
                        val index = parent.indexOfChild(binding.appVersionInfo)
                        parent.addView(statusView, index + 1)
                    }
                    // Hanya menampilkan Status Langganan (Device ID sudah pindah ke atas)
                    statusView.text = "Status Langganan: $premiumStatus"
                }

                // --- B. Tombol KODE PROMO (Gaya Netflix) ---
                val tagBtnPromo = "btn_promo_tag_only"
                var btnPromo = parent.findViewWithTag<Button>(tagBtnPromo)
                
                if (btnPromo == null) {
                    val shapeNormal = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4.toPx.toFloat() 
                        setColor(Color.parseColor("#E50914")) 
                    }
                    val shapeFocused = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4.toPx.toFloat()
                        setColor(Color.parseColor("#E50914")) 
                        setStroke(3.toPx, Color.WHITE) 
                    }
                    val stateListBg = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), shapeFocused)
                        addState(intArrayOf(), shapeNormal)
                    }
                    val textColorStateList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.WHITE, Color.WHITE))

                    btnPromo = Button(ctx).apply {
                        id = View.generateViewId() 
                        tag = tagBtnPromo
                        text = "MASUKKAN KODE PROMO"
                        textSize = 12f
                        setTextColor(textColorStateList) 
                        setTypeface(null, Typeface.BOLD) 
                        background = stateListBg 
                        isAllCaps = false
                        isFocusable = true 
                        val padVert = if (isTvMode) 6.toPx else 12.toPx
                        setPadding(32.toPx, padVert, 32.toPx, padVert)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER
                            topMargin = if (isTvMode) 4.toPx else 8.toPx
                            bottomMargin = if (isTvMode) 0 else 24.toPx
                        }
                    }

                    val targetView = if (isTvMode) binding.appVersionInfo else parent.findViewWithTag<TextView>("status_langganan_tag") ?: binding.appVersionInfo
                    val indexBtn = parent.indexOfChild(targetView)
                    parent.addView(btnPromo, indexBtn + 1)

                    btnPromo.setOnClickListener {
                        val inputContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(24.toPx, 24.toPx, 24.toPx, 8.toPx)
                        }
                        val input = EditText(ctx).apply {
                            hint = "KETIK KODE..."
                            setHintTextColor(Color.parseColor("#8C8C8C"))
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            setSingleLine()
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 4.toPx.toFloat()
                                setColor(Color.parseColor("#333333")) 
                            }
                            setPadding(16.toPx, 16.toPx, 16.toPx, 16.toPx)
                            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(10))
                        }
                        inputContainer.addView(input)

                        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                            .setTitle("Klaim Kode Promo")
                            .setMessage("Masukkan kodenya di bawah ini:")
                            .setView(inputContainer) 
                            .setPositiveButton("Klaim") { _, _ ->
                                val code = input.text.toString()
                                Toast.makeText(ctx, "Memeriksa kode...", Toast.LENGTH_SHORT).show()
                                
                                PremiumManager.activatePromoWithCode(ctx, code, deviceId) { success, msg ->
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    if (success) {
                                        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                        ctx.startActivity(Intent.makeRestartActivityTask(intent?.component))
                                        Runtime.getRuntime().exit(0)
                                    }
                                }
                            }
                            .setNegativeButton("Batal", null).show()
                    }
                }
            }
        }

        binding.appVersionInfo.isFocusable = true
        if (isLayout(TV)) {
            binding.settingsExtensions.nextFocusDownId = binding.appVersionInfo.id
            binding.appVersionInfo.nextFocusUpId = binding.settingsExtensions.id
            val parent = binding.appVersionInfo.parent as? ViewGroup
            parent?.findViewWithTag<Button>("btn_promo_tag_only")?.let { btn ->
                binding.appVersionInfo.nextFocusDownId = btn.id
                btn.nextFocusUpId = binding.appVersionInfo.id
            }
        }

        binding.appVersionInfo.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.setBackgroundColor(Color.parseColor("#1Affffff")) 
                view.scaleX = 1.02f
                view.scaleY = 1.02f
            } else {
                view.setBackgroundColor(Color.TRANSPARENT)
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }

        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(
                txt(R.string.extension_version), 
                "Device ID: $deviceId\n$appVersion $commitInfo $buildTimestamp\nStatus Langganan: $premiumStatus"
            )
            true
        }
    }
}
