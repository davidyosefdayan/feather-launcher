import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The release key stays private (setup/keystore.properties, not in git). Without it, release
// builds are signed with the debug key so anyone can still build and install the app.
val releaseKey = rootProject.file("setup/keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

// Android only updates an app to a build with an equal or higher versionCode, so it's the number
// of commits, which only goes up on main. Betas (-Pbeta, built by GitHub for every merge to main)
// are called "1.5 beta 112": the updater reads the part before the space.
val commits = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1
val beta = providers.gradleProperty("beta").isPresent

android {
    namespace = "app.featherlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.featherlauncher"
        minSdk = 30
        targetSdk = 37
        // Raise for every release; the updater compares it with the release's tag (v1.1).
        val release = "1.6"
        versionCode = commits
        versionName = if (beta) "$release beta $commits" else release
    }

    signingConfigs {
        // Feather Dev's key is public on purpose, so anyone's Dev build updates anyone else's.
        // It only ever signs app.featherlauncher.dev, which can't replace the real Feather.
        create("dev") {
            storeFile = file("feather-dev.jks")
            storePassword = "featherdev"
            keyAlias = "dev"
            keyPassword = "featherdev"
        }
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Feather Dev (./gradlew installDebug): installs next to the real Feather, with its own
        // settings, for trying out work that isn't released yet.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = " dev"
            signingConfig = signingConfigs.getByName("dev")
        }
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

// Every Dev build has the same versionCode, so any one installs over any other, older or newer.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set(1) }
    }
}
