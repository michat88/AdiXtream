package com.lagradost.cloudstream3.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.focusOutline
import com.mihon.material.AppBar
import java.text.DateFormat
import java.util.Date

object SettingsSubscriptionScreen : Screen {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val model = viewModel { SubscriptionViewModel(ExistingPremiumService(context)) }
        val state by model.state.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) model.refresh()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        DisposableEffect(context, model) {
            val prefs = context.getSharedPreferences("premium_fallback_prefs", Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> model.refresh() }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        SubscriptionContent(state, model::submit) { activity?.recreate() }
    }

    @Composable
    fun SubscriptionContent(state: SubscriptionState, submit: (String, Boolean) -> Unit, applyAccess: () -> Unit) {
        val context = LocalContext.current
        var activationCode by remember { mutableStateOf("") }
        var promoCode by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(state.success) {
            if (state.success) { activationCode = ""; promoCode = "" }
        }
        Scaffold(topBar = {
            AppBar(title = stringResource(R.string.adi_subscription_title), navigateUp = {
                activity?.onBackPressedDispatcher?.onBackPressed()
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "status") {
                        Section("Status Langganan") {
                            Text(when (state.tier) {
                                SubscriptionTier.ACTIVE -> "Aktif"
                                SubscriptionTier.FREE -> "Gratis"
                                SubscriptionTier.EXPIRED -> "Kadaluarsa"
                            }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            if (state.expiresAt > 0) Text("Masa aktif: ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.expiresAt))}")
                        }
                    }
                    item(key = "device") {
                        Section("Device ID") {
                            Text(state.deviceId, style = MaterialTheme.typography.titleLarge)
                            Button(modifier = Modifier.focusOutline(), onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", state.deviceId))
                                copied = true
                            }) { Text(if (copied) "ID Disalin" else "Salin") }
                        }
                    }
                    item(key = "feedback") {
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            if (state.loading) { CircularProgressIndicator(); Text("Memproses kode…") }
                            state.message?.let { Text(it, color = if (state.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                            if (state.success) Button(onClick = applyAccess, modifier = Modifier.focusOutline()) {
                                Text("Terapkan akses dan muat ulang")
                            }
                        }
                    }
                    item(key = "activation") {
                        CodeSection("Aktivasi", "Kode aktivasi", "Aktifkan", activationCode, !state.loading,
                            { activationCode = it }, { submit(activationCode, false) })
                    }
                    item(key = "promo") {
                        CodeSection("Kode Promo", "Kode promo", "Klaim Promo", promoCode, !state.loading,
                            { promoCode = it }, { submit(promoCode, true) })
                    }
                    item(key = "plans") {
                        Section("Informasi Langganan") {
                            Text("1 Bulan  ·  Rp 10.000")
                            Text("6 Bulan  ·  Rp 30.000")
                            Text("1 Tahun  ·  Rp 50.000")
                            Text("Scan QRIS untuk bayar", style = MaterialTheme.typography.titleMedium)
                            AsyncImage(model = "https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png",
                                contentDescription = "QRIS pembayaran AdiXtream", contentScale = ContentScale.Fit,
                                modifier = Modifier.sizeIn(maxWidth = 320.dp).fillMaxWidth().heightIn(min = 240.dp, max = 320.dp))
                            Text("OVO / DANA / GOPAY / SHOPEEPAY / BANK")
                            TextButton(onClick = { CloudStreamApp.openBrowser("https://t.me/michat88") }, modifier = Modifier.focusOutline()) {
                                Text("Hubungi admin · @michat88")
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
    @Composable private fun CodeSection(title: String, label: String, action: String, code: String, enabled: Boolean,
                                       onChange: (String) -> Unit, onSubmit: () -> Unit) {
        Section(title) {
            OutlinedTextField(value = code, onValueChange = onChange, enabled = enabled,
                label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusOutline())
            Button(onClick = onSubmit, enabled = enabled && code.isNotBlank(), modifier = Modifier.focusOutline()) { Text(action) }
        }
    }
}
