plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.triplex.dialer.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.benchmark.junit4)
}
