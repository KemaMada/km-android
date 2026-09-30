// Plugins declarados, no aplicados. El unico modulo de este repositorio es
// `:app`; los aplica su propio `app/build.gradle.kts`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
