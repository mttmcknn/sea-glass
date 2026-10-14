plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.mttmcknn.seaglass.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mttmcknn.seaglass.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("demoRelease") {
            initWith(getByName("release"))
            // Installable performance demo; shares the sample debug APK's identity.
            // Applications publishing to a store should use their own release key.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(project(":sample"))
    implementation(project(":sea-glass"))
    implementation(libs.androidx.activity.compose)
}
