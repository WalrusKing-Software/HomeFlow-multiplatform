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
            packageName = "org.homeflow"
            // Intentionally decoupled from the project version (gradle.properties):
            // jpackage requires the major component to be >= 1 for macOS dmg/pkg,
            // so a 0.x project version would break packageDmg. Reconcile to the
            // release X.Y.Z (>= 1.0.0) at packaging time. See __docs/BRANCHING.md.
            packageVersion = "1.0.0"
        }
    }
}
