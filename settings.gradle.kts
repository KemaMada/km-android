// ---------------------------------------------------------------------------
// km-android
//
// La app Android es un CONSUMIDOR de km-framework. No contiene copia alguna de
// la logica de protocolo: la toma de `com.km:km-core`.
// ---------------------------------------------------------------------------

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

// === DEPENDENCIA PROVISIONAL, NO EL ESTADO FINAL ============================
//
// km-core todavia NO esta publicado en Maven Central ni en ningun registro
// (no hay plugin `maven-publish` aplicado en km-core). Este `includeBuild` es
// el puente MIENTRAS TANTO: compila km-core desde el codigo fuente, y Gradle
// sustituye automaticamente la dependencia `com.km:km-core:0.1.0-SNAPSHOT` por
// el proyecto `:km-core` de ese checkout.
//
// Cuando km-core se publique, esta linea se sustituye por el repositorio real
// (mavenCentral() basta) y la dependencia del modulo `app` pasa a resolverse
// como un artefacto normal, sin checkout local.
//
// Requisito: el checkout de km-framework tiene que ser hermano de este
// repositorio y llamarse `km-framework`. Si tu clone local se llama de otra
// forma, ajusta la ruta de abajo.
//
// Nota de este entorno: el checkout local de km-framework se llama `KeyMessage`
// (en GitHub el repositorio se llama `km-framework`), asi que la ruta que
// funciona aqui es `../KeyMessage`. Se deja la que funciona y se documenta para
// que no parezca un error.
includeBuild("../KeyMessage")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "km-android"
include(":app")
