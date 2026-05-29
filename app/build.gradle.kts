plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version is AUTOMATIC. GitHub Actions sets SEEINGMETER_VERSION to the workflow
// run number + 17, so run 1 → v18, run 2 → v19, etc.
// Local builds with no env var fall back to "18".
val appVer: String = System.getenv("SEEINGMETER_VERSION") ?: "18"

android {
    namespace = "com.seeingmeter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seeingmeter"
        minSdk = 29
        targetSdk = 35
        versionCode = appVer.toIntOrNull() ?: 18
        versionName = appVer
        resValue("string", "app_name", "SeeingMeter-$appVer")
        buildConfigField("String", "APP_VERSION", "\"$appVer\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
        debug   { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}