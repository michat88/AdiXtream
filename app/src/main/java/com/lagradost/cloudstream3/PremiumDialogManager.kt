package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Intent

/** Preserve all existing premium access callers, with activation on its own page. */
object PremiumDialogManager {
    fun showPremiumUnlockDialog(activity: Activity) {
        activity.startActivity(Intent(activity, ActivationSubscriptionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
