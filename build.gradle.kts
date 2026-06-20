import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    // Quality gates — applied to every module via `subprojects` below. Apply
    // false here so the plugin jars land on the classpath once (see __docs/TESTING.md).
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Single version + group for every module, sourced from gradle.properties
// (`version=`). Configuration-cache friendly. See __docs/BRANCHING.md.
allprojects {
    group = "org.homeflow"
    version = providers.gradleProperty("version").getOrElse("0.0.0")
}

// Apply ktlint + detekt to every module so `./gradlew ktlintCheck` and
// `./gradlew detekt` (the Husky hooks and CI build-checks) cover the whole repo.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        // Never lint generated code (e.g. Compose resource accessors under build/).
        filter {
            exclude { entry -> entry.file.absolutePath.replace('\\', '/').contains("/build/") }
        }
    }

    configure<DetektExtension> {
        // Use detekt's defaults plus the repo config, so a missing rule in the
        // config falls back to a sensible default instead of disabling it.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        ignoreFailures = false
    }
}