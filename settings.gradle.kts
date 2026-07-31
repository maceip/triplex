pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://aws-chime-sdk.s3.amazonaws.com/releases") }
    }
}

rootProject.name = "triplex-dialer"
include(":app")
