// Plugins are declared here (apply false) so a single resolved version from the
// version catalog is shared by every module. Do NOT re-introduce a `buildscript`
// classpath: it silently pins older AGP/KGP versions and makes the catalog a lie,
// which is what pulled in the Compose compiler 1.3.2 default.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinKapt) apply false
    // Compose compiler ships inside the Kotlin plugin since Kotlin 2.0.
    // Applying it is what stops AGP from falling back to its hardcoded
    // compose compiler 1.3.2 default (which requires Kotlin 1.7.20).
    alias(libs.plugins.kotlinCompose) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
