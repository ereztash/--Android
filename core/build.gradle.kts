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

// A1 tool 1 of 4. Runs the SHIPPED detector over the held-out conversational slice and dumps
// every position it speaks at, plus the two control pools, for scripts/build_label_batch.py.
//
// A JavaExec on the test runtime classpath rather than a test: it writes a file into the
// working tree, which is not what a test does, and it must be run deliberately rather than
// swept up by `./gradlew test`.
tasks.register<JavaExec>("harvestLabelCandidates") {
    group = "verification"
    description = "Harvest real-word-error candidates for human labelling (docs/LABELING_PROTOCOL.md)"
    mainClass.set("com.hebrewime.core.labeling.HarvestCandidates")
    classpath = sourceSets["test"].runtimeClasspath
    args(rootProject.file("labeling/candidates.jsonl").absolutePath)
    for ((key, file) in listOf(
        "lexicon.file" to "lexicon/assets/he_lexicon.txt.gz",
        "frequency.file" to "lexicon/assets/he_freq.bin.gz",
        "bigram.file" to "lexicon/assets/he_bigrams.bin.gz",
        "skipgram.file" to "lexicon/experimental/he_skipgrams.bin.gz",
        "subtitle.heldout.file" to "lexicon/cache/subtitle-corpus-heldout.txt.gz",
    )) systemProperty(key, rootProject.file(file).absolutePath)
    maxHeapSize = "3g"
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
    systemProperty("bigram.file", rootProject.file("lexicon/assets/he_bigrams.bin.gz").absolutePath)
    // WITHDRAWN from the APK; kept out of lexicon/assets so it can never be packaged,
    // and kept on disk so the S1 sweep and verdict stay reproducible.
    systemProperty("skipgram.file", rootProject.file("lexicon/experimental/he_skipgrams.bin.gz").absolutePath)
    systemProperty("abbreviation.file", rootProject.file("lexicon/assets/he_abbreviations.txt.gz").absolutePath)
    // The pre-blend table, kept OUT of lexicon/assets so it is never packaged. It exists only
    // so the register change can be measured as a before/after in one run rather than asserted.
    systemProperty("bigram.wikionly.file", rootProject.file("lexicon/experimental/he_bigrams_wikionly.bin.gz").absolutePath)

    // Assets are read through absolute paths, so Gradle does not see them as task inputs and
    // will happily serve a CACHED PASS after the shipped table changes underneath it. Declaring
    // them makes a data change invalidate the tests that measure that data.
    inputs.files(
        rootProject.file("lexicon/assets"),
        rootProject.file("lexicon/eval"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("eval.dir", rootProject.file("lexicon/eval").absolutePath)
    // The confusion margin is swept on the dev slice and reported on the test slice, which
    // share no sentence -- see scripts/slice_eval_corpus.py, which proves it before writing.
    systemProperty("runConfusionSweep", project.findProperty("runConfusionSweep")?.toString() ?: "")
    // Adaptive learning: interpolation weight and session floor are swept on learning_dev and
    // reported on learning_test, which share no sentence with it or with each other.
    systemProperty("runLearningSweep", project.findProperty("runLearningSweep")?.toString() ?: "")
    // O1, the offer policy: thresholds are swept on the EVEN half of the committed eval slice
    // and reported on the ODD half, which the test asserts are disjoint rather than inferring
    // it from the rule that split them. Opt-in, because it is a sweep and not a regression.
    systemProperty("runOfferSweep", project.findProperty("runOfferSweep")?.toString() ?: "")

    // Warm-up under a deliberately small heap. An IME is one of the most heap-constrained
    // processes on Android and this project has no device to measure on, so the substitute is
    // to shrink the JVM until the allocation actually fails and report where that line sits.
    systemProperty("warmUpMode", project.findProperty("warmUpMode")?.toString() ?: "")
    // The correction measurement loads the whole lexicon, builds a trie over it and runs
    // thousands of queries. The default heap is not enough.
    //
    // -PtestHeap overrides it, and must come AFTER this line or it is silently ignored --
    // which it was on the first attempt, producing a table of "survives 24 MB" results from a
    // JVM that actually had 3 GB.
    maxHeapSize = "3g"
    project.findProperty("testHeap")?.let { maxHeapSize = it.toString() }
    // Forward the sweep opt-in into the test JVM. Gradle's -D sets it on the daemon, not on
    // the forked test process, so it has to be passed through explicitly.
    if (project.hasProperty("runWeightSweep")) {
        systemProperty("runWeightSweep", project.property("runWeightSweep").toString())
    }
}

