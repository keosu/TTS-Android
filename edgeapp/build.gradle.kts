plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mosstts.edge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mosstts.edge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the same optional signing key as MOSS; the package names are independent.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    signingConfigs {
        val releaseKey = rootProject.file("app/release.keystore")
        if (releaseKey.exists()) {
            create("release") {
                storeFile = releaseKey
                storePassword = "mosstts123"
                keyAlias = "release"
                keyPassword = "mosstts123"
            }
            buildTypes.getByName("release").signingConfig = getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
