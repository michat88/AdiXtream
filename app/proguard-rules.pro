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
-keep class com.lagradost.cloudstream3.PremiumManager { *; }
-keep class com.lagradost.cloudstream3.utils.RepoProtector { *; }

# 2. AMANKAN STRUKTUR DATA JSON & API
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.** *;
    @com.google.gson.annotations.** *;
}
-keep class com.lagradost.cloudstream3.syncproviders.** { *; }
-keep class com.lagradost.cloudstream3.responses.** { *; }

# 3. AMANKAN MESIN SCRAPING & JAVASCRIPT
-keep class org.mozilla.javascript.** { *; }
-keep class com.lagradost.cloudstream3.extractors.** { *; }

# 4. ABAIKAN PERINGATAN LIBRARY & MISSING CLASSES (FIX R8 BUILD ERROR)
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
