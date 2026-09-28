package com.lagradost.cloudstream3.plugins

/** Exact configured URLs only, never repository names, aliases or URL prefixes. */
object InternalRepositoryPolicy {
    fun allows(url: String, free: String, premium: String, premiumActive: Boolean): Boolean =
        url.isNotBlank() && ((free.isNotBlank() && url == free) ||
            (premiumActive && premium.isNotBlank() && url == premium))
}
