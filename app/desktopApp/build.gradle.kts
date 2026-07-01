import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Version sourced from gradle.properties `version.desktop`. packageVersion inside
// nativeDistributions still uses -PdesktopPackageVersion from the pipeline (jpackage
// major >= 1 constraint); this project.version is the canonical release record.
version = providers.gradleProperty("version.desktop").getOrElse("0.0.0")

// Generate a build-time version constant for the desktop app, mirroring Android's
// BuildConfig.VERSION_NAME. Resolved from gradle.properties at configuration time
// and written into the build output directory; the Kotlin source set below picks it up.
val generateDesktopBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/kotlin/desktopBuildConfig")
    // Resolve the provider to a plain String inside the task block so the config cache
    // doesn't need to serialize an outer-scope Gradle script object reference.
    val versionValue: String = providers.gradleProperty("version.desktop").getOrElse("unknown")
    outputs.dir(outDir)
    inputs.property("desktopVersion", versionValue)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        dir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package org.homeflow

            internal const val DESKTOP_VERSION = "$versionValue"
            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generateDesktopBuildConfig.map { it.outputs.files.singleFile })
        }
    }
}

dependencies {
    implementation(projects.app.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "org.homeflow.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HomeFlow"
            description = "HomeFlow — self-hosted period tracking"
            vendor = "HomeFlow"
            copyright = "© 2026 HomeFlow"
            // Installer version. jpackage requires major >= 1 (macOS dmg/pkg), so the
            // release pipeline passes -PdesktopPackageVersion = the release X.Y.Z when
            // major >= 1, else "1.0.0". Defaults to "1.0.0" for local packaging.
            // See __docs/RELEASE-PIPELINE.md §3.2.
            packageVersion = (project.findProperty("desktopPackageVersion") as String?) ?: "1.0.0"

            // Best-effort screenshot protection on desktop is a runtime concern (the window
            // is not added to the OS screen-capture exclusion here); see the client security
            // checklist in __docs/ARCHITECTURE-client.md.
            windows {
                menuGroup = "HomeFlow"
                // Stable UUID so MSI upgrades replace the prior install instead of stacking.
                upgradeUuid = "5f1d2c9e-7b3a-4e2f-9c8d-1a2b3c4d5e6f"
            }
            linux {
                packageName = "homeflow"
            }
        }
    }
}
