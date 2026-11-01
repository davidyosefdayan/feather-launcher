import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The release key stays private (setup/keystore.properties, not in git). Without it, release
// builds are signed with the debug key so anyone can still build and install the app.
val releaseKey = rootProject.file("setup/keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "app.featherlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.featherlauncher"
        minSdk = 30
        targetSdk = 37
        // Raise both for every release; the updater compares versionName with the release's tag (v1.1).
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
