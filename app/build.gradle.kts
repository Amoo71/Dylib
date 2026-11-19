import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amoo.roomlauncher"
    compileSdk = 35
    // AirPlay-Empfänger (UxPlay, übernommen aus jqssun/android-airplay-server): nativer Code
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.amoo.roomlauncher"
        minSdk = 25
        targetSdk = 35
        versionCode = 21
        versionName = "3.0.0"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        // Galaxy Tab E: 32-Bit-ARM. Nur diese Architektur bauen – hält APK und Build klein.
        ndk { abiFilters += "armeabi-v7a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true // Oboe (Audio) als Prefab-Paket
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// UxPlay-Patches von jqssun vor dem CMake-Build auf das Untermodul anwenden (wie im Original)
tasks.register("applyUxplayPatches") {
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay", *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", "--unidiff-zero", it.path) }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    // Launcher selbst: klassische Views ohne Compose. AndroidX nur für den eingebauten AirPlay-Empfänger.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
}
