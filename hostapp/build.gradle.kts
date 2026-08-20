plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * A deliberately trivial app with one EditText.
 *
 * It exists only so the macrobenchmark has something to drive. Macrobenchmark's built-in
 * metrics are bound to `targetPackageName` and never see the IME process, so the only way to
 * measure the keyboard is to drive a host app with UiAutomator and read the IME's own
 * `androidx.tracing` sections via `TraceSectionMetric(targetPackageOnly = false)`.
 *
 * Nothing here is shipped. It is a test fixture that happens to be an app.
 */
android {
    namespace = "com.hebrewime.hostapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hebrewime.hostapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        // Macrobenchmark requires a non-debuggable, profileable build to measure.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
}
