package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.PremiumManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SubscriptionTier { ACTIVE, FREE, EXPIRED }
data class SubscriptionState(
    val deviceId: String = "", val tier: SubscriptionTier = SubscriptionTier.FREE,
    val expiresAt: Long = 0, val loading: Boolean = false,
    val message: String? = null, val success: Boolean = false,
)
interface SubscriptionService {
    fun status(): SubscriptionState
    fun activate(code: String, promo: Boolean, result: (Boolean, String) -> Unit)
}

/** Reuses the original licensing APIs and stores. No second licensing system. */
class ExistingPremiumService(context: Context) : SubscriptionService {
    private val context = context.applicationContext
    override fun status(): SubscriptionState {
        val status = PremiumManager.getLocalSubscription(context)
        val now = System.currentTimeMillis()
        return SubscriptionState(PremiumManager.getDeviceId(context), when {
            status.isPremium && status.expiresAt > now -> SubscriptionTier.ACTIVE
            status.expiresAt in 1..now -> SubscriptionTier.EXPIRED
            else -> SubscriptionTier.FREE
        }, status.expiresAt)
    }
    override fun activate(code: String, promo: Boolean, result: (Boolean, String) -> Unit) {
        val deviceId = PremiumManager.getDeviceId(context)
        if (promo) PremiumManager.activatePromoWithCode(context, code, deviceId, false, result)
        else PremiumManager.activatePremiumWithCode(context, code, deviceId, result)
    }
}

class SubscriptionViewModel(private val service: SubscriptionService) : ViewModel() {
    private val mutableState = MutableStateFlow(service.status())
    val state = mutableState.asStateFlow()
    private var timeout: Job? = null
    private var operation = 0
    fun refresh() {
        val local = service.status()
        mutableState.update { local.copy(loading = it.loading, message = it.message, success = it.success) }
    }
    fun submit(code: String, promo: Boolean) {
        if (state.value.loading) return
        if (code.isBlank()) {
            mutableState.update { it.copy(message = "Kode tidak boleh kosong.", success = false) }
            return
        }
        val request = ++operation
        mutableState.update { it.copy(loading = true, message = null, success = false) }
        timeout = viewModelScope.launch {
            delay(60_000)
            if (request == operation) mutableState.update {
                it.copy(loading = false, message = "Belum ada jawaban server. Periksa status sebelum mencoba kembali.")
            }
        }
        service.activate(code.trim(), promo) { success, message ->
            viewModelScope.launch {
                if (request != operation) return@launch
                timeout?.cancel()
                mutableState.value = service.status().copy(message = message, success = success)
            }
        }
    }
}
