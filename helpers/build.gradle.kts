plugins {
    id("com.android.library")
}

android {
    compileSdk = 29

    defaultConfig {
        minSdk = 26
        // Remove the deprecated targetSdk from here
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    namespace = "com.google.ar.core.helpers"

    // Add these blocks to replace the deprecated targetSdk
    testOptions {
        targetSdk = 29
    }

    lint {
        targetSdk = 29
    }
}

dependencies {
    // Dependencies remain the same
    implementation("com.google.ar:core:1.23.0")
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.google.android.material:material:1.3.0")
    implementation("com.google.firebase:firebase-database:19.6.0")
    implementation("de.javagl:obj:0.2.1")
    implementation("androidx.activity:activity:1.7.2")
    implementation("com.github.haifengl:smile-core:2.6.0")
}