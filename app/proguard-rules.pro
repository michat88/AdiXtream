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

# 4. ABAIKAN PERINGATAN LIBRARY MENGGANGGU
# (Memastikan proses "Build APK" sukses dan tidak macet di tengah jalan karena warning)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn com.fasterxml.jackson.databind.**
