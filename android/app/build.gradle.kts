import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keys and signing details come from -P properties or the environment, never from the repo.
fun setting(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val keystore = setting("NAVBRIDGE_KEYSTORE")

android {
    namespace = "com.cubevg.navbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cubevg.navbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = (setting("NAVBRIDGE_VERSION_CODE") ?: "3").toInt()
        versionName = setting("NAVBRIDGE_VERSION_NAME") ?: "0.3"
        // Routes API key for the blue route line. Builds without one still relay turns.
        buildConfigField("String", "MAPS_API_KEY", "\"${setting("MAPS_API_KEY") ?: ""}\"")
        // Must match the id in watch/manifest.xml.
        buildConfigField("String", "WATCH_APP_ID", "\"ab20da3b6a6544eb8cf6feede0824f3f\"")
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = setting("NAVBRIDGE_KEYSTORE_PASSWORD")
                keyAlias = setting("NAVBRIDGE_KEY_ALIAS") ?: "navbridge"
                keyPassword = setting("NAVBRIDGE_KEY_PASSWORD") ?: setting("NAVBRIDGE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a keystore the release build is signed with the debug key, which is fine for testing.
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Sideloaded app: no Play Store policy checks.
        disable += setOf("BatteryLife", "QueryAllPackagesPermission")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
