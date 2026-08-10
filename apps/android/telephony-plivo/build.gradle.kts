import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.triplex.telephony.plivo"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        // The prepared native stage (.native-deps/install) currently pins
        // arm64-v8a only; other ABIs fail fast in CMake by design.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        val skipNative = providers.gradleProperty("triplex.skipNative")
            .getOrElse("false")
            .equals("true", ignoreCase = true)
        if (!skipNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=c++_static")
                }
            }
        }
    }

    // CI sets -Ptriplex.skipNative=true for JVM policy/unit tests without the
    // prepared PJSIP stage.
    val skipNative = providers.gradleProperty("triplex.skipNative")
        .getOrElse("false")
        .equals("true", ignoreCase = true)
    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // src/main/kotlin holds the host-side TransportValidationRunner
            // CLI; it is not part of the Android library.
            java.setSrcDirs(listOf("src/main/java"))
            kotlin.setSrcDirs(listOf("src/main/java"))
        }
    }
}

// Kotlin 2.3 removed the `android.kotlinOptions` bridge; the JVM target now
// lives on the Kotlin extension.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
