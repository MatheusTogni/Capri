plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.capri.assistente"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.capri.assistente"
        minSdk = 26
        targetSdk = 34
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
        versionCode = buildNumber ?: 16
        versionName = if (buildNumber != null) "1.7.$buildNumber" else "1.7.0"
    }

    // Chave fixa: assim cada APK novo instala por cima do anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

// sem dependências: HTTP com HttpURLConnection, JSON com org.json e interface montada em código
