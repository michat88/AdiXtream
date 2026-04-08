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
        // --- 3. LOGIKA VERSI, STATUS LANGGANAN & TOMBOL REDEEM ---
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
            // --- A. Status Langganan (Tetap di posisi lama, di bawah Versi Info) ---
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
                            topMargin = 4.toPx
                            bottomMargin = 12.toPx
                        }
                    }
                    val index = parent.indexOfChild(binding.appVersionInfo)
                    parent.addView(statusView, index + 1)
                }
                statusView.text = "Status Langganan: $premiumStatus"
            }

            // --- B. Tombol Redeem (Pindah ke posisi 'X', Paling Atas) ---
            val rootLayout = binding.root as? ViewGroup // Pastikan root adalah ViewGroup (LinearLayout vertikal)
            rootLayout?.let { root ->
                val tagButton = "btn_redeem_tag_improved"
                var redeemBtn = root.findViewWithTag<Button>(tagButton)
                
                if (redeemBtn == null) {
                    // Membuat Desain Tombol yang Jauh Lebih Keren secara Programmatic
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10.toPx.toFloat() // Sudut membulat rapi
                        setColor(Color.parseColor("#1f2937")) // Latar belakang dark navy
                        setStroke(2, Color.parseColor("#06b6d4")) // Garis pinggir cyan khas AdiXtream
                    }

                    redeemBtn = Button(ctx).apply {
                        tag = tagButton
                        text = "MASUKKAN KODE VIP / PROMO"
                        textSize = 13f
                        setTextColor(Color.parseColor("#06b6d4")) // Tulisan warna cyan
                        setTypeface(null, Typeface.BOLD) // Tulisan tebal
                        background = shape
                        isAllCaps = false
                        
                        // Padding dalam agar tombol proporsional
                        setPadding(16.toPx, 12.toPx, 16.toPx, 12.toPx)

                        // LayoutParams untuk meletakkan di paling atas (posisi 'X')
                        // Mengasumsikan root layout adalah LinearLayout Vertikal
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER
                            // Margin agar tidak menempel ke pinggir layar
                            topMargin = 16.toPx
                            leftMargin = 16.toPx
                            rightMargin = 16.toPx
                            bottomMargin = 8.toPx // Jarak ke konten di bawahnya
                        }
                    }
                    
                    // Sisipkan tombol di index 0 (paling atas konten)
                    root.addView(redeemBtn, 0)
                }

                // Logika Saat Tombol Redeem Ditekan (Sama seperti sebelumnya)
                redeemBtn.setOnClickListener {
                    val input = EditText(ctx).apply {
                        hint = "Ketik kode promo di sini..."
                        gravity = Gravity.CENTER
                        setSingleLine()
                    }

                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                        .setTitle("Aktivasi VIP / Klaim Promo")
                        .setMessage("Masukkan Kode VIP pribadi atau Kode Promo Anda:")
                        .setView(input)
                        .setPositiveButton("Klaim Sekarang") { _, _ ->
                            val code = input.text.toString()
                            val deviceId = PremiumManager.getDeviceId(ctx)
                            
                            Toast.makeText(ctx, "Memeriksa kode...", Toast.LENGTH_SHORT).show()
                            
                            PremiumManager.activatePremiumWithCode(ctx, code, deviceId) { success, msg ->
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

        // Fitur Copy (Salin)
        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(
                txt(R.string.extension_version), 
                "$appVersion $commitInfo $buildTimestamp\nStatus Langganan: $premiumStatus"
            )
            true
        }
    }
}
