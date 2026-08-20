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
    // The lexicon artifact and the held-out corpus live at the repo root rather than in test
    // resources: they are build outputs shared with the Android app and the Python tooling,
    // and copying a 950 KB artifact into two places invites the copies drifting apart.
    systemProperty("lexicon.file", rootProject.file("lexicon/assets/he_lexicon.txt.gz").absolutePath)
    systemProperty(
        "heldout.file",
        rootProject.file("lexicon/heldout/hewiki_sample.txt.gz").absolutePath,
    )
    systemProperty("frequency.file", rootProject.file("lexicon/assets/he_freq.bin.gz").absolutePath)
    systemProperty("golden.dir", rootProject.file("lexicon/golden").absolutePath)
    // The correction measurement loads the whole lexicon, builds a trie over it and runs
    // thousands of queries. The default heap is not enough.
    maxHeapSize = "3g"
    // Forward the sweep opt-in into the test JVM. Gradle's -D sets it on the daemon, not on
    // the forked test process, so it has to be passed through explicitly.
    if (project.hasProperty("runWeightSweep")) {
        systemProperty("runWeightSweep", project.property("runWeightSweep").toString())
    }
}
