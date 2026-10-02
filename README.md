# km-android

**km-android** es el cliente Android de **KeyMessage**: la aplicación que un
usuario instala, con interfaz de usuario, lista de contactos, conversaciones y
código QR.

No es un repositorio de protocolo. La criptografía, la identidad, la
negociación, el ratchet y el transporte viven en
[**km-framework**](https://github.com/KemaMada/km-framework). Aquí solo hay lo
que hace falta para *usar* ese protocolo desde un teléfono.

```
km-framework  =  protocolo + criptografía + transporte      (com.km.*)
km-android    =  interfaz + almacenamiento + red Android   (com.example.keymessage)
```

---

## Qué hace

| Pieza | Dónde | Qué hace |
|-------|-------|----------|
| Interfaz | `ui/` | Pantallas Compose: lista de contactos, conversación, contacto nuevo, panel lateral. Tema propio en `ui/theme/`. |
| Estado de UI | `ui/ConversationViewModel.kt`, `model/AppState.kt` | Puente entre Compose y `com.km.api.KeyMessageCore`. |
| Persistencia local | `storage/room/` | Room: mensajes, cola de salida y control de duplicados. Implementa las interfaces `com.km.storage.*` sobre SQLite. |
| Transporte al relé | `network/RelayClient.kt` | WebSocket contra el relé, autenticación, confirmaciones, reconexión. Implementa `com.km.protocol.Transport` y `com.km.node.RelayTransport`. |
| Transporte P2P | `network/WebRtcManager.kt` | `PeerConnection` con el SDK WebRTC nativo de Android. |
| Descubrimiento | `network/dht/` | Nodo DHT en UDP para localizar pares. **Experimental**, no forma parte del protocolo. |
| Clave local | `AppContainer.kt`, `crypto/Crypto.kt` | Par de claves Ed25519 propio, en `SharedPreferences`. |
| QR | `ui/MainActivity.kt` | Genera el QR de identidad con ZXing y escanea el del peer (`CaptureActivity` de zxing-android-embedded). |

Y el protocolo, tomado de `km-core`:

| Tipo de km-framework | Para qué lo usa esta app |
|----------------------|-------------------------|
| `com.km.api.KeyMessageCore` / `KeyMessageCoreImpl` | Fachada: crear mensajes, enviar, recibir. |
| `com.km.crypto.Ed25519Impl` | Generar el par de claves local y firmar. |
| `com.km.model.Message`, `MessageId`, `IdentityId`, `MessageState` | Modelo de datos que la UI pinta. |
| `com.km.codec.JsonRelayControlCodec` | Serializar los mensajes de control del relé. |
| `com.km.storage.MessageStore` / `OfflineQueue` / `DuplicateStore` | Contratos que `storage/room/` implementa sobre Room. |
| `com.km.protocol.ConnectionState` | Estado de la conexión, pintado en pantalla. |

---

## Qué NO es

- **No es una fuente alternativa del protocolo.** No hay aquí ni una línea de
  lógica criptográfica propia ni una copia de `km-core`. Si algo del protocolo
  cambia, cambia en km-framework y esta app lo consume. Dos copias del
  protocolo divergen, y el cliente acaba usando la equivocada.
- **No está en producción.** Endpoints y direcciones están a fuego en el código
  (`ws://10.0.2.2:8080/ws` en `AppContainer.kt`, `http://13.140.155.230:8080`
  en `DhtNode.kt`, un `peerId` de relleno en `MainActivity.kt`).
  `android:usesCleartextTraffic="true"` está activo.
- **No está auditado.** No ha pasado ninguna revisión de seguridad externa.
- **No tiene `minifyEnabled`.** El build de release no ofusca nada
  (`isMinifyEnabled = false`), así que `proguard-rules.pro` está vacío.
- **No hay CI.** No existe `.github/`. Los tests se ejecutan en local.
- **No cubre la integración real.** No hay un test que levante un relé real. La
  cobertura de `RelayClient` es a nivel de **wire**: `RelayClientWireTest`
  levanta un `MockWebServer` y comprueba los frames y el parseo sobre el
  socket, no contra el protocolo de producción.

---

## Requisitos

| Requisito | Versión | Nota |
|-----------|---------|------|
| Android SDK | platform **android-34** | `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`. |
| Build tools | 34.x o superior | |
| JDK para ejecutar Gradle | **17** | Ver la nota de abajo: `gradle.properties` no fija el JDK. |
| Gradle | **8.7** | Solo con el wrapper: `./gradlew`. |
| AGP / Kotlin | 8.5.0 / 2.1.20 | Fijados en `gradle/libs.versions.toml`. |
| `com.km:km-core` en `mavenLocal()` | 0.1.0-SNAPSHOT | Ver [Dependencia del framework](#dependencia-del-framework). |

Configura el SDK en `local.properties` (está en `.gitignore`, no se versiona):

```properties
sdk.dir=/ruta/a/tu/Android/Sdk
```

---

## Construir y testear

```sh
# Compila el APK de debug
./gradlew :app:assembleDebug

# Tests unitarios
./gradlew :app:testDebugUnitTest
```

Hay que pasar el JDK en la invocación, porque una ruta fija en
`gradle.properties` no viaja entre máquinas:

```sh
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17 :app:assembleDebug
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17 :app:testDebugUnitTest
```

Este build **requiere JDK 17**: AGP 8.5.0 y Kotlin 2.1.20 no arrancan con el
JDK 27 que es el `default` de la máquina. Un JDK más nuevo no vale, uno más
antiguo tampoco.

### Estado medido de los tests

`./gradlew :app:testDebugUnitTest` — `exit 0`, **45 / 0 / 0**:

| Clase | Declarados | Ejecutados | Skip | Fallos |
|-------|-----------:|-----------:|-----:|-------:|
| `ExampleUnitTest` | 1 | 1 | 0 | 0 |
| `RelayDataCodecTest` | 22 | 22 | 0 | 0 |
| `RelayClientWireTest` | 13 | 13 | 0 | 0 |
| `RelayClientIdempotencyTest` | 6 | 6 | 0 | 0 |
| `RelayDataResidualPathsTest` | 3 | 3 | 0 | 0 |
| **Total** | **45** | **45** | **0** | **0** |

Cero skips: no hay ningún `@Ignore`. Antes de cada corrida, borra
`app/build/test-results`: si el directorio no existe tras la corrida, la corrida
no ocurrió.

Dos detalles de los unit tests, porque no son opcionales:

- **`org.json` real en el classpath.** El `android.jar` que se usa en JVM unit
  tests tiene `org.json` como stub que lanza `"not mocked"` en cada llamada, así
  que `RelayDataCodec` y `RelayClient` no correrían. Por eso está
  `testImplementation(libs.orgjson)`.
- **`MockWebServer` en la misma versión que `okhttp`.** Se compila contra clases
  internas de `okhttp`; un desfase de versión no falla al compilar, falla en
  runtime con `NoSuchMethodError`.

---

## Dependencia del framework

km-android depende de **un solo artefacto** del framework:

```kotlin
implementation("com.km:km-core:0.1.0-SNAPSHOT")
```

`group` y `version` los declaré en `km-core/build.gradle.kts` (km-core no los
tenía, y sin ellos no se puede consumir como dependencia). Elegí:

| | Valor | Por qué |
|---|-------|---------|
| `group` | `com.km` | Coherente con los paquetes `com.km.*` del framework, y es el grupo que el README de km-framework ya declara como previsto. |
| `version` | `0.1.0-SNAPSHOT` | El proyecto está por debajo de 1.0 (`CHANGELOG.md` de km-framework), y el sufijo `-SNAPSHOT` deja explícito que **no hay ninguna versión publicada**. |

Declarar coordenadas **no** publica nada. Hoy km-core **sí** tiene el plugin
`maven-publish` con destino `mavenLocal()`, pero sigue sin estar en Maven Central:
`0.1.0-SNAPSHOT` solo existe en tu `~/.m2/repository`.

### Provisional: `mavenLocal()`

Como km-core no está publicado en ningún registro, hoy no existe un repositorio
del que resolver `com.km:km-core`. Lo que lo resuelve es `mavenLocal()` en
`settings.gradle.kts`, combinado con publicar km-core antes de compilar:

```sh
# 1. Publicar km-core en mavenLocal (desde el checkout de km-framework)
./gradlew -p ../KeyMessage :km-core:publishToMavenLocal

# 2. Compilar esta app
./gradlew :app:assembleDebug
```

> **Esto es un puente, no el estado final.** Requiere que km-core esté en disco y
> publicado en tu `~/.m2`. Cuando km-core se publique en Maven Central,
> `app/build.gradle.kts` **no cambia** (misma group, misma version); lo que
> desaparece es `mavenLocal()` y `mavenCentral()` pasa a ser la fuente real.

**Verificado:** `com/km/api/KeyMessageCore`, `com/km/auth/AuthVerifier`,
`com/km/crypto/Ed25519Impl`, `com/km/model/Message`, `com/km/node/RelayTransport`,
`com/km/protocol/Transport` y `com/km/storage/MessageStore` están dentro de
`app-debug.apk`. `com/keymessage/core/*` no aparece en ningún dex
(`dexdump | grep -c com/keymessage/core` → `0`).

---

## Cómo firmar una release

**La clave de firma no está en este repositorio, y no debe estarlo.** Una clave
versionada es una fuga: cualquiera que tenga el repositorio podría publicar una
actualizacion firmada con la identidad de esta app, y el usuario instalaría algo
que no es de KemaMada. `.gitignore` bloquea `*.jks`, `*.keystore` y
`keystore.properties`.

`app/build.gradle.kts` crea el `signingConfig` **solo si encuentra la clave**.
Sin ella:

- `:app:assembleDebug` funciona.
- `:app:testDebugUnitTest` funciona.
- `:app:assembleRelease` funciona y produce **`app-release-unsigned.apk`**, que
  Android no instalará hasta que alguien lo firme. Verificado: `exit 0`.

### Pasos para firmar de verdad

1. Genera o recupera la clave y **ponla fuera del árbol**, por ejemplo en
   `~/.keystores/keymessage-release.jks` (`chmod 600`).
2. Crea `keystore.properties` en la raíz de este repositorio — está en
   `.gitignore`, no se versiona:

   ```properties
   storeFile=/home/tu/.keystores/keymessage-release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

   En una CI, pasa lo mismo como propiedades de Gradle en vez de escribir el
   fichero:

   ```sh
   ./gradlew :app:assembleRelease \
     -Pkm.release.storeFile="$KEYSTORE_PATH" \
     -Pkm.release.storePassword="$STORE_PASSWORD" \
     -Pkm.release.keyAlias="$KEY_ALIAS" \
     -Pkm.release.keyPassword="$KEY_PASSWORD"
   ```

3. Comprueba que la configuración se cogió: el build imprime
   `app: firma de release configurada desde <fichero>`. Si imprime
   `app: sin clave de release...`, no se ha firmado.
4. El resultado firmado aparece en `app/build/outputs/apk/release/`.

Nunca subas la clave ni `keystore.properties` a este repositorio ni a ningún
otro.

---

## Estructura

```
.
├── app/                                   módulo Android (único)
│   ├── build.gradle.kts                   namespace, SDK, firma, dependencia de km-core
│   ├── proguard-rules.pro                 vacío a propósito (minify desactivado)
│   └── src/
│       ├── main/java/com/example/keymessage/
│       │   ├── AppContainer.kt            DI manual: core, base de datos, transporte
│       │   ├── KeyMessageApp.kt           Application
│       │   ├── crypto/ data/ model/ util/
│       │   ├── network/                   RelayClient, RelayDataCodec, WebRtcManager, ChatManager, dht/
│       │   ├── storage/room/              entidades, DAOs, implementaciones Room
│       │   └── ui/                        MainActivity, ViewModel, screens/, theme/
│       ├── main/res/                      iconos, temas, strings
│       ├── test/java/...                  5 clases de test unitario (45 tests)
│       └── androidTest/java/...           1 test instrumentado (esqueleto de Android Studio)
├── gradle/libs.versions.toml              catálogo de versiones
├── settings.gradle.kts                    mavenLocal() + include(":app")
├── build.gradle.kts                       plugins declarados, no aplicados
├── gradle.properties                      sin org.gradle.java.home: es ruta local
├── LICENSE                                MIT, Copyright (c) 2026 KemaMada
└── .gitignore                             incluye *.jks, *.keystore, build/
```

40 ficheros `.kt`: 34 en `main`, 5 en `test`, 1 en `androidTest`.

### Nota sobre `gradle/libs.versions.toml`

Gradle no comparte catálogos de versiones entre builds, así que el de
km-android es una **copia** del de km-framework, no un enlace. Si el framework
actualiza una versión, hay que replicarla aquí; si no, los dos proyectos
compilarán contra versiones distintas de la misma biblioteca.

Dos decisiones que conviene no deshacer:

- **Compose va por BOM.** `androidx-compose-bom` constrain `ui`, `material3` e
  `icons` a un único conjunto alineado. Fijar `version.ref` en los aliases
  individuales es lo que dejó antes `ui 1.5.1` conviviendo con `material3 1.1.2`.
- **El plugin `kotlinCompose` se aplica a mano.** Con Kotlin 2.x el compiler de
  Compose va dentro del plugin de Kotlin. Si no se aplica
  `org.jetbrains.kotlin.plugin.compose`, AGP inyecta su valor por defecto
  (compose compiler 1.3.2), que exige Kotlin 1.7.20 y no compila.

`webrtc` apunta a `io.github.webrtc-sdk:android`, el AAR de Android. **No** es
`dev.onvoid.webrtc:webrtc-java`: ese es el build de escritorio (packages
`dev.onvoid.webrtc.*`, natives `linux-x86_64`) y no aporta ninguna clase
`org.webrtc.*`. El nombre de la clave de versión (`webrtc-java`) es un resto del
catálogo de km-framework y no describe lo que resuelve.

### Nota sobre el paquete propio

`namespace` y `applicationId` siguen siendo `com.example.keymessage`. Esta
migración movió el código de repositorio, no la identidad de la app: renombrar
el paquete o el `applicationId` rompería las actualizaciones ya instaladas. Si
algún día se cambia, `AndroidManifest.xml` lleva los `android:name` en forma
relativa (`.KeyMessageApp`, `.ui.MainActivity`) y seguirá resolviendo solo,
porque se resuelven contra el `namespace`.

---

## Renombrado de paquetes: la regla de tres formas

km-core se renombró de `com.keymessage.core.*` a `com.km.*`. Los ficheros de esta
app que lo usaban se migraron con esta tabla:

| Antes | Ahora |
|-------|-------|
| `com.keymessage.core.api` | `com.km.api` |
| `com.keymessage.core.codec` | `com.km.codec` |
| `com.keymessage.core.crypto` | `com.km.crypto` |
| `com.keymessage.core.model` | `com.km.model` |
| `com.keymessage.core.node` | `com.km.node` |
| `com.keymessage.core.protocol` | `com.km.protocol` |
| `com.keymessage.core.storage` | `com.km.storage` |
| `com.keymessage.webrtc` | `com.km.webrtc` |

Los **nombres de tipo no cambian**. Solo los paquetes.

Si algún día vuelve a cambiar un paquete, no basta con un `grep` de puntos. Un
nombre de paquete aparece en el código de **tres formas**:

1. **Puntos** — `package`, `import`, FQN en línea (`com.km.model.Message`).
2. **Barras** — dentro de cadenas, en rutas o en recursos. Invisible para un
   grep de puntos.
3. **Nombres de clase escritos a mano** — `android:name` en el manifiesto,
   `Class.forName`, reflexión, `ProcessBuilder`, valores de recursos,
   `BuildConfig`, `proguard-rules.pro`, ficheros `.pro`/`.xml`/`.json`/
   `.properties`.

Las tres gave zero en este repositorio: no hay ni una aparición de
`com.keymessage` en forma de puntos, ni de `com/keymessage` en forma de barras,
ni un nombre de clase a mano que apunte al paquete antiguo. Los únicos `com.*`
que quedan son el paquete propio `com.example.keymessage`, el grupo Maven
`com.km`, los IDs de plugin y las URIs de espacio de nombres XML.

---

## Pendiente

- **Atribución de software de terceros.** km-framework tiene `NOTICE` y
  `THIRD-PARTY-LICENSES/`. km-android **no**, y debería: sus dependencias
  directas (Room, Compose, AppCompat, Navigation, zxing-android-embedded,
  okhttp, webrtc-sdk, security-crypto, jackson-kotlin) no están documentadas.
  Faltan.
- **Sin `NOTICE` propio** que explique por qué esta app es MIT sin concesión
  explícita de patentes (km-framework sí lo razona en su `CONTRIBUTING.md`).
- **Publicar `com.km:km-core`.** Hoy la build depende de `mavenLocal()`, así que
  un clon limpio necesita primero un `:km-core:publishToMavenLocal` desde el
  checkout del framework. Sin eso, Gradle no encuentra la dependencia.
