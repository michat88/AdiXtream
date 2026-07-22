import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val head = headFile.get().asFile

        val hash = try {
            if (head.exists()) {
                // Read the commit hash from .git/HEAD
                val headContent = head.readText().trim()
                if (headContent.startsWith("ref:")) {
                    val refPath = headContent.substring(5) // e.g., refs/heads/main
                    val commitFile = File(head.parentFile, refPath)
                    if (commitFile.exists()) commitFile.readText().trim() else ""
                } else headContent // If it's a detached HEAD (commit hash directly)
            } else "" // If .git/HEAD doesn't exist
        } catch (_: Throwable) {
            "" // Just set to an empty string if any exception occurs
        }.take(7) // Get the short commit hash

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }
}

val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    val gitDir = layout.projectDirectory.dir("../.git")

    headFile.set(gitDir.file("HEAD"))
    headsDir.set(gitDir.dir("refs/heads"))

    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

// ===== AdiXtream: helper untuk resValue commit_hash (dipakai UI AdiXtream) =====
fun getGitCommitHash(): String {
    return try {
        val headFile = file("${project.rootDir}/.git/HEAD")
        if (headFile.exists()) {
            val headContent = headFile.readText().trim()
            if (headContent.startsWith("ref:")) {
                val refPath = headContent.substring(5).trim()
                val commitFile = file("${project.rootDir}/.git/$refPath")
                if (commitFile.exists()) commitFile.readText().trim() else ""
            } else headContent
        } else {
            ""
        }.take(7)
    } catch (_: Throwable) {
        ""
    }
}

