# =========================================================
# --- BAWAAN ANDROID TEMPLATE ---
# =========================================================
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

# =========================================================
# --- ATURAN FINAL ADIXTREAM (ANTI-CRASH & PLUGIN FIX) ---
# =========================================================

# 1. JURUS NINJA: AMANKAN SELURUH API CLOUDSTREAM UNTUK PLUGIN, 
# TETAPI HANCURKAN/ACAK NAMA PREMIUM MANAGER & REPO PROTECTOR!
# (Tanda seru '!' artinya mengecualikan file tersebut agar tetap diacak oleh R8)
-keep class !com.lagradost.cloudstream3.PremiumManager, !com.lagradost.cloudstream3.utils.RepoProtector, com.lagradost.cloudstream3.** { *; }

# 2. AMANKAN LIBRARY EKSTERNAL YANG SERING DIPAKAI OLEH PLUGIN (.cs3)
-keep class org.jsoup.** { *; }
-keep class okhttp3.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.javascript.** { *; }

# 3. AMANKAN JACKSON & ANNOTASI JSON (Fix Error InAppUpdater & Campaign Popup)
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.** *;
    @com.google.gson.annotations.** *;
}
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }

# 4. ABAIKAN PERINGATAN BUILD ERROR AGAR APK SUKSES DIBUAT
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
