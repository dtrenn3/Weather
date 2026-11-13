plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.dtrenn.weather"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dtrenn.weather"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.work)
    implementation(libs.androidx.security)
}
