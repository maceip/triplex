pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Triplex"
include(":app")

// The platform-free turn loop. A standalone build rather than a subproject so
// `cd dialogue && gradle test` runs the conversation tests on a bare JVM —
// without the Android SDK, without an AGP-compatible JDK, and without the two
// sibling checkouts the app needs. Composed in here so :app consumes it as an
// ordinary dependency.
includeBuild("dialogue") {
    dependencySubstitution {
        substitute(module("dev.triplex:dialogue")).using(project(":"))
    }
}
include(":telephony-plivo")
include(":telemetry-client")
// Local sibling checkout by default; CI overrides with -PtelemetryClientPath=…
val telemetryClientPath = providers.gradleProperty("telemetryClientPath")
    .getOrElse("../../../triplex-analytics/android/telemetry-client")
project(":telemetry-client").projectDir = file(telemetryClientPath)

// RikkaUI is consumed from source, not from Maven: the design system and this
// app move together, so a published snapshot would always be one step behind.
// The checkout is expected as a sibling of the triplex repo; override with
// -PrikkaUiPath=… . When it is absent the build still configures, and the
// dev.rikkaui:* coordinates resolve from Maven instead.
val rikkaUiPath = providers.gradleProperty("rikkaUiPath").getOrElse("../../../RikkaUi")
val rikkaUiDir = file(rikkaUiPath)
if (rikkaUiDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(rikkaUiDir) {
        // Spelled out rather than left to Gradle's automatic discovery: RikkaUI
        // sets its `group` from the publishing plugin, which is too late for
        // Gradle to infer the coordinates, so without this the module would
        // silently resolve from Maven Central instead of the checkout.
        dependencySubstitution {
            substitute(module("dev.rikkaui:components")).using(project(":components"))
            substitute(module("dev.rikkaui:foundation")).using(project(":foundation"))
        }
    }
}
