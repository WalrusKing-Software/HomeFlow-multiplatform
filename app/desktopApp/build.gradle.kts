import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
