// build.gradle.kts (ROOT PROJECT) - FIX FINAL

plugins {
    // Plugin Aplikasi (Wajib untuk App)
    alias(libs.plugins.android.application) apply false

    // Plugin Library (Wajib untuk modul :library)
    alias(libs.plugins.android.library) apply false

    // --- BAGIAN YANG DIHAPUS ---
    // Saya menghapus 'android.lint' dan 'android.multiplatform.library' 
    // karena belum ada di libs.versions.toml kamu dan menyebabkan error.
    // ---------------------------

    // Plugin pendukung lainnya (Tetap dipertahankan)
    alias(libs.plugins.buildkonfig) apply false 
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    // Konfigurasi tasks (Aman dipertahankan)
    tasks.withType<AbstractTestTask>().configureEach {
        failOnNoDiscoveredTests = false
    }
}
