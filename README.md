# km-android

**km-android** es el cliente Android de **KeyMessage**: la aplicación que un
usuario instala, con interfaz de usuario, lista de contactos, conversaciones y
código QR.

No es un repositorio de protocolo. La criptografía, la identidad, la
negociación, el ratchet y el transporte viven en el **framework**,
[`KemaMada/KeyMessage`](https://github.com/KemaMada/km-framework) (el directorio se
llama `KeyMessage`; el nombre del módulo y del producto sigue siendo
`km-framework`). Ahí viven también el cliente de escritorio (`km-desktop/`) y el
relé (`km-daemon/`): **el framework es un solo repositorio con varios módulos**,
y el móvil es lo único que se clone aparte. Aquí solo hay lo que hace falta para
*usar* ese protocolo desde un teléfono.

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

## Modelo de roles — DISEÑADO, no implementado entero

> **Estado: DISEÑADO.** El diseño es que no hay servidor privilegiado; lo que hay
> hoy es un subconjunto. Nada de lo que sigue es una afirmación sobre el código
> actual salvo donde se marca.

El modelo previsto es que **cualquier cliente de escritorio puede actuar como nodo
de señalización y como relé**, y que el rol es por sesión, no por identidad. No
hay un servidor al que haya que pedirle permiso ni una lista de operadores.

| Rol | Qué hace | Dónde vive |
|-----|----------|------------|
| **Cliente** | Envía y recibe mensajes. | El escritorio (`km-desktop`) y esta app |
| **Nodo de señalización** | Aloja el intercambio de SDP/ICE para que dos peers abran la `PeerConnection`. | `km-daemon`, con `signalingEnabled: true` |
| **Relay** | Mueve `MESSAGE` y `ACK` entre peers que no se alcanzan, y los mantiene en línea aunque el otro esté dormido. | `km-daemon`, con `relayEnabled: true` |

Los dos últimos roles son **el mismo proceso**: `km-daemon` es a la vez nodo de
señalización y relé, con los dos caminos encendibles por separado. Eso es lo que
hace que «subir» un cliente a relay sea arrancar un proceso, y no desplegar un
servicio aparte: cualquiera que clone el repo del framework puede ser el relé
para sus propios contactos.

**El móvil es un cliente fino**, y en este diseño eso es una decisión, no una
limitación. Aquí no hay ni una línea de lógica criptográfica propia ni una copia
de `km-core`: esta app implementa las interfaces de almacenamiento sobre Room y
los contratos de transporte sobre WebSocket, y todo lo demás lo pide a
`km-core`. Puede subir a signalling/relé más adelante, y **el diseño no depende
de que eso ocurra**: el sistema se sostiene con los escritorios.

Qué hay hoy:

| Pieza | Estado |
|-------|--------|
| Cliente móvil que se autentica contra el relé, envía y recibe por WebSocket | **IMPLEMENTADO** a nivel de wire (`RelayClientWireTest`), **sin** E2E contra un relé real |
| `km-daemon` con los caminos de datos, señalización y control | **IMPLEMENTADO** |
| Señalización SDP/ICE en el cable | **DISEÑADO.** Existe como extensión explícita con `kind` ∈ {`sdp_offer`, `sdp_answer`, `ice_candidate`, `contact_exchange`}; el catálogo normativo no define un tipo `SIGNAL` y esa divergencia está abierta |
| El móvil subiendo a signalling/relé | **NO EMPEZADO**, y fuera del camino crítico |
| QR de identidad: generar y escanar | **IMPLEMENTADO** (`MainActivity.kt`, ZXing, formato `nombre\|clavePública`). Sin nombre, esta app usa `"User"` como reserva |
| Fichero de contacto `.km` (ContactBundle firmado + nombre visible) | **DISEÑADO, NO EMPEZADO.** `ContactBundle` sí existe en `km-core`, pero no hay importación ni exportación |
| Cifrado del mensaje en el camino real | **NO EMPEZADO.** `SecureFrame` y el Double Ratchet existen y están testeados en `km-core`, pero **no están cableados**: lo que va por el cable va **firmado, no cifrado** |

La última fila es la que más importa y la que más fácil de pasar por alto: en el
framework, el camino de relé y `ACK` funciona de extremo a extremo entre dos
escritorios, pero la carga del mensaje **viaja en claro**. Firmar un mensaje no es
cifrarlo.

---

## Requisitos

### Para compilar

