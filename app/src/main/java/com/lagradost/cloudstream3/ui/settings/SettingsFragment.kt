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

// --- IMPORT TAMBAHAN ---
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Button
import androidx.appcompat.app.AlertDialog
// -----------------------

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
            
            // --- 1. MODIFIKASI: BYPASS MASUK LANGSUNG KE PLUGINS ---
            settingsExtensions.setOnClickListener {
                try {
                    val bundle = Bundle()
                    // Nama yang akan muncul di header toolbar
                    bundle.putString("name", "AmanGacor") 
                    // URL Repository kamu (WAJIB SAMA PERSIS DENGAN MAIN ACTIVITY)
                    bundle.putString("url", "https://raw.githubusercontent.com/aldry84/AdiManuLateri4/refs/heads/builds/repo.json") 
                    bundle.putBoolean("isLocal", false)
                    
                    // Navigasi langsung ke PluginsFragment, melewati ExtensionsFragment
                    activity?.navigate(R.id.navigation_settings_plugins, bundle)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            // --------------------------------------------------------

            // --- 2. LOGIKA TOMBOL TENTANG (WARNA MERAH PUTIH) ---
            settingsAbout.setOnClickListener {
                val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                builder.setTitle("Tentang AdiXtream")
                builder.setMessage("AdiXtream dikembangkan oleh michat88.\n\nAplikasi ini berbasis pada proyek open-source CloudStream.\n\nTerima kasih kepada Developer CloudStream (Lagradost & Tim) atas kode sumber yang luar biasa ini.")
                
                builder.setNeutralButton("Kode Sumber") { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/michat88/AdiXtream"))
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

                val sourceCodeButton: Button? = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                sourceCodeButton?.let { button ->
                    val fullText = "Kode Sumber"
                    val spannable = SpannableString(fullText)

                    spannable.setSpan(
                        ForegroundColorSpan(Color.parseColor("#FF0000")),
                        0, 4,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    spannable.setSpan(
                        ForegroundColorSpan(Color.WHITE),
                        5, fullText.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    button.text = spannable
                }
            }
            // --------------------------------------------------

            // --- 3. DAFTAR MENU LAINNYA ---
            // Catatan: 'settingsExtensions' DIHAPUS dari list ini agar tidak bentrok dengan logika khusus di atas
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

        val appVersion = BuildConfig.APP_VERSION
        val commitInfo = getString(R.string.commit_hash)
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG,
            Locale.getDefault()
        ).apply { timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(txt(R.string.extension_version), "$appVersion $commitInfo $buildTimestamp")
            true
        }
    }
}
