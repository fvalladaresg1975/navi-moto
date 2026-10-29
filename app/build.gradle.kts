import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key, kept out of git (see keystore/ in .gitignore). Android only accepts
// an update signed with the same key as the installed app, so this folder must be backed
// up: losing it means uninstalling (and reconfiguring) on every phone.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.chronoscompanion.app"
    compileSdk = 34

    defaultConfig {
        // Never change: Android would install it as a different app and every setting
        // (paired board, contacts, chosen apps) would be lost.
        applicationId = "org.chronoscompanion.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "2.2"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val releaseKey = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseKey
        }
        debug {
            // Same key as release, so a debug build can be installed over a release one
            // (and back) without uninstalling.
            if (releaseKey != null) signingConfig = releaseKey
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
