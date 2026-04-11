# =========================================================
# --- BAWAAN ANDROID TEMPLATE ---
# =========================================================
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile


# =========================================================
# --- ATURAN KHUSUS ADIXTREAM & CLOUDSTREAM (ANTI-CRASH) ---
# =========================================================

# 1. AMANKAN KODE KEAMANAN UTAMA KITA 
# (Wajib agar gembok XOR & logika Premium tidak rusak saat diakses)
-keep class com.lagradost.cloudstream3.PremiumManager { *; }
-keep class com.lagradost.cloudstream3.utils.RepoProtector { *; }

# 2. AMANKAN STRUKTUR DATA JSON & API
# (Mencegah error saat parsing data Firebase dan data Film dari provider)
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.** *;
    @com.google.gson.annotations.** *;
}
-keep class com.lagradost.cloudstream3.syncproviders.** { *; }
-keep class com.lagradost.cloudstream3.responses.** { *; }

# 3. AMANKAN MESIN SCRAPING & JAVASCRIPT
# (Cloudstream butuh ini agar fitur pemutar video/ekstraktor link tetap jalan)
-keep class org.mozilla.javascript.** { *; }
-keep class com.lagradost.cloudstream3.extractors.** { *; }

# 4. ABAIKAN PERINGATAN LIBRARY & MISSING CLASSES (FIX R8 BUILD ERROR)
# (Memastikan proses "Build APK" sukses dan tidak macet di tengah jalan)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# =========================================================
# 5. AMANKAN SISTEM PLUGIN & UPDATER (FIX JACKSON/JSON PARSE ERROR)
# =========================================================

# Wajib agar Jackson tidak kehilangan informasi tipe data Generic (TypeReference error)
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions

# Wajib agar Jackson tahu cara membuat (construct) objek untuk Plugin & Updater
-keep class com.lagradost.cloudstream3.plugins.** { *; }
-keep class com.lagradost.cloudstream3.models.** { *; }
-keep class com.lagradost.cloudstream3.utils.InAppUpdater.** { *; }
-keep class com.lagradost.cloudstream3.metaproviders.** { *; }

# Amankan spesifik TypeReference bawaan Jackson agar tidak diubah namanya
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }
