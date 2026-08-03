package com.lagradost.cloudstream3.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.LogcatBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.BackupWorkManager
import com.lagradost.cloudstream3.ui.BasePreferenceFragmentCompat
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.InAppUpdater.runAutoUpdate
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.txt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsUpdates : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_updates)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    @Suppress("DEPRECATION_ERROR")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_updates, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // ==========================================
        // SUNTIKAN ADIXTREAM: MATIKAN AUTO BACKUP
        // ==========================================
        // Memaksa nilai preferensi menjadi 0 (Mati)
        settingsManager.edit {
            putInt(getString(R.string.automatic_backup_key), 0)
        }
        // Memberitahu sistem WorkManager untuk menghentikan proses latar belakang
        (context ?: CloudStreamApp.context)?.let { ctx ->
            BackupWorkManager.enqueuePeriodicWork(ctx, 0L)
        }
        // ==========================================

        getPref(R.string.redo_setup_key)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.navigation_setup_language)
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.show_logcat_key)?.setOnPreferenceClickListener { pref ->
            val builder = AlertDialog.Builder(pref.context, R.style.AlertDialogCustom)

            val binding = LogcatBinding.inflate(layoutInflater, null, false)
            builder.setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            val logList = mutableListOf<String>()
            try {
                val process = Runtime.getRuntime().exec("logcat -d")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                bufferedReader.lineSequence().forEach { logList.add(it) }
            } catch (e: Exception) {
                logError(e) 
            }

            val adapter = LogcatAdapter().apply { submitList(logList) }
            binding.logcatRecyclerView.layoutManager = LinearLayoutManager(pref.context)
            binding.logcatRecyclerView.adapter = adapter

            binding.copyBtt.setOnClickListener {
                clipboardHelper(txt("Logcat"), logList.joinToString("\n"))
                dialog.dismissSafe(activity)
            }

            binding.clearBtt.setOnClickListener {
                Runtime.getRuntime().exec("logcat -c")
                dialog.dismissSafe(activity)
            }

            binding.saveBtt.setOnClickListener {
                val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(Date(currentTimeMillis()))
                var fileStream: OutputStream? = null
                try {
                    fileStream = VideoDownloadManager.setupStream(
                        it.context,
                        "logcat_${date}",
                        null,
                        "txt",
                        false
                    ).openNew()
                    fileStream.writer().use { writer -> writer.write(logList.joinToString("\n")) }
                    dialog.dismissSafe(activity)
                } catch (t: Throwable) {
                    logError(t)
                    showToast(t.message)
                }
            }

            binding.closeBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.apk_installer_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.apk_installer_pref)
            val prefValues = resources.getIntArray(R.array.apk_installer_values)

            // MENGUBAH DEFAULT DI SINI: Angka 0 diubah jadi 1 (Versi lama)
            val currentInstaller =
                settingsManager.getInt(getString(R.string.apk_installer_key), 1)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentInstaller),
                getString(R.string.apk_installer_settings),
                true,
                {}
            ) { num ->
                try {
                    settingsManager.edit {
                        putInt(getString(R.string.apk_installer_key), prefValues[num])
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.manual_check_update_key)?.let { pref ->
            // Menampilkan Versi AdiXtream (APP_VERSION)
            pref.summary = BuildConfig.APP_VERSION
            pref.setOnPreferenceClickListener {
                ioSafe {
                    if (activity?.runAutoUpdate(false) == false) {
                        activity?.runOnUiThread {
                            showToast(
                                R.string.no_update_found,
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                }
                return@setOnPreferenceClickListener true
            }
        }

        getPref(R.string.auto_download_plugins_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.auto_download_plugin)
            val prefValues =
                enumValues<AutoDownloadMode>().sortedBy { x -> x.value }.map { x -> x.value }

            val current = settingsManager.getInt(getString(R.string.auto_download_plugins_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.automatic_plugin_download_mode_title),
                true,
                {}
            ) { num ->
                settingsManager.edit {
                    putInt(getString(R.string.auto_download_plugins_key), prefValues[num])
                }
                (context ?: CloudStreamApp.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.manual_update_plugins_key)?.setOnPreferenceClickListener {
            ioSafe {
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity ?: return@ioSafe)
            }
            return@setOnPreferenceClickListener true 
        }

        // --- MENYEMBUNYIKAN MENU YANG TIDAK DIBUTUHKAN ---
        
        // 1. Menyembunyikan tombol "Pasang versi pra-rilis"
        getPref(R.string.install_prerelease_key)?.isVisible = false
        
        // 2. Menyembunyikan Parent/Header Kategori Backup
        getPref(R.string.backup_key)?.parent?.isVisible = false 
        
        // Menyembunyikan item-item di dalamnya secara tuntas
        getPref(R.string.backup_key)?.isVisible = false
        getPref(R.string.automatic_backup_key)?.isVisible = false
        getPref(R.string.restore_key)?.isVisible = false
        getPref(R.string.backup_path_key)?.isVisible = false
    
        // 3. Menampilkan tombol Logcat (Diubah menjadi true)
        getPref(R.string.show_logcat_key)?.isVisible = false
        // ------------------------------------------------
    }
}
