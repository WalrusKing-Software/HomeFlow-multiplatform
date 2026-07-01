import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Version sourced from gradle.properties `version.android`. versionName below reads
// project.version; versionCode is supplied by the pipeline via -PversionCode.
version = providers.gradleProperty("version.android").getOrElse("0.0.0")

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
        // versionName tracks the project version (gradle.properties). versionCode is
        // a monotonic integer supplied by the release pipeline via -PversionCode
        // (derived from the version core); defaults to 1 for local/dev builds.
        // See __docs/RELEASE-PIPELINE.md §3.4.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
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
    // Release signing is driven by an untracked `keystore.properties` at the repo root
    // (see keystore.properties.example). When absent — local dev, CI without secrets —
    // no release signing config is wired and `bundleRelease` produces an unsigned AAB.
    // Secrets never live in the build script or VCS. See __docs/BRANCHING.md / DEPLOYMENT.md.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val releaseSigning =
        if (keystorePropsFile.exists()) {
            val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
            signingConfigs.create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        } else {
            null
        }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            releaseSigning?.let { signingConfig = it }
        }
    }
    buildFeatures {
        // Needed for BuildConfig.VERSION_NAME (passed as clientVersion to AppRoot).
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
