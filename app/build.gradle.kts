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

    dependenciesInfo {
        includeInApk = false
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

    // ===== AdiXtream: signing release sendiri =====
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
        versionCode = 90          // AdiXtream: naikkan manual tiap rilis
        versionName = "4.8.3"     // AdiXtream: versi fork, bukan versi upstream

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

        // ===== AdiXtream: kunci SIMKL di-hardcode =====
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
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
        disable.add("MissingTranslation")
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        viewBinding = true
    }

    packaging {
        jniLibs {
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
    implementation(libs.kotlinx.serialization.json)

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
    implementation(libs.colorpicker)
    implementation(libs.newpipeextractor)
    implementation(libs.juniversalchardet)

    // UI Stuff
    implementation(libs.shimmer)
    implementation(libs.palette.ktx)
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels)
    implementation(libs.biometric)
    implementation(libs.previewseekbar.media3)
    implementation(libs.qrcode.kotlin)

    // Extensions & Other Libs
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.rhino)
    implementation(libs.safefile)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.conscrypt.android)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.zipline)

    // ===== AdiXtream: penyimpanan terenkripsi (repo premium) =====
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Temp/deprecated
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp)

    implementation(project(":library"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories)
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/stableDebug/bundleStableDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    rename("library-jvm.*.jar", "library-jvm.jar")
}

tasks.register<Jar>("makeJar") {
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
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
            "kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
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
