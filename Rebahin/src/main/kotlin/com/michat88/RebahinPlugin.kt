package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RebahinPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Rebahin ke dalam Cloudstream
        registerMainAPI(RebahinProvider())
    }
}
