import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "dev.triplex"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.triplex"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GATEWAY_URL", "\"${localProperties.getProperty("gateway.url", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "AGENT_TRANSFER_NUMBER", "\"${localProperties.getProperty("plivo.agent.transfer.number", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        ndk {
            // Matches the prepared telephony native stage, which is arm64-only.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            // :app and :telephony-plivo both import the same staged Rust
            // cdylib; keep a single copy.
            pickFirsts += "**/libtriplex_native_media.so"
        }
    }

    androidResources {
        noCompress += "zip"
    }
}

// Hilt's aggregating task runs its processor through javac, where it reads
// Kotlin metadata with a shaded kotlin-metadata-jvm capped at format 2.2.0 —
// and Kotlin 2.3.20 emits 2.3.0, so it hard-fails on our own @Module objects.
// Turning the task off routes the same processing through KSP, which reads
// symbols from the compiler instead of from class metadata. Revisit when a Hilt
// release ships a 2.3-capable metadata library.
hilt {
    enableAggregatingTask = false
}

// Kotlin 2.3 removed the `android.kotlinOptions` bridge; the JVM target now
// lives on the Kotlin extension.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":telephony-plivo"))
    implementation(project(":telemetry-client"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("com.google.ai.edge.litert:litert:2.1.3")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta3")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Liquid glass. RikkaUI comes from the ~/RikkaUi checkout via the composite
    // build wired in settings.gradle.kts; `foundation` is listed explicitly
    // because `components` depends on it with `implementation`, so its theme
    // types would otherwise stay off this module's compile classpath.
    implementation(libs.rikka.ui.components)
    implementation(libs.rikka.ui.foundation)
    implementation(libs.backdrop)
    implementation(libs.swipe)
    implementation(libs.extendedspans)

    // Icons. One vocabulary (RikkaIcons tokens), one pack chosen at the
    // composition root (Phosphor), so stroke weight and tint are a single
    // decision instead of a per-call-site one.
    implementation(libs.rikka.icons.core)
    implementation(libs.rikka.icons.tokens.core)
    implementation(libs.rikka.icons.pack.phosphor)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Local-only agent run history (reskin.md §2.4, §5): screened-call
    // transcripts never leave the device, so there is no sync layer above this.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.timber)
    implementation("com.google.protobuf:protobuf-javalite:4.34.1")
    implementation("com.getkeepsafe.relinker:relinker:1.4.5")
    implementation("com.google.guava:guava:33.6.0-android")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
