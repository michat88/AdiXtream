package com.lagradost.cloudstream3.ui.settings

import org.junit.Assert.*
import org.junit.Test

class AdiXtreamUpdatePolicyTest {
    @Test fun compareRealReleaseTagsAndAssetNames() {
        assertFalse(AdiXtreamUpdatePolicy.isNewer("AdiXtream-4.8.3.apk", "4.8.3"))
        assertFalse(AdiXtreamUpdatePolicy.isNewer("v4.8.2", "4.8.3"))
        assertTrue(AdiXtreamUpdatePolicy.isNewer("v4.8.4", "4.8.3"))
        assertTrue(AdiXtreamUpdatePolicy.isNewer("AdiXtream-4.10.0.apk", "4.9.9"))
        assertFalse(AdiXtreamUpdatePolicy.isNewer("pre-release", "4.8.3"))
        assertFalse(AdiXtreamUpdatePolicy.isNewer("999999999999999999999.1.0", "4.8.3"))
    }
    @Test fun rejectUpstreamImpersonationAndPathTricks() {
        val url = "https://github.com/michat88/AdiXtream/releases/download/v4.8.4/AdiXtream-4.8.4.apk"
        assertTrue(AdiXtreamUpdatePolicy.allowsDownload(url))
        listOf(url.replace("michat88/AdiXtream", "recloudstream/cloudstream"),
            url.replace("github.com", "github.com.evil.test"), url.replace("https:", "http:"),
            url.replace("github.com", "attacker@github.com"), url.replace("v4.8.4/", "../"),
            url.replace("v4.8.4/", "%2e%2e/"), url.replace(".apk", ".zip"), "$url?redirect=evil")
            .forEach { assertFalse(AdiXtreamUpdatePolicy.allowsDownload(it)) }
    }
}
