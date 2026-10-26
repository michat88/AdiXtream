package com.lagradost.cloudstream3.ui.settings.extensions

/** Exact configured identities only; blank configuration must fail closed. */
internal class AdiXtreamRepositoryPolicy(
    private val freeRepository: String,
    private val premiumRepository: String,
) {
    fun allowsCatalog(repository: String): Boolean = repository.isNotBlank() &&
        ((freeRepository.isNotBlank() && repository == freeRepository) ||
            (premiumRepository.isNotBlank() && repository == premiumRepository))

    fun allowsUse(repository: String, isPremium: Boolean): Boolean =
        allowsCatalog(repository) && (repository != premiumRepository || isPremium)
}
