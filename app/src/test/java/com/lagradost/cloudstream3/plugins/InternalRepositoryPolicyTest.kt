package com.lagradost.cloudstream3.plugins

import org.junit.Assert.*
import org.junit.Test

class InternalRepositoryPolicyTest {
    private val free = "https://example.test/free.json"
    private val premium = "https://example.test/premium.json"
    @Test fun freeUsersCannotAddPremiumOrExternalRepositories() {
        assertTrue(InternalRepositoryPolicy.allows(free, free, premium, false))
        listOf(premium, "$free/extra", "https://external.test/repo.json", "", " $free").forEach {
            assertFalse(InternalRepositoryPolicy.allows(it, free, premium, false))
        }
    }
    @Test fun premiumUsersAreStillRestrictedToConfiguredUrls() {
        listOf(free, premium).forEach { assertTrue(InternalRepositoryPolicy.allows(it, free, premium, true)) }
        assertFalse(InternalRepositoryPolicy.allows("$premium?external=true", free, premium, true))
        assertFalse(InternalRepositoryPolicy.allows("", "", "", true))
    }
}
