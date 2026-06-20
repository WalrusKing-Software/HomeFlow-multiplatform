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
}

// Single version + group for every module, sourced from gradle.properties
// (`version=`). Configuration-cache friendly. See __docs/BRANCHING.md.
allprojects {
    group = "org.homeflow"
    version = providers.gradleProperty("version").getOrElse("0.0.0")
}