package com.lagradost.cloudstream3.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch
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

    // Scoped to this destination: the user's theme elsewhere remains unchanged.
    private val PremiumColors = darkColorScheme(
        primary = Color(0xFFFF6675), onPrimary = Color(0xFF290007),
        primaryContainer = Color(0xFFB82038), onPrimaryContainer = Color.White,
        background = Color(0xFF0C0D10), onBackground = Color(0xFFF6F3F4),
        surface = Color(0xFF18191F), onSurface = Color(0xFFF6F3F4),
        surfaceVariant = Color(0xFF24252D), onSurfaceVariant = Color(0xFFC5C1CC),
        outline = Color(0xFF77737F), outlineVariant = Color(0xFF383640),
        error = Color(0xFFFFB4AB),
    )

    @Composable
    fun SubscriptionContent(state: SubscriptionState, submit: (String, Boolean) -> Unit, applyAccess: () -> Unit) {
        MaterialTheme(colorScheme = PremiumColors) {
            PremiumContent(state, submit, applyAccess)
        }
    }

    @Composable
    private fun PremiumContent(state: SubscriptionState, submit: (String, Boolean) -> Unit, applyAccess: () -> Unit) {
        val context = LocalContext.current
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val activationFocus = remember { FocusRequester() }
        val plansFocus = remember { FocusRequester() }
        var activationCode by remember { mutableStateOf("") }
        var promoCode by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(state.success) {
            if (state.success) { activationCode = ""; promoCode = "" }
        }
        LaunchedEffect(state.loading, state.message) {
            if (state.loading || state.message != null) listState.animateScrollToItem(2)
        }
        Scaffold(topBar = {
            AppBar(title = stringResource(R.string.adi_subscription_title), navigateUp = {
                (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            })
        }, containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "status") {
                        Card(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFF59313E))) {
                            Column(
                                Modifier.fillMaxWidth().background(Brush.linearGradient(
                                    listOf(Color(0xFF3C1726), Color(0xFF1B1A23), Color(0xFF17181E))
                                )).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text("ADIXTREAM", style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFF1C6CF), fontWeight = FontWeight.Bold)
                                Text("Hiburan pilihan.\nPengalaman premium.", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold)
                                Text("Status Langganan", style = MaterialTheme.typography.titleMedium)
                                val statusColor = when (state.tier) {
                                    SubscriptionTier.ACTIVE -> Color(0xFFA7E4BC)
                                    SubscriptionTier.FREE -> Color(0xFFF2D49D)
                                    SubscriptionTier.EXPIRED -> MaterialTheme.colorScheme.error
                                }
                                Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))) {
                                    Text(when (state.tier) {
                                        SubscriptionTier.ACTIVE -> "Aktif"
                                        SubscriptionTier.FREE -> "Gratis"
                                        SubscriptionTier.EXPIRED -> "Kadaluarsa"
                                    }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = statusColor, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(when (state.tier) {
                                    SubscriptionTier.ACTIVE -> "Akses premium Anda sudah aktif di perangkat ini."
                                    SubscriptionTier.FREE -> "Aktifkan kode Anda atau pilih paket langganan."
                                    SubscriptionTier.EXPIRED -> "Perpanjang langganan untuk menikmati akses premium kembali."
                                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.expiresAt > 0) Text("Masa aktif: ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.expiresAt))}")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch {
                                        listState.animateScrollToItem(3)
                                        withFrameNanos { }
                                        activationFocus.requestFocus()
                                    } },
                                        colors = premiumButtonColors(), modifier = Modifier.heightIn(min = 52.dp).focusOutline()) {
                                        Text("Aktivasi kode", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(onClick = { scope.launch {
                                        listState.animateScrollToItem(5)
                                        withFrameNanos { }
                                        plansFocus.requestFocus()
                                    } },
                                        modifier = Modifier.heightIn(min = 52.dp).focusOutline()) {
                                        Text("Berlangganan")
                                    }
                                }
                            }
                        }
                    }
                    item(key = "device") {
                        Section("Device ID", "Gunakan ID perangkat ini saat menghubungi admin.") {
                            Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
                                Text(state.deviceId, Modifier.fillMaxWidth().padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                            }
                            OutlinedButton(modifier = Modifier.heightIn(min = 48.dp).focusOutline(), onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", state.deviceId))
                                copied = true
                            }) { Text(if (copied) "ID Disalin" else "Salin") }
                        }
                    }
                    item(key = "feedback") {
                        AnimatedVisibility(visible = state.loading || state.message != null,
                            enter = fadeIn(), exit = fadeOut()) {
                            Section("Status aktivasi") {
                                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (state.loading) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text("Memproses kode…")
                                    }
                                    state.message?.let { Text(it, color = if (state.success)
                                        Color(0xFFA7E4BC) else MaterialTheme.colorScheme.error) }
                                    if (state.success) Button(onClick = applyAccess, colors = premiumButtonColors(),
                                        modifier = Modifier.heightIn(min = 48.dp).focusOutline()) {
                                        Text("Terapkan akses dan muat ulang")
                                    }
                                }
                            }
                        }
                    }
                    item(key = "activation") {
                        CodeSection("Aktivasi", "Sudah punya kode? Aktifkan akses Anda di sini.",
                            "Kode aktivasi", "Aktifkan", activationCode, !state.loading,
                            { activationCode = it }, { submit(activationCode, false) }, activationFocus)
                    }
                    item(key = "promo") {
                        CodeSection("Kode Promo", "Klaim penawaran dengan kode promo yang Anda miliki.",
                            "Kode promo", "Klaim Promo", promoCode, !state.loading,
                            { promoCode = it }, { submit(promoCode, true) })
                    }
                    item(key = "plans") {
                        Section("Informasi Langganan", "Pilih masa aktif yang sesuai untuk Anda.") {
                            BoxWithConstraints {
                                if (maxWidth >= 560.dp) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Plan("1 Bulan", "Rp 10.000", Modifier.weight(1f))
                                        Plan("6 Bulan", "Rp 30.000", Modifier.weight(1f))
                                        Plan("1 Tahun", "Rp 50.000", Modifier.weight(1f))
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Plan("1 Bulan", "Rp 10.000")
                                        Plan("6 Bulan", "Rp 30.000")
                                        Plan("1 Tahun", "Rp 50.000")
                                    }
                                }
                            }
                            Button(onClick = { CloudStreamApp.openBrowser("https://t.me/michat88") },
                                colors = premiumButtonColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(plansFocus).focusOutline()) {
                                Text("Berlangganan · Hubungi admin", fontWeight = FontWeight.Bold)
                            }
                            Text("Hubungi admin · @michat88", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("Scan QRIS untuk bayar", style = MaterialTheme.typography.titleMedium)
                            var qrFailed by remember { mutableStateOf(false) }
                            AsyncImage(model = "https://raw.githubusercontent.com/michat88/Zaneta/main/Icons/qris.png",
                                contentDescription = "QRIS pembayaran AdiXtream", contentScale = ContentScale.Fit,
                                onError = { qrFailed = true }, onSuccess = { qrFailed = false },
                                modifier = Modifier.align(Alignment.CenterHorizontally).sizeIn(maxWidth = 320.dp)
                                    .fillMaxWidth().heightIn(min = 240.dp, max = 320.dp))
                            if (qrFailed) Text("QRIS belum dapat dimuat. Periksa koneksi atau hubungi admin.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("OVO / DANA / GOPAY / SHOPEEPAY / BANK", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    @Composable private fun premiumButtonColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    @Composable private fun Plan(duration: String, price: String, modifier: Modifier = Modifier) {
        Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(duration, style = MaterialTheme.typography.titleMedium)
                Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                content()
            }
        }
    }

    @Composable private fun CodeSection(title: String, subtitle: String, label: String, action: String,
                                       code: String, enabled: Boolean, onChange: (String) -> Unit, onSubmit: () -> Unit,
                                       inputFocus: FocusRequester? = null) {
        Section(title, subtitle) {
            OutlinedTextField(value = code, onValueChange = onChange, enabled = enabled,
                label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().then(if (inputFocus != null) Modifier.focusRequester(inputFocus) else Modifier).focusOutline())
            Button(onClick = onSubmit, enabled = enabled && code.isNotBlank(), colors = premiumButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusOutline()) {
                Text(action, fontWeight = FontWeight.Bold)
            }
        }
    }
}