// ===== AdiXtream: enkripsi XOR untuk URL repo terintegrasi =====
fun xorEncrypt(input: String, keyString: String): String {
    if (input.isEmpty() || keyString.isEmpty()) return ""

    val key = keyString.toByteArray(Charsets.UTF_8)
    val inputBytes = input.toByteArray(Charsets.UTF_8)
    val outputBytes = ByteArray(inputBytes.size)

    for (i in inputBytes.indices) {
        outputBytes[i] = (inputBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
    }
    return outputBytes.joinToString("") { "%02x".format(it) }
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Looks like google likes to add metadata only they can read https://gitlab.com/IzzyOnDroid/repo/-/work_items/491
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )
        }
    }

    // ===== AdiXtream: signing release sendiri (menggantikan signing prerelease CI upstream) =====
    signingConfigs {
        create("release") {
            val envKeystorePath = System.getenv("KEYSTORE_PATH")
            storeFile = if (envKeystorePath != null) file(envKeystorePath) else file("keystore.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: "161105"
            keyAlias = System.getenv("ALIAS") ?: "adixtream"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "161105"
        }
    }

    // ===== AdiXtream: hanya paketkan locale en/id/in =====
    androidResources {
        localeFilters += listOf("en", "id", "in")
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ===== AdiXtream: identitas aplikasi =====
        applicationId = "com.adixtream.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 89          // AdiXtream: naikkan manual tiap rilis
        versionName = "4.8.2"     // AdiXtream: versi fork, bukan versi upstream

        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        // ===== AdiXtream: resource runtime =====
        resValue("string", "commit_hash", getGitCommitHash())
        resValue("bool", "is_prerelease", "false")
        resValue("string", "app_name", "AdiXtream")
        resValue("color", "blackBoarder", "#FF000000")

        // Reads local.properties
        val localProperties = gradleLocalProperties(rootDir, project.providers)

        // ===== AdiXtream: rahasia repo terenkripsi XOR =====
        val xorSecretKey = (localProperties.getProperty("XOR_SECRET_KEY")
            ?: System.getenv("XOR_SECRET_KEY")
            ?: "DefaultKeyAman").trim()

        val premiumRepo = (localProperties.getProperty("PREMIUM_REPO_ENCODED")
            ?: System.getenv("PREMIUM_REPO_ENCODED")
            ?: "").trim()
        val freeRepo = (localProperties.getProperty("FREE_REPO_ENCODED")
            ?: System.getenv("FREE_REPO_ENCODED")
            ?: "").trim()
        val firebaseUrl = (localProperties.getProperty("FIREBASE_URL_ENCODED")
            ?: System.getenv("FIREBASE_URL_ENCODED")
            ?: "").trim()

        val obfuscatedKeyArray = xorSecretKey.map { it.code + 7 }.joinToString(", ")

        buildConfigField("int[]", "OBFUSCATED_KEY", "new int[]{$obfuscatedKeyArray}")
        buildConfigField("String", "PREMIUM_REPO_ENCODED", "\"${xorEncrypt(premiumRepo, xorSecretKey)}\"")
        buildConfigField("String", "FREE_REPO_ENCODED", "\"${xorEncrypt(freeRepo, xorSecretKey)}\"")
        buildConfigField("String", "FIREBASE_URL_ENCODED", "\"${xorEncrypt(firebaseUrl, xorSecretKey)}\"")

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )
        // ===== AdiXtream: versi aplikasi untuk UI =====
        buildConfigField("String", "APP_VERSION", "\"$versionName\"")

        // ===== AdiXtream: kunci SIMKL di-hardcode (tanpa env CI) =====
        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"db13c9a72e036f717c3a85b13cdeb31fa884c8f4991e43695f7b6477374e35b8\""
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"d8cf8e1b79bae9b2f77f0347d6384a62f1a8d802abdd73d9aa52bf6a848532ba\""
        )
        // Dipertahankan dari upstream: kode sumber baru bisa saja mereferensikan
        // BuildConfig.MAL_KEY / ANILIST_KEY. Nilai tetap dari env/local.properties.
        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\""
        )
        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // ===== AdiXtream: tanda tangani release dengan keystore sendiri =====
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ===== AdiXtream: hanya flavor stable (flavor prerelease upstream dihapus) =====
    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
            resValue("bool", "is_prerelease", "false")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        // Use Java 17 toolchain even if a higher JDK runs the build.
        // We still use Java 8 for now which higher JDKs have deprecated.
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
        // ===== AdiXtream: locale difilter, jadi peringatan terjemahan dimatikan =====
        disable.add("MissingTranslation")
    }

    buildFeatures {
        buildConfig = true
        // ===== AdiXtream: wajib untuk resValue di atas =====
        resValues = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Enables legacy JNI packaging to reduce APK size (similar to builds before minSdk 23).
            // Note: This may increase app startup time slightly.
            useLegacyPackaging = true
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json) // JSON Parser

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime-db for filler
    implementation(libs.anime.db)

    // PlayBack
    implementation(libs.colorpicker) // Subtitle Color Picker
    implementation(libs.newpipeextractor) // For Trailers
    implementation(libs.juniversalchardet) // Subtitle Decoding

    // UI Stuff
    implementation(libs.shimmer) // Shimmering Effect (Loading Skeleton)
    implementation(libs.palette.ktx) // Palette for Images -> Colors
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels) // Gestures
    implementation(libs.biometric) // Fingerprint Authentication
    implementation(libs.previewseekbar.media3) // SeekBar Preview
    implementation(libs.qrcode.kotlin) // QR Code for PIN Auth on TV

    // Extensions & Other Libs
    implementation(libs.jsoup) // HTML Parser
    implementation(libs.ksoup) // HTML Parser
    implementation(libs.rhino) // Run JavaScript
    implementation(libs.safefile) // To Prevent the URI File Fu*kery
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio) // NIO Flavor Needed for NewPipeExtractor
    implementation(libs.conscrypt.android) // To Fix SSL Fu*kery on Android 9
    implementation(libs.jackson.module.kotlin) // JSON Parser
    implementation(libs.zipline)

    // ===== AdiXtream: penyimpanan terenkripsi (repo premium) =====
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Temp/deprecated; will be removed once extensions have time to migrate from using it
    implementation("com.google.code.gson:gson:2.11.0")
    // Deprecated; will be removed once extensions have time to migrate from using it
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp) // HTTP Lib

    implementation(project(":library"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories) // Full Sources
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        // ===== AdiXtream: flavor prerelease dihapus, jadi jar diambil dari stableDebug =====
        "build/intermediates/compile_app_classes_jar/stableDebug/bundleStableDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    // Remove the version
    rename("library-jvm.*.jar", "library-jvm.jar")
}

// Merge the app classes and the library classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        // ===== AdiXtream. CATATAN: hanya valid di Kotlin >= 2.2.
        // Jika build error "Unknown option -Xannotation-default-target", hapus baris ini. =====
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
            // ===== AdiXtream =====
            "kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
            // ===== AdiXtream: baris suppress upstream dihapus karena mengacu
            // ke variant prereleaseDebug yang tidak ada lagi di fork ini =====
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"
            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")
                remoteUrl("https://github.com/michat88/AdiXtream/tree/master")
                remoteLineSuffix = "#L"
            }
        }
    }
}
