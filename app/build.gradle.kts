import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing, read from an UNTRACKED keystore.properties at the repository root.
 *
 * The file is in .gitignore alongside *.jks and *.keystore. When it is absent -- which is the
 * case in CI and in any checkout that has not been handed the secret -- the release build still
 * assembles, unsigned. That keeps the whole release path buildable and testable without ever
 * putting a key in the repository, and makes "is the release configuration correct" a question
 * that can be answered separately from "do we have the signing key".
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigningSecrets = keystoreProperties.getProperty("storeFile") != null

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

    signingConfigs {
        if (hasSigningSecrets) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // Signature schemes, stated rather than left to AGP's defaults.
                //
                // v1 (JAR signing) is off: it only matters below API 24 and minSdk here is 31,
                // and a v1 signature on a modern APK is dead weight in every entry's manifest.
                //
                // v2 is the floor. v3 is what makes KEY ROTATION possible: without it, an APK
                // distributed outside Play is bound to this key forever, and if the key is
                // ever compromised or lost there is no upgrade path for anyone who installed
                // it -- they have to uninstall and lose their personal dictionary, which on
                // this app is encrypted under a Keystore key that dies with the package.
                //
                // Verified by generating a throwaway key and running apksigner: before this
                // block the release APK came back "v2: true, v3: false", which is AGP's
                // default and not a decision anyone had made.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // v4 produces a side-car file for incremental `adb install`. It is not part of
                // a Play upload and nothing here consumes it.
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (hasSigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
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

        // Compress the DEX in DEBUG builds only.
        //
        // AGP stores debug DEX uncompressed so the platform can mmap it and start faster. That
        // is the right default for a build you install over ADB, and the wrong one for a build
        // somebody downloads to a phone over mobile data: unminified Compose puts 28.6 MB of
        // DEX in the debug APK, and stored uncompressed that is a 31.4 MB download.
        //
        // Scoped to debug on purpose. The release variant keeps AGP's default so its packaged
        // size stays the number GATE-SIZE-1 baselines and docs/RELEASE_READINESS.md quotes --
        // shrinking the shipped artifact as a side effect of making a test build easier to
        // download would quietly invalidate both.
        dex.useLegacyPackaging = false
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // See the packaging block: compressed DEX for the downloadable debug build, AGP's
        // uncompressed default everywhere else.
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    // ExploreByTouchHelper: the only way to make canvas-drawn keys visible to TalkBack.
    implementation(libs.androidx.customview)
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
