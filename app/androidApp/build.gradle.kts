import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.app.shared)

    implementation(libs.androidx.activity.compose)
    // MainActivity is a FragmentActivity (BiometricPrompt) and bridges the AppAuth
    // result launcher via coroutines — both are implementation-scoped in :app:shared.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.core)
    // Provides the Theme.AppCompat parent for the app theme (AppAuth's redirect
    // receiver activity requires an AppCompat-descendant theme). See res/values/themes.xml.
    implementation(libs.androidx.appcompat)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "org.homeflow"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "org.homeflow"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        // versionName tracks the project version (gradle.properties); bump
        // versionCode manually on every distributed build (see __docs/BRANCHING.md).
        versionCode = 1
        versionName = project.version.toString()
        // AppAuth's RedirectUriReceiverActivity captures the OIDC custom-scheme
        // redirect; the scheme must match homeflow-android's Valid Redirect URI
        // (org.homeflow.mobile:/oauth2redirect) in the Keycloak realm. See KEYCLOAK.md.
        manifestPlaceholders["appAuthRedirectScheme"] = "org.homeflow.mobile"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
