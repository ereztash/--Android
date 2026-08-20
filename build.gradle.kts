import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Feeds GATE-NET-1's dependency detector.
 *
 * Writes every externally-resolved module coordinate, across every project and every
 * compile/runtime classpath, to a flat file. `scripts/check_no_network.py` reads that file and
 * uses its line count as the detector's denominator -- so if this task does not run, the gate
 * reports NOT-MEASURED rather than pretending the dependency graph was clean.
 *
 * Uses `resolutionResult`, which resolves the graph without downloading artifacts.
 */
tasks.register("dependencyAudit") {
    group = "verification"
    description = "Writes all resolved external dependency coordinates for GATE-NET-1."

    val outFile = layout.buildDirectory.file("reports/dependency-audit/coordinates.txt")
    outputs.file(outFile)
    outputs.upToDateWhen { false }

    val projects = allprojects

    doLast {
        // Each row is `scope<TAB>project<TAB>configuration<TAB>coordinate`.
        //
        // The scope column exists because "does this dependency have network capability" is
        // only a shipping question. `:benchmark` pulls in com.squareup.wire transitively --
        // androidx.benchmark uses it to parse Perfetto traces -- and `:hostapp` is a test
        // fixture. Neither is in the shipped APK, so flagging them would either produce a
        // permanently red gate or, worse, invite someone to delete the blocklist entry that
        // matters. The gate fails on SHIPPING scope and merely reports the rest.
        val rows = sortedSetOf<String>()
        var configurationsExamined = 0
        var configurationsFailed = 0

        fun scopeOf(projectPath: String, configuration: String): String {
            val isTestConfig = configuration.contains("Test", ignoreCase = true) ||
                configuration.startsWith("netcontrol")
            val isShippingProject = projectPath == ":app" || projectPath == ":core"
            return if (isShippingProject && !isTestConfig) "SHIPPING" else "TEST"
        }

        projects.forEach { p ->
            p.configurations
                .filter { it.isCanBeResolved }
                .filter {
                    it.name.endsWith("CompileClasspath") ||
                        it.name.endsWith("RuntimeClasspath")
                }
                .forEach { cfg ->
                    configurationsExamined++
                    val scope = scopeOf(p.path, cfg.name)
                    runCatching {
                        cfg.incoming.resolutionResult.allComponents.forEach { component ->
                            val id = component.id
                            if (id is ModuleComponentIdentifier) {
                                rows.add(
                                    "$scope\t${p.path}\t${cfg.name}\t" +
                                        "${id.group}:${id.module}:${id.version}"
                                )
                            }
                        }
                    }.onFailure { configurationsFailed++ }
                }
        }

        val shipping = rows.count { it.startsWith("SHIPPING") }
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("# GATE-NET-1 dependency audit")
                appendLine("# format: scope<TAB>project<TAB>configuration<TAB>coordinate")
                appendLine("# SHIPPING = reaches the published APK; TEST = benchmark, host app")
                appendLine("#            fixture, or a test/netcontrol configuration")
                appendLine("# projects_examined=${projects.size}")
                appendLine("# configurations_examined=$configurationsExamined")
                appendLine("# configurations_failed_to_resolve=$configurationsFailed")
                appendLine("# rows=${rows.size} shipping_rows=$shipping")
                rows.forEach { appendLine(it) }
            }
        )
        logger.lifecycle(
            "dependencyAudit: ${rows.size} rows ($shipping shipping) from " +
                "$configurationsExamined configurations across ${projects.size} projects " +
                "-> ${f.relativeTo(rootDir)}"
        )
        if (configurationsFailed > 0) {
            logger.warn(
                "dependencyAudit: $configurationsFailed configuration(s) failed to resolve; " +
                    "their coordinates are NOT in the denominator."
            )
        }
    }
}
