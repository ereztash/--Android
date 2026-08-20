plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

/**
 * The ONLY route to a real keystroke-latency number for this keyboard.
 *
 * Macrobenchmark's StartupTimingMetric and FrameTimingMetric are bound to `targetPackageName`
 * and will never see the IME process -- an IME runs in its own process, not the app's. So the
 * measurement has to come from `TraceSectionMetric(..., targetPackageOnly = false)` reading the
 * IME's own `androidx.tracing` sections while UiAutomator drives a host app.
 *
 * Without this module the sub-50 ms target is unfalsifiable, and an unfalsifiable target is not
 * a gate. It is committed and assembles; running it needs a physical device.
 */
android {
    namespace = "com.hebrewime.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":hostapp"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit4)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
