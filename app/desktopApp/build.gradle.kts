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

// Dev builds (-PdevBuild) install as "HomeFlow-Dev" with a separate upgradeUuid so they
// coexist with production installs rather than replacing them.
val isDevBuild = project.hasProperty("devBuild")
val appName = if (isDevBuild) "HomeFlow-Dev" else "HomeFlow"

// Generate a build-time version constant for the desktop app, mirroring Android's
// BuildConfig.VERSION_NAME. Resolved from gradle.properties at configuration time
// and written into the build output directory; the Kotlin source set below picks it up.
val generateDesktopBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/kotlin/desktopBuildConfig")
    // Resolve the provider to a plain String inside the task block so the config cache
    // doesn't need to serialize an outer-scope Gradle script object reference.
    val versionValue: String = providers.gradleProperty("version.desktop").getOrElse("unknown")
    val dataDirValue: String = if (isDevBuild) ".homeflow-dev" else ".homeflow"
    outputs.dir(outDir)
    inputs.property("desktopVersion", versionValue)
    inputs.property("desktopDataDir", dataDirValue)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        dir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package org.homeflow

            internal const val DESKTOP_VERSION = "$versionValue"
            internal const val DESKTOP_DATA_DIR = "$dataDirValue"
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
            packageName = appName
            description =
                if (isDevBuild) {
                    "HomeFlow — self-hosted period tracking (Development Build)"
                } else {
                    "HomeFlow — self-hosted period tracking"
                }
            vendor = "HomeFlow"
            copyright = "© 2026 HomeFlow"
            // Installer version. jpackage requires major >= 1 (macOS dmg/pkg), so the
            // release pipeline passes -PdesktopPackageVersion = the release X.Y.Z when
            // major >= 1, else "1.0.0". Defaults to "1.0.0" for local packaging.
            // Dev builds use 1.<days-since-2024-01-01>.<minute-of-day> so every build
            // (down to the minute) produces a strictly higher version and MSI upgrade logic
            // replaces the previous install automatically without needing an explicit uninstall.
            // See __docs/RELEASE-PIPELINE.md §3.2.
            packageVersion =
                if (isDevBuild) {
                    // Encode minutes since 2024-01-01 00:00 UTC as a base-65536 number split
                    // across MINOR and BUILD. MSI limits: MAJOR 0-255, MINOR 0-255, BUILD 0-65535.
                    // Days since epoch (~950 by Aug 2026) exceeds MINOR's limit of 255, so we
                    // use minutesSinceEpoch / 65536 for MINOR (~20 by Aug 2026, grows ~8/year)
                    // and minutesSinceEpoch % 65536 for BUILD. Monotonically increasing per minute.
                    val minutesSinceEpoch = ((System.currentTimeMillis() - 1_704_067_200_000L) / 60_000L).toInt()
                    "1.${minutesSinceEpoch / 65536}.${minutesSinceEpoch % 65536}"
                } else {
                    (project.findProperty("desktopPackageVersion") as String?) ?: "1.0.0"
                }

            // The bundled runtime is produced by jlink, which only keeps JDK modules it
            // can detect statically. Two modules must be forced in or the packaged app
            // (MSI/DMG/DEB) crashes at runtime with NoClassDefFoundError — both work under
            // `./gradlew run` because that uses the full JDK:
            //   - java.sql: the local SQLite DB is opened through JdbcSqliteDriver ->
            //     java.sql.DriverManager, loaded reflectively via ServiceLoader, so jlink
            //     can't see the dependency and strips java.sql. See LocalDatabaseFactory.jvm.kt.
            //   - jdk.httpserver: the desktop OIDC login runs a loopback redirect listener
            //     on 127.0.0.1 using com.sun.net.httpserver.HttpServer; jlink's jdeps scan
            //     doesn't pull jdk.httpserver in, so connecting to a server crashes with
            //     NoClassDefFoundError: com/sun/net/httpserver/HttpServer. See OidcClient.jvm.kt.
            modules("java.sql", "jdk.httpserver")

            // Best-effort screenshot protection on desktop is a runtime concern (the window
            // is not added to the OS screen-capture exclusion here); see the client security
            // checklist in __docs/ARCHITECTURE-client.md.
            // App icon (the HomeFlow bloom, icons/homeflow-icon.svg). jpackage needs a
            // platform-native format per OS: .ico for Windows, .icns for macOS, .png for
            // Linux. Rasterised from the SVG into app/desktopApp/icons/.
            val iconsDir = project.file("icons")
            windows {
                menuGroup = appName
                // Dev builds use a separate UUID so they never replace the production install.
                // Production UUID is stable for in-place upgrades (see RELEASE-PIPELINE.md §3.2).
                upgradeUuid =
                    if (isDevBuild) "a9f1e2d3-b4c5-4d6e-8f70-1a2b3c4d5e6f" else "5f1d2c9e-7b3a-4e2f-9c8d-1a2b3c4d5e6f"
                iconFile.set(iconsDir.resolve("homeflow.ico"))
            }
            macOS {
                iconFile.set(iconsDir.resolve("homeflow.icns"))
            }
            linux {
                packageName = if (isDevBuild) "homeflow-dev" else "homeflow"
                iconFile.set(iconsDir.resolve("homeflow.png"))
            }
        }
    }
}
