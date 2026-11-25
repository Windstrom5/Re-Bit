// Android Library module — unlike core-model/core-analysis, this genuinely
// needs the Android platform (android.hardware.usb.*, BroadcastReceiver,
// Context), confirmed by grepping every import in usb/ before moving
// anything: android.app/content/hardware.usb/os, kotlinx.coroutines.flow,
// and com.windstrom5.rebit.domain.model (→ core-model). Nothing from
// domain.repository, domain.usecase, playback, data, or presentation.
//
// No AndroidManifest.xml needed: nothing here declares a manifest component
// (the USB BroadcastReceiver is registered dynamically via registerReceiver()
// in code, not declared in XML) or requires a manifest-merged permission
// beyond what app/src/main/AndroidManifest.xml already declares — the
// android.hardware.usb.host <uses-feature> and the USB-attach intent-filter
// are app-level concerns (what the *app* advertises/launches on), not
// something this module needs to restate.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.windstrom5.rebit.usb"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // "USB Direct — Native (C++)" driver profile — see
        // src/main/cpp/usb_native_streamer.cpp for what this is and its
        // verification status (compiled clean against the real kernel
        // usbdevfs headers + isolated unit tests for its core logic, but
        // never built with the actual NDK or run against real hardware).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            stl = "c++_shared"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // 16 KB page-size support. NDK r27 (the AGP default) needs this switch; r28+ does it by default.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
