plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is a PURE JVM module. It must never depend on the Android SDK.
//
// Rationale: everything decidable about this keyboard -- lexicon lookup, prefix stripping,
// tokenization, candidate generation and ranking, and the sensitive-field mask logic -- is
// pure data transformation. Keeping it off Android means it is unit-testable on a plain JVM,
// which is what lets the accuracy and privacy gates run in CI on every push instead of
// needing a device. The Android modules hold only what genuinely needs a device.

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
