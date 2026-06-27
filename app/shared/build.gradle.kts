import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "org.homeflow.app.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
            // android.util.Log (used by the auth Diagnostics actual) is a stub in host
            // unit tests and throws "not mocked" by default; return defaults instead.
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // Android auth actuals: OIDC (AppAuth + Custom Tab), Keystore-backed
            // secure storage, biometric app-lock gate, OkHttp engine.
            implementation(libs.ktor.client.okhttp)
            implementation(libs.appauth)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment)
            implementation(libs.androidx.security.crypto)
            // SQLDelight Android driver + SQLCipher for whole-DB encryption (Phase 13).
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
        }
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // Multiplatform Ktor client + auth/bearer + JSON negotiation.
            implementation(libs.ktor.client.core.mp)
            implementation(libs.ktor.client.contentNegotiation.mp)
            implementation(libs.ktor.client.auth.mp)
            implementation(libs.ktor.serialization.json.mp)
            // SQLDelight runtime (Phase 13).
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock.mp)
        }
        jvmMain.dependencies {
            // Desktop auth actuals: CIO engine, OS-keychain secure storage.
            implementation(libs.ktor.client.cio.mp)
            implementation(libs.java.keyring)
            // SQLDelight JVM driver backed by willena SQLite+SQLCipher (Phase 13).
            implementation(libs.sqlite.jdbc.willena)
            implementation(libs.sqldelight.sqlite.driver)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // In-memory SQLite driver for contract/parity/analytics tests (no SQLCipher).
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("HomeFlowDb") {
            packageName.set("org.homeflow.app.shared.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// AppAuth (an androidMain dependency) contributes a manifest entry using the
// ${appAuthRedirectScheme} placeholder; supply a value so the library's own
// (test) manifest merge resolves it. The real app value is set in :app:androidApp.
extensions.configure<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        val scheme = "org.homeflow.mobile"
        variant.manifestPlaceholders.put("appAuthRedirectScheme", scheme)
        // Test components merge their own manifest, so they need the value too.
        variant.hostTests.values.forEach { it.manifestPlaceholders.put("appAuthRedirectScheme", scheme) }
        variant.deviceTests.values.forEach { it.manifestPlaceholders.put("appAuthRedirectScheme", scheme) }
    }
}
