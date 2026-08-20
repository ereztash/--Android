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
}

kotlin {
    jvmToolchain(17)
}

/**
 * The lexicon artifact is a build output shared by the app, the Python tooling and the :core
 * tests. Copying it into app/src/main/assets would put a second 950 KB copy in git and invite
 * the two drifting apart, so it is staged into a generated asset directory instead.
 */
val stageLexiconAsset = tasks.register<Copy>("stageLexiconAsset") {
    description = "Stages lexicon/he_lexicon.txt.gz as an app asset."
    from(rootProject.file("lexicon/he_lexicon.txt.gz"))
    into(layout.buildDirectory.dir("generated/lexiconAssets"))
}

android.sourceSets.getByName("main").assets
    .srcDir(layout.buildDirectory.dir("generated/lexiconAssets"))

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(stageLexiconAsset) }

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
