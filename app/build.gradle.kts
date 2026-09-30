// `java` esta sombreado por la extension `java` del plugin Android, asi que
// `java.util.Properties` no resuelve sin este import explicito.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Firma de release.
//
// La clave NO esta en el repositorio. Una clave de firma versionada es una
// fuga: cualquiera con el repositorio podria publicar una actualizacion firmada
// con la identidad de esta app, y Android rechazaria la app legitima o, peor,
// el usuario instalaria algo que no es de KemaMada. Ver README.md, seccion
// "Como firmar una release".
//
// Por eso este bloque se crea SOLO si existe la clave. Un clon limpio puede
// configurar el proyecto y compilar debug sin tenerla.
//
// Las credenciales se leen de `keystore.properties` en la raiz del repositorio
// (ignorado por git) o de propiedades de Gradle en la linea de ordenes.
// ---------------------------------------------------------------------------
val keystoreProperties = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

fun releaseSetting(key: String): String? =
    keystoreProperties?.getProperty(key) ?: providers.gradleProperty("km.release.$key").orNull

val releaseStoreFile: File? = releaseSetting("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

val releaseSigningReady: Boolean =
    releaseStoreFile != null &&
        listOf("storePassword", "keyAlias", "keyPassword").all { releaseSetting(it) != null }

if (releaseSigningReady) {
    logger.lifecycle("app: firma de release configurada desde ${releaseStoreFile!!.name}")
} else {
    logger.lifecycle("app: sin clave de release. Se compila, pero :app:assembleRelease quedara SIN FIRMAR.")
}

android {
    namespace = "com.example.keymessage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.keymessage"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSetting("storePassword")
                keyAlias = releaseSetting("keyAlias")
                keyPassword = releaseSetting("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---- PROTOCOLO: vive en km-framework, NO en este repositorio ------------
    //
    // Este es el unico punto por el que km-android toca la logica de protocolo.
    // No hay copia local de km-core aqui, y no debe haberla: dos copias del
    // protocolo divergen, y el cliente acabaria usando la equivocada.
    //
    // `com.km:km-core:0.1.0-SNAPSHOT` sigue siendo una COORDENADA PROVISIONAL.
    // km-core no esta publicado en Maven Central ni en ningun registro, asi que
    // de momento no existe un repositorio del que resolverla. Lo que la
    // resuelve es el `includeBuild("../KeyMessage")` de settings.gradle.kts:
    // Gradle compila km-core desde el codigo fuente y sustituye esta
    // dependencia por ese proyecto. Es un puente, no el estado final.
    //
    // Cuando km-core se publique, esta linea no cambia (misma group, misma
    // version); lo que desaparece es el `includeBuild` de settings.gradle.kts y
    // `mavenCentral()` en `dependencyResolutionManagement` pasa a ser la fuente
    // real del artefacto.(group/version las declaro yo en km-core/build.gradle.kts
    // con este mismo criterio; ver el comentario ahi.)
    implementation("com.km:km-core:0.1.0-SNAPSHOT")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.webrtc.sdk)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
