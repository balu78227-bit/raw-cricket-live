plugins {
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

android {
    namespace = "com.rawcricket.live"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rawcricket.pro"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.5")
    implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.5.5")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
}
