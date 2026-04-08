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
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt
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
import com.lagradost.cloudstream3.PremiumManager

// --- IMPORT TAMBAHAN UNTUK TV FOCUS & INPUT LIMIT ---
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.text.InputFilter
import android.widget.FrameLayout
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
                listView?.setPadding(0, 0, 0, 100.toPx)
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
                    padBottom = isLandscape()
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
            padBottom = isLandscape(),
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

            // --- 1. MODIFIKASI ADIXTREAM: BYPASS MASUK LANGSUNG KE PLUGINS ---
            settingsExtensions.setOnClickListener {
                try {
                    val bundle = Bundle()
                    val context = requireContext()

                    // Cek Status Premium User
                    val isPremium = PremiumManager.isPremium(context)

                    // Tentukan Nama Repo & URL berdasarkan status
                    val repoName = if (isPremium) "Repository Premium" else "Repository Gratis"
                    val repoUrl = if (isPremium) PremiumManager.PREMIUM_REPO_URL else PremiumManager.FREE_REPO_URL

                    // Masukkan ke Bundle
                    bundle.putString("name", repoName)
                    bundle.putString("url", repoUrl)
                    bundle.putBoolean("isLocal", false)

                    // Navigasi langsung ke PluginsFragment (melewati Extensions)
                    activity?.navigate(R.id.navigation_settings_plugins, bundle)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            // --------------------------------------------------------

            // --- 2. MODIFIKASI ADIXTREAM: LOGIKA TOMBOL TENTANG (MERAH PUTIH) ---
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

                builder.setPositiveButton("Tutup") { dialog, _ ->
                    dialog.dismiss()
                }

                val dialog: AlertDialog = builder.create()
                dialog.show()

                val webButton: Button? = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                webButton?.let { button ->
                    val fullText = "Kunjungi Website"
                    val spannable = android.text.SpannableString(fullText)

                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(Color.parseColor("#FF0000")),
                        0, 8,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(Color.WHITE),
                        8, fullText.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    button.text = spannable
                }
            }
            // --------------------------------------------------

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

            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }
        }

        // ==========================================================
        // --- 3. LOGIKA VERSI, STATUS LANGGANAN & TOMBOL PROMO SAJA ---
        // ==========================================================
        
        val appVersion = BuildConfig.APP_VERSION
        val commitInfo = getString(R.string.commit_hash)
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(
            DateFormat.LONG, DateFormat.LONG, Locale.getDefault()
        ).apply { 
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        
        // Ambil Status Premium
        val context = context
        val premiumStatus = if (context != null) {
            val dateStr = PremiumManager.getExpiryDateString(context)
            if (dateStr == "Non-Premium") "Gratis" else "Aktif s/d $dateStr"
        } else {
            "Gagal Memuat"
        }

        // === LOGIKA SUNTIK TAMPILAN SECARA OTOMATIS (SISI ANDROID) ===
        context?.let { ctx ->
            // Cek apakah ini mode TV untuk penyesuaian tata letak
            val isTvMode = isLayout(TV)

            // --- A. Status Langganan ---
            val versionParent = binding.appVersionInfo.parent as? ViewGroup
            versionParent?.let { parent ->
                val tagStatus = "status_langganan_tag"
                var statusView = parent.findViewWithTag<TextView>(tagStatus)
                
                if (statusView == null) {
                    statusView = TextView(ctx).apply {
                        tag = tagStatus
                        gravity = Gravity.CENTER 
                        textSize = 12f 
                        setTextColor(Color.parseColor("#94a3b8")) 
                        
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            // Margin dirapatkan maksimal saat di TV
                            topMargin = 0
                            bottomMargin = if (isTvMode) 0 else 12.toPx 
                        }
                    }
                    val index = parent.indexOfChild(binding.appVersionInfo)
                    parent.addView(statusView, index + 1)
                }
                statusView.text = "Status Langganan: $premiumStatus"

                // --- B. Tombol KODE PROMO Saja (Dengan Efek Fokus TV) ---
                val tagBtnPromo = "btn_promo_tag_only"
                var btnPromo = parent.findViewWithTag<Button>(tagBtnPromo)
                
                if (btnPromo == null) {
                    // Desain saat normal
                    val shapeNormal = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10.toPx.toFloat()
                        setColor(Color.parseColor("#1f2937")) 
                        setStroke(2, Color.parseColor("#a855f7")) // Ungu Promo
                    }
                    
                    // Desain saat disorot Remote (Fokus)
                    val shapeFocused = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10.toPx.toFloat()
                        setColor(Color.parseColor("#a855f7")) // Background terisi ungu
                        setStroke(2, Color.parseColor("#ffffff")) // Border putih
                    }

                    // Gabungkan desain ke dalam StateList (Bisa berubah otomatis)
                    val stateListBg = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), shapeFocused)
                        addState(intArrayOf(), shapeNormal)
                    }

                    // Warna teks berubah jadi putih saat disorot remote
                    val textColorStateList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_focused), // Saat fokus
                            intArrayOf() // Default
                        ),
                        intArrayOf(
                            Color.WHITE, // Teks Putih
                            Color.parseColor("#a855f7") // Teks Ungu
                        )
                    )

                    btnPromo = Button(ctx).apply {
                        tag = tagBtnPromo
                        text = "MASUKKAN KODE PROMO"
                        textSize = 12f
                        setTextColor(textColorStateList) // Terapkan efek teks
                        setTypeface(null, Typeface.BOLD) 
                        background = stateListBg // Terapkan efek background
                        isAllCaps = false
                        isFocusable = true // SANGAT PENTING UNTUK TV
                        
                        // Padding dikecilkan sedikit di TV biar lebih irit ruang vertikal
                        val padVert = if (isTvMode) 8.toPx else 12.toPx
                        setPadding(32.toPx, padVert, 32.toPx, padVert)
                        
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER
                            // Perkecil margin di TV sampai mendekati 0 agar anti-scroll
                            topMargin = if (isTvMode) 2.toPx else 8.toPx
                            bottomMargin = if (isTvMode) 2.toPx else 24.toPx
                        }
                    }

                    val indexBtn = parent.indexOfChild(statusView)
                    parent.addView(btnPromo, indexBtn + 1)

                    // === LOGIKA KLIK TOMBOL PROMO (DENGAN UI MODERN) ===
                    btnPromo.setOnClickListener {
                        
                        // Container dengan layout vertikal untuk form input yang lebih rapi
                        val inputContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            val padHorizontal = 24.toPx
                            setPadding(padHorizontal, 24.toPx, padHorizontal, 8.toPx)
                        }

                        // Desain modern untuk kolom input (Rounded corners + Outline senada tombol)
                        val inputBg = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 12.toPx.toFloat()
                            setColor(Color.parseColor("#1f2937")) // Warna background gelap
                            setStroke(2, Color.parseColor("#a855f7")) // Border ungu AdiXtream
                        }

                        val input = EditText(ctx).apply {
                            hint = "KETIK KODE..."
                            setHintTextColor(Color.parseColor("#94a3b8"))
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            setSingleLine()
                            background = inputBg 
                            
                            // Padding dalam text box biar teks tidak nempel ke garis
                            val pad = 16.toPx
                            setPadding(pad, pad, pad, pad)
                            
                            // FITUR BARU: Paksa Huruf Besar Semua & Batasi maksimal 10 Karakter
                            filters = arrayOf(
                                InputFilter.AllCaps(),
                                InputFilter.LengthFilter(10)
                            )
                            
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                        
                        // Masukkan input ketikan ke dalam wadah
                        inputContainer.addView(input)

                        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                            .setTitle("Klaim Kode Promo")
                            .setMessage("Masukkan kodenya di bawah ini:")
                            .setView(inputContainer) // Pasang wadah baru ke dialog
                            .setPositiveButton("Klaim") { _, _ ->
                                val code = input.text.toString()
                                val deviceId = PremiumManager.getDeviceId(ctx)
                                Toast.makeText(ctx, "Memeriksa kode...", Toast.LENGTH_SHORT).show()
                                
                                PremiumManager.activatePromoWithCode(ctx, code, deviceId) { success, msg ->
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    if (success) {
                                        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                        ctx.startActivity(mainIntent)
                                        Runtime.getRuntime().exit(0)
                                    }
                                }
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                }
            }
        }

        // --- C. EFEK FOKUS UNTUK TEKS VERSI APLIKASI DI TV ---
        binding.appVersionInfo.isFocusable = true
        binding.appVersionInfo.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // Beri latar belakang transparan agak terang dan perbesar sedikit saat disorot remote
                view.setBackgroundColor(Color.parseColor("#1Affffff")) 
                view.scaleX = 1.02f
                view.scaleY = 1.02f
            } else {
                // Kembalikan ke normal saat ditinggalkan
                view.setBackgroundColor(Color.TRANSPARENT)
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }

        // Fitur Copy (Salin) & Tekan Biasa
        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(
                txt(R.string.extension_version), 
                "$appVersion $commitInfo $buildTimestamp\nStatus Langganan: $premiumStatus"
            )
            true
        }
    }
}
