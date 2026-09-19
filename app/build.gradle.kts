// Test harness for :mdm-lite: a WebView screen wired the way android-menu-board is,
// plus crash/ANR triggers. Not shipped.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mdmServerUrl: String = (project.findProperty("mdmServerUrl") as String?) ?: "http://10.32.1.170:8083"
val mdmEnrollToken: String = (project.findProperty("mdmEnrollToken") as String?) ?: ""
// Empty = the built-in sample menu page (no network needed); set to test a real page.
val demoUrl: String = (project.findProperty("demoUrl") as String?) ?: ""

android {
    namespace = "com.aioapp.mdmlite.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aioapp.mdmlite.demo"
        minSdk = 29
        targetSdk = 35
        // Overridable to build a newer copy for testing remote app updates:
        // ./gradlew assembleDebug -PversionCode=3 -PversionName=0.1.2
        versionCode = ((project.findProperty("versionCode") as String?) ?: "2").toInt()
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.1"
        buildConfigField("String", "MDM_SERVER_URL", "\"$mdmServerUrl\"")
        buildConfigField("String", "MDM_ENROLL_TOKEN", "\"$mdmEnrollToken\"")
        buildConfigField("String", "DEMO_URL", "\"$demoUrl\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":mdm-lite"))
}
