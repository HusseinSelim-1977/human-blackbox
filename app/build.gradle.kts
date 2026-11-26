plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is only wired when the operator provides google-services.json.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.humanblackbox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.humanblackbox.humanblackbox.v2"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sensors"))
    implementation(project(":ml-audio"))
    implementation(project(":ml-motion"))
    implementation(project(":ml-vision"))
    implementation(project(":ml-fusion"))
    implementation(project(":data"))
    implementation(project(":cloud"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Local contacts/event history are encrypted on-device; no Firebase config is required
    // for compilation or for the manual SOS path.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.firebase:firebase-messaging-ktx:24.0.0")
}
