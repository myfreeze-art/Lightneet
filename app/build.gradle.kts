plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.lightneet"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.myfreezeart.lightneet"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Drop the obsolete armeabi/mips builds that JNA still ships.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // Store native libs uncompressed and page-aligned so they load straight from the APK.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The JVM unit tests run the real Android code path (lazysodium-android over JNA) on the
// host. They only need a desktop build of libsodium, taken from lazysodium-java's jar.
val hostSodium: Configuration by configurations.creating
val extractHostSodium by tasks.registering(Copy::class) {
    from({ zipTree(hostSodium.singleFile) }) { include("linux64/**", "mac/**", "windows64/**") }
    into(layout.buildDirectory.dir("hostSodium"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractHostSodium)
    val base = layout.buildDirectory.dir("hostSodium").get().asFile
    systemProperty("jna.library.path", listOf("linux64", "mac/aarch64", "mac/intel", "windows64").joinToString(File.pathSeparator) { File(base, it).path })
    maxHeapSize = "2g"
}

dependencies {
    hostSodium(libs.lazysodium.java) { isTransitive = false }
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.zxcvbn)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.jna)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
