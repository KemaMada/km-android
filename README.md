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
- **No cubre la integración real.** Los 3 tests de `RelayClientIntegrationTest`
  están marcados `@Ignore` ("Requires debugging of in-process WebSocket mock
  relay"): se compilan y se cuentan, pero no se ejecutan.

---

## Requisitos

| Requisito | Versión | Nota |
|-----------|---------|------|
| Android SDK | platform **android-36** | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 28`. |
| Build tools | 36.x | |
| JDK para ejecutar Gradle | 17 o superior | El toolchain 11 de `km-core` lo descarga `foojay-resolver-convention` si falta. |
| Gradle | **9.5.0** | Solo con el wrapper: `./gradlew`. |
| AGP / Kotlin | 9.3.1 / 2.2.10 | Fijados en `gradle/libs.versions.toml`. |
| Checkout hermano de km-framework | — | Ver [Dependencia del framework](#dependencia-del-framework). |

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

En este entorno hay que fijar el JDK en la invocación:

```sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk :app:assembleDebug
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk :app:testDebugUnitTest
```

`gradle.properties` **no** fija `org.gradle.java.home`: es una ruta específica de
cada máquina y no debe viajar en el repositorio.

### Estado medido de los tests

`./gradlew :app:testDebugUnitTest` — `exit 0`:

| Clase | Declarados | Ejecutados | Skip | Fallos |
|-------|-----------:|-----------:|-----:|-------:|
| `ExampleUnitTest` | 1 | 1 | 0 | 0 |
| `RelayClientIdempotencyTest` | 6 | 6 | 0 | 0 |
| `RelayClientIntegrationTest` | 3 | 0 | 3 | 0 |
| **Total** | **10** | **7** | **3** | **0** |

Los 3 skips son los `@Ignore` de arriba, no fallos. Antes de cada corrida,
borra `app/build/test-results`: si el directorio no existe tras la corrida, la
corrida no ocurrió.

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

Declarar coordenadas **no** publica nada: km-core sigue sin plugin
`maven-publish` y sin repositorio.

### Provisional: composite build

Como km-core no está publicado, hoy no existe un repositorio del que resolver
`com.km:km-core`. Lo que lo resuelve es esto, en `settings.gradle.kts`:

```kotlin
includeBuild("../KeyMessage")   // el checkout hermano de km-framework
```

Gradle compila km-core desde el código fuente y **sustituye** la dependencia
`com.km:km-core:0.1.0-SNAPSHOT` por el proyecto `:km-core` de ese checkout.

> **Esto es un puente, no el estado final.** Requiere tener km-framework en disco
> y a la misma altura. Cuando km-core se publique, `app/build.gradle.kts` **no
> cambia** (misma group, misma version); lo que desaparece es el `includeBuild`
> y `mavenCentral()` pasa a ser la fuente real del artefacto. Si tu clone local
> de km-framework se llama distinto, ajusta la ruta del `includeBuild`.

> En este entorno el checkout de km-framework se llama `KeyMessage` (en GitHub
> el repositorio se llama `km-framework`), por eso la ruta es `../KeyMessage`.

**Verificado:** con el puente activo, `com/km/api/KeyMessageCore`,
`com/km/crypto/Ed25519Impl`, `com/km/model/Message`, `com/km/node/RelayTransport`,
`com/km/protocol/Transport` y `com/km/storage/MessageStore` están dentro de
`app-debug.apk`. `com/keymessage/core/*` no aparece en ningún dex.

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
│       │   ├── network/                   RelayClient, WebRtcManager, ChatManager, dht/
│       │   ├── storage/room/              entidades, DAOs, implementaciones Room
│       │   └── ui/                        MainActivity, ViewModel, screens/, theme/
│       ├── main/res/                      iconos, temas, strings
│       ├── test/java/...                  3 clases de test unitario
│       └── androidTest/java/...           1 test instrumentado (esqueleto de Android Studio)
├── gradle/libs.versions.toml              catálogo de versiones (copiado de km-framework)
├── settings.gradle.kts                    includeBuild del framework + include(":app")
├── build.gradle.kts                       plugins declarados, no aplicados
├── gradle.properties                      sin org.gradle.java.home: es ruta local
├── LICENSE                                MIT, Copyright (c) 2026 KemaMada
└── .gitignore                             incluye *.jks, *.keystore, build/
```

39 ficheros `.kt`: 35 en `main`, 3 en `test`, 1 en `androidTest`.

### Nota sobre `gradle/libs.versions.toml`

Es una **copia** del de km-framework, no un enlace: Gradle no comparte catálogos
de versiones entre builds. Las entradas que km-android no usa (`bcprov`,
`webrtc-java`, `kotlin-jvm`) siguen ahí. Si el framework actualiza una versión,
hay que replicarla aquí; si no, los dos proyectos compilarán contra versiones
distintas de la misma biblioteca.

### Nota sobre el paquete propio

`namespace` y `applicationId` siguen siendo `com.example.keymessage`. Esta
migración movió el código de repositorio, no la identidad de la app: renombrar
el paquete o el `applicationId` rompería las actualizaciones ya instaladas. Si
algún día se cambia, `AndroidManifest.xml` lleva los `android:name` en forma
relativa (`.KeyMessageApp`, `.ui.MainActivity`) y seguirá resolviendo solo,
porque se resuelven contra el `namespace`.

---

## Renombrado de paquetes: la regla de tres formas

km-core se renombró de `com.keymessage.core.*` a `com.km.*`. Los 13 ficheros de
esta app que lo usaban se migraron con esta tabla:

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
  directas (Room, Compose, AppCompat, Material, Navigation, zxing,
  zxing-android-embedded, okhttp, webrtc-sdk, security-crypto) no están
  documentadas. Faltan.
- **Subir el proyecto al remoto.** El remoto `origin` apunta a
  `https://github.com/KemaMada/km-android.git` y **no** se ha hecho `push`.
- **Sin `NOTICE` propio** que explique por qué esta app es MIT sin concesión
  explícita de patentes (km-framework sí lo razona en su `CONTRIBUTING.md`).
