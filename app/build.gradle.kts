plugins {
    id("com.android.application")
    alias(libs.plugins.google.gms.google.services)
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.palmpet20"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    namespace = "com.example.palmpet20"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

dependencies {
    // ARCore Library
    implementation("com.google.ar:core:1.45.0")
    implementation("com.google.android.material:material:1.9.0")

    implementation(project(":helpers"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Force a consistent version of Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
    implementation(libs.firebase.database)
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10")
    }
}