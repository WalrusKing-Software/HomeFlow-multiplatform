plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

// group + version come from the root build (allprojects), sourced from
// gradle.properties `version=`. Do not hardcode here.
application {
    mainClass = "org.homeflow.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
