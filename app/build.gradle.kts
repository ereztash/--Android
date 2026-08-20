plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hebrewime"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hebrewime"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // No test runner is declared yet: there are no instrumented tests, and declaring a
        // runner would make an empty androidTest suite look like a passing one.
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // POSITIVE CONTROL for GATE-NET-2. Builds a REAL apk carrying a real INTERNET
        // permission and real java.net usage, so the apk scanner is proven against an actual
        // defective artifact rather than a fixture that merely resembles one.
        // Its sources live under tools/positive_controls/, which every source gate already
        // excludes, so adding this build type does not weaken GATE-NET-1.
        create("netcontrol") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".netcontrol"
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets {
        // The lexicon artifact ships as an app asset, pointed at directly rather than copied
        // into a generated directory by a Copy task. The copy approach was tried and produced
        // a real Gradle failure: lint's model task reads the generated directory but has no
        // dependency on the task that writes it, so `assembleNetcontrol` and `lintDebug` in
        // one invocation raced and the build failed with an implicit-dependency error. A plain
        // source directory has no such edge.
        //
        // lexicon/assets/ deliberately holds ONLY the artifact -- MANIFEST.json and the 37 MB
        // of upstream sources live in lexicon/ and lexicon/cache/ and must never be packaged.
        getByName("main") {
            assets.srcDir(rootProject.file("lexicon/assets"))
        }

        getByName("netcontrol") {
            manifest.srcFile(
                rootProject.file("tools/positive_controls/apk_network/AndroidManifest.xml")
            )
            kotlin.srcDir(rootProject.file("tools/positive_controls/apk_network/kotlin"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // ObsoleteSdkInt fires on res/mipmap-anydpi-v26 and advises merging it into
        // res/mipmap-anydpi because minSdk is 30. Following that advice was tried and it
        // BREAKS THE BUILD: AGP's resource merger does not accept <adaptive-icon> from an
        // unversioned anydpi folder, and aapt2 then reports "resource mipmap/ic_launcher not
        // found" -- even though `aapt2 compile` handles the same files without complaint.
        // The advice is wrong for adaptive icons; the qualifier stays.
        disable += "ObsoleteSdkInt"

        // Fail the build on anything lint considers an error, rather than only on warnings
        // someone happens to read.
        abortOnError = true
        warningsAsErrors = false

        // XML as well as HTML: CI and the gate scripts parse the XML, and a report only a
        // human can open is a report nobody checks.
        xmlReport = true
        htmlReport = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.tracing)
    implementation(libs.kotlinx.coroutines.android)

    // Compose is used in the settings/onboarding UI ONLY, never inside the IME window.
    // See docs/milestones/M2.md: ComposeView throws inside an InputMethodService until
    // LifecycleOwner, SavedStateRegistryOwner and ViewModelStoreOwner are hand-rolled, and it
    // would add a recomposition layer to the most latency-sensitive path in the app.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
