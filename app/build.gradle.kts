plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.erivaldogelson.recnow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.erivaldogelson.recnow.grtis"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "1.0.30-grtis"
    }

    signingConfigs {
        create("release") {
            val storePath = (findProperty("RECNOW_RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RECNOW_RELEASE_STORE_FILE")
                ?: "${System.getProperty("user.home")}\\.android\\debug.keystore"
            val storePasswordValue = (findProperty("RECNOW_RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RECNOW_RELEASE_STORE_PASSWORD")
                ?: "android"
            val keyAliasValue = (findProperty("RECNOW_RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RECNOW_RELEASE_KEY_ALIAS")
                ?: "androiddebugkey"
            val keyPasswordValue = (findProperty("RECNOW_RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RECNOW_RELEASE_KEY_PASSWORD")
                ?: "android"

            storeFile = file(storePath)
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.google.play.billing)
    implementation(libs.google.play.services.ads)
}
