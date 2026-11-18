plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amoo.roomlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amoo.roomlauncher"
        minSdk = 25
        targetSdk = 35
        versionCode = 9
        versionName = "2.5.0"
    }

    // Fester Schlüssel im Repo, damit neue APKs sich über alte installieren lassen.
    // Nur für private Sideload-Nutzung gedacht.
    signingConfigs {
        create("room") {
            storeFile = rootProject.file("keystore/room-launcher.jks")
            storePassword = "roomlauncher"
            keyAlias = "room"
            keyPassword = "roomlauncher"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("room")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("room")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Bewusst ohne AndroidX/Compose: klassische Views sind auf dem Galaxy Tab E am flüssigsten.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
}
