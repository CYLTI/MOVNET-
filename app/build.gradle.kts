plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.movnet.app"
    compileSdk = 35

    defaultConfig {
        val apiUrl = (project.findProperty("MOVNET_API_BASE_URL") as String?) ?: ""
        buildConfigField("String", "MOVNET_API_BASE_URL", "\"$apiUrl\"")
        applicationId = "com.movnet.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "0.14.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.webrtc:google-webrtc:1.0.32006")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
