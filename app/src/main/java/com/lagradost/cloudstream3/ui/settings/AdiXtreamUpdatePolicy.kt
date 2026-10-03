package com.lagradost.cloudstream3.ui.settings

import java.net.URI

object AdiXtreamUpdatePolicy {
    private val version = Regex("(?:^|[^0-9])(\\d+)\\.(\\d+)\\.(\\d+)(?:$|[^0-9])")
    private fun parts(value: String): List<Long>? = version.find(value)?.groupValues?.drop(1)
        ?.map { it.toLongOrNull() ?: return null }
    fun isNewer(candidate: String, installed: String): Boolean {
        val next = parts(candidate) ?: return false
        val current = parts(installed) ?: return false
        return next.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
    }
    fun allowsDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path == uri.rawPath && uri.normalize().path == uri.path &&
            uri.path.startsWith("/michat88/AdiXtream/releases/download/") &&
            uri.path.removePrefix("/michat88/AdiXtream/releases/download/").split('/').let {
                it.size == 2 && it.all(String::isNotBlank) && it.last().endsWith(".apk", true)
            }
    }.getOrDefault(false)
}
