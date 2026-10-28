package com.lagradost.cloudstream3.plugins

/** Publish only initialized instances; also clean providers registered before an initializer failed. */
internal fun initializePluginTransaction(
    allowed: () -> Boolean,
    initialize: () -> Unit,
    publish: () -> Unit,
    rollback: () -> Unit,
): Boolean {
    if (!allowed()) return false
    try {
        initialize()
        if (!allowed()) {
            rollback()
            return false
        }
        publish()
        return true
    } catch (failure: Throwable) {
        rollback()
        throw failure
    }
}
