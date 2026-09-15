import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun versionNameDate(): String {
    val fmt = SimpleDateFormat("yyyyMMdd.HH").apply { timeZone = TimeZone.getTimeZone("GMT+8") }
    return fmt.format(Date())
}

fun getAppGitHead(): String =
    ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim()

fun getAppBuildTime(): String =
    ProcessBuilder("git", "log", "-1", "--pretty=%ai").start().inputStream.bufferedReader().readText().trim()

android {
    compileSdk = 37
    namespace = "com.yuyan.imemodule"
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        javaCompileOptions {
            annotationProcessorOptions {
                arguments(mapOf("AROUTER_MODULE_NAME" to project.name))
            }
        }
        lint {
            abortOnError = false
        }

        buildConfigField("String", "versionName", "\"${versionNameDate()}\"")
        buildConfigField("String", "AppCommitHead", "\"${getAppGitHead()}\"")
        buildConfigField("String", "AppBuildTime", "\"${getAppBuildTime()}\"")
    }

    flavorDimensions += "default"
    productFlavors {
        create("offline") {
            dimension = "default"
            buildConfigField("Boolean", "offline", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg")
            resValue("string", "app_name", "@string/ime_yuyan_name")
            resValue("drawable", "app_icon", "@drawable/ic_sdk_launcher")
        }
        getByName("debug") {
            resValue("string", "app_name", "@string/ime_yuyan_name_debug")
            resValue("drawable", "app_icon", "@drawable/ic_sdk_launcher_debug")
        }
    }

    sourceSets {
        getByName("offline").jniLibs.srcDirs("libs")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    ndkVersion = "30.0.14904198"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.emoji2:emoji2-views:1.5.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("com.louiscad.splitties:splitties-resources:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-recyclerview:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-constraintlayout:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-appcompat:3.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
}

tasks.register("makeaar", Copy::class) {
    from("build/outputs/aar/")
    into("build/outputs/aar/")
    include("imeModule-dev-debug.aar")
    rename("imeModule-dev-debug.aar", "imesdk_" + versionNameDate() + ".aar")
    dependsOn("assembleDebug")
}