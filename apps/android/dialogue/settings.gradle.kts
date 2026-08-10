dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Deliberately a standalone build rather than an `include(":dialogue")` in the
// Android settings file. The turn loop this module holds is the part of the
// agent we most need to test, and an Android module cannot be tested without
// the SDK, an AGP-compatible JDK, and the two sibling checkouts `:app` pulls
// in. As its own build it is `gradle test` on a bare JVM — which is what CI
// and a laptop both actually have — while `apps/android/settings.gradle.kts`
// composes it into the app through `includeBuild`, the same way RikkaUI is
// consumed.
rootProject.name = "dialogue"
