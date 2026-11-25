import java.util.Properties

// Release signing: credentials live in keystore.properties at the project root, which is
// git-ignored. See RELEASING.md. Never put passwords in this file.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// CPU architectures packed into RELEASE builds. Every extra one adds a full copy of the native libraries
// (TensorFlow's Select-ops library alone is ~100 MB per architecture), which is how an APK reached 400 MB.
// arm64-v8a covers practically every phone since ~2017. For old 32-bit-only phones build with:
//   gradlew assembleFullRelease -PrebitAbis=arm64-v8a,armeabi-v7a
val releaseAbis: List<String> = (project.findProperty("rebitAbis") as String? ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.windstrom5.rebit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.windstrom5.rebit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // First public beta. Bump versionCode on every release; keep "-beta.N" until 1.0.0 final.
        versionName = "1.0.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(requireNotNull(keystoreProps.getProperty("storeFile")) { "storeFile missing in keystore.properties" })
                storePassword = requireNotNull(keystoreProps.getProperty("storePassword")) { "storePassword missing in keystore.properties" }
                keyAlias = requireNotNull(keystoreProps.getProperty("keyAlias")) { "keyAlias missing in keystore.properties" }
                keyPassword = requireNotNull(keystoreProps.getProperty("keyPassword")) { "keyPassword missing in keystore.properties" }
            }
        }
    }

    // Two editions of the same app:
    //  full: includes the on-device neural vocal separator (Spleeter model + TensorFlow Select ops).
    //  lite: leaves those out (~100 MB lighter). Vocal/karaoke separation still works through the built-in
    //        signal-processing path; the app falls back to it when the neural model isn't present.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") {
            dimension = "edition"
            versionNameSuffix = "-lite"
        }
    }

    buildTypes {
        release {
            ndk { abiFilters += releaseAbis }
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                project.logger.warn(
                    "WARNING: keystore.properties not found, so the release APK is signed with the DEBUG key. " +
                        "Fine for testing on your own phone; do NOT publish it. See RELEASING.md."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // NOTE: as of Kotlin 2.0+, the Compose compiler ships as part of the Kotlin
    // compiler itself and is configured via the `org.jetbrains.kotlin.plugin.compose`
    // Gradle plugin (applied above, pinned to the same version as Kotlin) — the old
    // `composeOptions { kotlinCompilerExtensionVersion = "..." }` block is obsolete
    // and intentionally removed here.

    packaging {
        // Compress native libraries inside the APK. The default (uncompressed, memory-mapped) makes the file
        // as large as the libraries themselves; compressing cuts the download/APK size a lot, at the cost of
        // the system extracting them to disk on install.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0") // background release/news checks
    // ── Extracted modules ──────────────────────────────────────────────
    // See settings.gradle.kts for the full planned module graph and why
    // these two came first (zero Android deps, zero circular imports,
    // verified before moving anything).
    implementation(project(":core-model"))
    implementation(project(":core-analysis"))
    implementation(project(":core-usb"))

    // Core / Kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // FLAC software-decode fallback (org.kc7bfi.jflac) is vendored directly under
    // app/src/main/java/org/kc7bfi/jflac — see that package's note for why. No
    // Maven dependency needed; it compiles as part of this module.
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.palette.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room (local persistence for library, DAC profiles, sessions, benchmarks, experiments)
    // Uses KSP rather than kapt for annotation processing — faster and far less prone to
    // the Gradle-internal-API breakage that kapt has historically hit across Gradle upgrades.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Koin (dependency injection)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // TensorFlow Lite AI Suite (On-Device Neural Engine)
    implementation(libs.tensorflow.lite)
    // Required for SpleeterNeuralSeparator's model — it computes its own
    // STFT/inverse-STFT internally using complex-number ops that core TFLite
    // has no kernels for at all, so Select TF ops (the "Flex" delegate) is
    // load-bearing here, not optional. No code needs to reference this
    // directly — having it on the classpath is what makes the Flex delegate
    // auto-register for graphs that need it. This is also the main cost of
    // that model: this single dependency adds roughly 20-40MB to the app.
    "fullImplementation"(libs.tensorflow.lite.select.tf.ops)
    implementation(libs.tensorflow.lite.support)

    // Japanese morphological analysis for LyricsRomanizer — real dictionary-
    // based reading disambiguation (kanji readings are context-dependent;
    // e.g. 大 is "oo" in 大きい but "dai" in 大丈夫) instead of the previous
    // one-reading-per-character lookup table. Pure Java/Kotlin, no NDK.
    // NOTE: this dependency's resolution against Maven Central has not been
    // verified in the environment that wrote this — no network access to
    // Maven Central was available to confirm the artifact fetches cleanly.
    // If Gradle sync fails on this line, check for a newer kuromoji-ipadic
    // version on Maven Central and adjust.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // Unit / instrumentation tests
    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM, so unit tests that parse JSON need the real one.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
