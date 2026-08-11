import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
}

// Consumed by :app through the composite build wired in
// apps/android/settings.gradle.kts.
group = "dev.triplex"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // :app runs on JVM 17 bytecode, so this module emits it. Deliberately
        // a target rather than a toolchain: a toolchain pins the *JDK* that
        // must be installed, and this build is meant to run wherever a JDK 17
        // or newer exists — a laptop, the Android CI job on 17, or a container
        // that only ships 21. `-Xjdk-release` keeps the newer JDK's APIs off
        // the compile classpath so building on 21 cannot produce something
        // Android will not load.
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        // The module is a contract for the SIP engine on the other side of the
        // seam; an accidental warning there is a real defect here.
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
