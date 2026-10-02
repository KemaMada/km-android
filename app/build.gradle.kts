// `java` esta sombreado por la extension `java` del plugin Android, asi que
// `java.util.Properties` no resuelve sin este import explicito.
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinKapt)
    // Required with Kotlin 2.x: the Compose compiler lives inside the Kotlin
    // plugin, so this marker must be applied or AGP injects its legacy
    // compose compiler 1.3.2 default, which requires Kotlin 1.7.20.
    alias(libs.plugins.kotlinCompose)
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
// configurar el proyecto y compilar debug sin tenerla, y sin ella
// :app:assembleRelease produce un APK SIN FIRMAR en vez de fallar.
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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.keymessage"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Sin clave, NO se asigna signingConfig: Gradle produce
            // `app-release-unsigned.apk`, que Android no instalara hasta que
            // alguien lo firme. Asignar una config con contrasenas por defecto
            // seria exactamente la fuga que esta seccion existe para evitar.
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    // No `composeOptions.kotlinCompilerExtensionVersion`: with Kotlin 2.x the
    // Compose compiler is provided by the org.jetbrains.kotlin.plugin.compose
    // plugin applied above, and AGP rejects the legacy extension version.

    packaging {
        resources {
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
        }
    }
}

dependencies {
    // km-framework (desde mavenLocal)
    implementation("com.km:km-core:0.1.0-SNAPSHOT")

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose (BOM constrains ui / material3 / icons to one aligned version set)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.ui.tooling.preview)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okio)

    // WebRTC
    implementation(libs.webrtc)

    // ZXing
    implementation(libs.zxing.android)
    implementation(libs.zxing.core)

    // Crypto
    implementation(libs.security.crypto)

    // JSON
    implementation(libs.jackson)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    // Real org.json on the unit-test classpath: the android.jar stub throws
    // "not mocked" for every call, so RelayClient/RelayDataCodec could not run.
    testImplementation(libs.orgjson)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}
