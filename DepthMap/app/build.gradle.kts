plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.depthmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.depthmap"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            ndk { debugSymbolLevel = "none" }
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            ndk { debugSymbolLevel = "none" }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }

    // Workaround: NDK llvm-strip is x86-64 and won't run on aarch64
    // Copy native libs from merged to stripped output
    tasks.register<Copy>("copyMergedToStrippedDebug") {
        dependsOn("mergeDebugNativeLibs")
        from("$buildDir/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib")
        into("$buildDir/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib")
    }
    tasks.register<Copy>("copyMergedToStrippedRelease") {
        dependsOn("mergeReleaseNativeLibs")
        from("$buildDir/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib")
        into("$buildDir/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib")
    }
    tasks.matching { it.name.contains("strip") && it.name.contains("DebugSymbols") }.configureEach {
        enabled = false
    }
    tasks.matching { it.name == "packageDebug" }.configureEach {
        dependsOn("copyMergedToStrippedDebug")
    }
    tasks.matching { it.name == "packageRelease" }.configureEach {
        dependsOn("copyMergedToStrippedRelease")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.onnxruntime.android)
    implementation(libs.coroutines.android)
    implementation(libs.documentfile)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
