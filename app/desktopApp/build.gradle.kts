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
            // Intentionally decoupled from the project version (gradle.properties):
            // jpackage requires the major component to be >= 1 for macOS dmg/pkg,
            // so a 0.x project version would break packageDmg. Reconcile to the
            // release X.Y.Z (>= 1.0.0) at packaging time. See __docs/BRANCHING.md.
            packageVersion = "1.0.0"

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
