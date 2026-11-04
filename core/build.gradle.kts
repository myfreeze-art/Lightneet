import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    // JVM target exists so the whole vault engine can be tested on a desktop
    // with the real libsodium. iOS targets get added here later; everything in
    // commonMain is already platform-neutral.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // libsodium glue is identical for JVM and Android; only the native loader differs.
        jvmMain { kotlin.srcDir("src/sodiumShared/kotlin") }
        androidMain { kotlin.srcDir("src/sodiumShared/kotlin") }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.protobuf)
            api(libs.okio)
            implementation(libs.atomicfu)
        }
        jvmMain.dependencies {
            implementation(libs.lazysodium.java)
            implementation(libs.jna)
        }
        androidMain.dependencies {
            // JNA must come as an AAR on Android (it carries the native dispatch libraries).
            implementation("com.goterl:lazysodium-android:${libs.versions.lazysodiumAndroid.get()}") {
                exclude(group = "net.java.dev.jna", module = "jna")
            }
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

android {
    namespace = "app.lightneet.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
