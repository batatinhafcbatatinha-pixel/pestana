plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.batatinha.pestana"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.batatinha.pestana"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.1"
        // Only Portuguese and English strings; drops other locales from libraries.
        androidResources.localeFilters += listOf("pt", "en")
    }

    // One APK per architecture, so a phone downloads only its own library.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // CI signs with the key in its secrets; a local build signs with the debug key.
    val keystore = System.getenv("ANDROID_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("ci") {
                storeFile = keystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += listOf("META-INF/**", "kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Builds the Rust core (../../core) into jniLibs with cargo-ndk.
// Skip with -PskipRust when the libraries are already built.
val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libpestana.so for every ABI with cargo-ndk"
    workingDir = rootProject.file("../core")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "x86_64",
        "-P", "26",
        "-o", project.file("src/main/jniLibs").absolutePath,
        "build", "--release",
    )
}
if (!project.hasProperty("skipRust")) {
    tasks.named("preBuild") { dependsOn(cargoBuild) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