| Requisito | Versión | Fuente |
|-----------|---------|--------|
| **JDK para ejecutar Gradle** | **17** | Ver la nota de abajo: Gradle **8.7** rechaza un JDK 27, que es el `default` de esta máquina. |
| Gradle | **8.7** | `gradle/wrapper/gradle-wrapper.properties`. **Solo el wrapper**: `./gradlew`. |
| Android SDK | platform **android-34** | `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`. |
| Build tools | 34.x o superior | |
| AGP | 8.5.0 | `gradle/libs.versions.toml` |
| Kotlin | **2.2.10** | Idem. Debe seguir a la de `km-core`: es el motivo de que Room 2.7.x se procese con **KSP2** y no con KAPT. |
| `com.km:km-core` en `mavenLocal()` | 0.1.0-SNAPSHOT | Ver [Dependencia del framework](#dependencia-del-framework). Publicar primero, desde el checkout del framework. |
| Espacio libre | **~5 GB** | Cachés de Gradle (`~/.gradle`), SDK de Android y salidas de build. |
| Red de salida | HTTPS | `services.gradle.org`, `dl.google.com` (SDK y AGP), Maven Central. |

**El JDK 17 es obligatorio y no negociable en este build.** AGP 8.5.0 y
Kotlin 2.2.10 no arrancan con el JDK 27 que es el `default` de esta máquina, y
Gradle 8.7 **rechaza** un `-Dorg.gradle.java.home` que apunte a 27. Un JDK más
nuevo no vale; uno más antiguo tampoco.

```properties
# local.properties, en la raíz del repo (está en .gitignore, no se versiona)
sdk.dir=/ruta/a/tu/Android/Sdk
```

> Ojo con `local.properties`: en este checkout hay uno regenerado por Android
> Studio cuyo comentario dice `compileSdk = 36`, pero el build declara
> `compileSdk = 34`. Instala la plataforma **android-34**, que es la que pide
> `app/build.gradle.kts`. En un clon limpio no hay ese fichero.

Seleccionar el JDK, de las dos formas que funcionan:

```sh
# Opción A: exportar el JDK una vez por terminal
export JAVA_HOME=/home/kemamada/jdk-17.0.2
./gradlew :app:assembleDebug

# Opción B: pasar el JDK solo en esa invocación
./gradlew -Dorg.gradle.java.home=/home/kemamada/jdk-17.0.2 :app:assembleDebug
```

`gradle.properties` **no** fija `org.gradle.java.home`, y es a propósito: es una
ruta absoluta que no viaja entre máquinas y rompería el clon de cualquiera que
tenga el JDK en otro sitio. Para no pasar `-D` en cada comando, ponla en
`~/.gradle/gradle.properties`, que tiene precedencia sobre el fichero del repo.

### Para instalar en un dispositivo

| Requisito | Detalle |
|-----------|---------|
| `adb` | Del SDK o de `platform-tools`. Tiene que estar en el `PATH`. |
| Dispositivo | Android 8.0 (API 26) o superior, con **depuración USB** activada, o un emulador. |
| Puerto | 5555/TCP en local, o el que asigne el dispositivo. |

---

## Inicio rápido

De un clon limpio a un APK instalado en un teléfono.

```sh
# 1. Publicar km-core en mavenLocal. NO es opcional: esta app depende de
#    com.km:km-core:0.1.0-SNAPSHOT, y hoy eso solo existe en tu ~/.m2.
#    Se hace desde el checkout del framework, NO desde aquí.
git clone https://github.com/KemaMada/km-framework.git /tmp/KeyMessage
/tmp/KeyMessage/gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
                        :km-core:publishToMavenLocal

# 2. Configurar el SDK, si el clon no lo tiene ya
echo "sdk.dir=/ruta/a/tu/Android/Sdk" > local.properties

# 3. Compilar el APK de debug
./gradlew -Dorg.gradle.java.home=/home/kemamada/jdk-17.0.2 :app:assembleDebug

# 4. Instalarlo
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK sale en `app/build/outputs/apk/debug/app-debug.apk`.

> **Los dos JDK son distintos y no se pueden cambiar.** El del **framework** es el
> 27, porque su wrapper es Gradle 9.5.0. El de **esta** app es el 17, porque su
> wrapper es Gradle 8.7 y el AGP no arranca con 27. Es el punto donde más se
> pierden tiempo al montar esto por primera vez.

Para probar contra un `km-daemon` propio, `AppContainer.kt` apunta hoy a
`ws://10.0.2.2:8080/ws`, que es `localhost:8080` visto desde el emulador. En un
teléfono físico hay que cambiarlo por la IP del relé: `adb reverse tcp:8080
tcp:8080` es el atajo que funciona sin tocar el código.

Tests:

```sh
./gradlew -Dorg.gradle.java.home=/home/kemamada/jdk-17.0.2 :app:testDebugUnitTest
```

---

## Construir y testear

```sh
# Compila el APK de debug
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17 :app:assembleDebug

# Tests unitarios
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17 :app:testDebugUnitTest
```

El `-Dorg.gradle.java.home` es necesario en la práctica, porque una ruta fija en
`gradle.properties` no viaja entre máquinas. Si lo exportas en `JAVA_HOME` una vez
por terminal, ya no hace falta pasarlo.

Este build **requiere JDK 17**: AGP 8.5.0 y Kotlin 2.2.10 no arrancan con el
JDK 27 que es el `default` de esta máquina, y Gradle 8.7 rechaza un
`-Dorg.gradle.java.home` que apunte a 27. Un JDK más nuevo no vale, uno más
antiguo tampoco.

### Estado medido de los tests

`./gradlew :app:testDebugUnitTest` — `exit 0`, **57 / 0 / 0**. Medido el
10-oct-2026 con JDK 17 (`-Dorg.gradle.java.home=/home/kemamada/jdk-17.0.2`),
borrando `app/build/test-results` antes de la corrida.

| Clase | Declarados | Ejecutados | Skip | Fallos |
|-------|-----------:|-----------:|-----:|-------:|
| `ExampleUnitTest` | 1 | 1 | 0 | 0 |
| `RelayDataCodecTest` | 22 | 22 | 0 | 0 |
| `RelayClientWireTest` | 13 | 13 | 0 | 0 |
| `RelayClientAckTest` | 12 | 12 | 0 | 0 |
| `RelayClientIdempotencyTest` | 6 | 6 | 0 | 0 |
| `RelayDataResidualPathsTest` | 3 | 3 | 0 | 0 |
| **Total** | **57** | **57** | **0** | **0** |

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
│       ├── test/java/...                  6 clases de test unitario
│       └── androidTest/java/...           1 test instrumentado (esqueleto de Android Studio)
├── gradle/libs.versions.toml              catálogo de versiones
├── settings.gradle.kts                    mavenLocal() + include(":app")
├── build.gradle.kts                       plugins declarados, no aplicados
├── gradle.properties                      sin org.gradle.java.home: es ruta local
├── local.properties                       sdk.dir; no se versiona
├── LICENSE                                MIT, Copyright (c) 2026 KemaMada
└── .gitignore                             incluye *.jks, *.keystore, build/
```

44 ficheros `.kt`: 37 en `main`, 6 en `test`, 1 en `androidTest`.

### Nota sobre `gradle/libs.versions.toml`

Gradle no comparte catálogos de versiones entre builds, así que el de
km-android es una **copia** del de km-framework, no un enlace. Si el framework
actualiza una versión, hay que replicarla aquí; si no, los dos proyectos
compilarán contra versiones distintas de la misma biblioteca.

**Ojo con Kotlin: aquí va en 2.2.10, y tiene que seguir a `km-core`.** Es la
dependencia más fácil de romper de todo este build, y el fallo que produce no
dice nada de Kotlin:

| | Valor | Por qué |
|---|-------|---------|
| `kotlin` | **2.2.10** | `km-core` compila con 2.2.10 y emite metadatos de versión 2.2.0. Con 2.1.20 aquí, el compilador de Room falla al leer las clases del framework con `Provided Metadata instance has version 2.2.0, while maximum supported version is 2.1.0`. |
| `room` | **2.7.2** | Es el suelo que lee metadatos de Kotlin 2.2. 2.6.1 y 2.7.0-alpha02 siguen capeando en 2.1.0 a través de su `kotlinx-metadata-jvm` empaquetado. |
| `ksp` | **2.2.10-2.0.2** | KSP se versiona contra el Kotlin por su propio plugin: aplicar `2.2.10-2.0.2` sobre Kotlin 2.1.20 falla con `is too new for kotlin-2.1.20`. La línea `-2.0.x` es KSP2. |

Y de ahí una cuarta decisión, que no es una versión sino una elección de
procesador: **Room se procesa con KSP2, no con KAPT.** KAPT pasa por el mismo
`kotlinx-metadata-jvm` empaquetado que capeaba a 2.1.0, así que con Room 2.7.x es
un callejón sin salida; KSP2 es el camino soportado para Room sobre Kotlin 2.x.
Esto viene con un coste: Room 2.7.x arrastra BouncyCastle y `jspecify`, y los dos
aportan `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, así que `packaging.resources`
necesita excluirlo o el paso de empaquetado aborta con `Duplicate resources`.

Dos decisiones más que conviene no deshacer:

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
- **El `local.properties` de este checkout confunde.** No está versionado
  (`local.properties` está en `.gitignore`), pero el que hay dice
  `compileSdk = 36` cuando `app/build.gradle.kts` declara `compileSdk = 34`.
  Quien lo lea se instala la plataforma equivocada. No afecta a un clon limpio,
  que no trae ese fichero.
- **La carga del mensaje viaja en claro.** `SecureFrame` y el Double Ratchet
  existen y están testeados en `km-core`, pero no están cableados al transporte.
  Ver [Modelo de roles](#modelo-de-roles--diseñado-no-implementado-entero).
