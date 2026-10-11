package com.example.keymessage

import android.app.Application
import androidx.room.Room
import com.example.keymessage.network.RelayClient
import com.example.keymessage.storage.room.database.KeyMessageDatabase
import com.example.keymessage.storage.room.impl.RoomDuplicateStore
import com.example.keymessage.storage.room.impl.RoomMessageStore
import com.example.keymessage.storage.room.impl.RoomOfflineQueue
import com.example.keymessage.util.LogBuffer
import com.km.api.KeyMessageCoreImpl
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.MessageState
import com.km.protocol.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(application: Application) {

    private val ed25519 = Ed25519Impl()
    private val keyPair: KeyPair = loadOrCreateKeyPair(application)

    // KM-ID-0001 seccion 6: identityId = SHA-256("KM-ID-IDENTITY" || pubKey),
    // 32 bytes -> 64 hex. La derivacion canonica vive en km-core y la usan
    // daemon, desktop y la referencia normativa; truncando a 20 bytes se
    // obtenian 40 hex, que AuthVerifier rechaza en la capa V1
    // (INVALID_IDENTITY) y con los que ni se puede montar el transcript de
    // autenticacion (16 + 8 + 64 + 64 = 152 bytes).
    val localIdentity = IdentityId(bytesToHex(KmIds.identityId(keyPair.publicKey)))

    val database: KeyMessageDatabase = Room.databaseBuilder(
        application,
        KeyMessageDatabase::class.java,
        "keymessage.db"
    ).build()

    val messageStore = RoomMessageStore(database.messageDao())
    val offlineQueue = RoomOfflineQueue(database.offlineQueueDao())
    val duplicateStore = RoomDuplicateStore(database.duplicateDao())

    // Identidad del rele, en el MISMO espacio de identificadores que
    // localIdentity: 64 hex. Un literal de 40 hex aqui hace que el transcript
    // de autenticacion no cumpla 16 + 8 + 64 + 64.
    val relayIdentityId = IdentityId("cadd3afc301de94d9161ce7834b8c6b6396d49dd529a830269dfcc09c4cda590")

    /**
     * Claves de FIRMA de los peers conocidos, por `identityId`.
     *
     * Es la fuente del `peerPublicKeyResolver` del core, y por tanto lo que
     * decide si un `ACK` entrante se procesa o se descarta. El valor guardado es
     * Base64 estándar porque es lo que ya produce `Contact.publicKey` y lo que se
     * puede pegar desde la UI sin decodificar nada.
     *
     * SE LEE DE `SharedPreferences` y no de Room a proposito: el registro es de
     * claves publicas de terceros, no de mensajes, y su volumen es de unos
     * cuantos contactos. Una tabla mas exigiria una migracion de esquema para
     * guardar lo mismo.
     *
     * Lo que NO hace, y no puede, es descubrir claves: un peer que no se ha
     * registrado no se autentica, y su `ACK` se descarta con la documentacion
     * que dice `AckManagerImpl`. Descubrirla por X3DH es Checkpoint C.
     */
    private val peerKeys = PeerSigningKeys(application.getSharedPreferences(PeerSigningKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE))

    /** Registra la clave de firma de un peer. Base64 estándar, 32 B. */
    fun registerPeerKey(peerId: IdentityId, base64SigningKey: String) {
        peerKeys.put(peerId, base64SigningKey)
    }

    /** Identidades cuya clave de firma se conoce. */
    fun peersWithKnownKey(): Set<IdentityId> = peerKeys.known()

    // E3/1.1. La URL sale de RelayEndpoint, no de un literal: el default
    // (emulador) vive en BuildConfig y un telefono fisico lo retargetea en
    // runtime con `settings put global km_relay_ws_url` sin recompilar.
    // Se resuelve UNA vez, al construir el contenedor: cambiarla exige
    // recrear el transporte.
    val relayUrl: String = RelayEndpoint.resolve(application.contentResolver)

    val transport = RelayClient(
        url = relayUrl,
        localIdentity = localIdentity,
        localPrivateKey = keyPair.privateKey,
        localPublicKey = keyPair.publicKey,
        relayIdentityId = relayIdentityId,
        ed25519 = ed25519
    )

    /**
     * Estado de conexion del transporte, EN VIVO. R4 / 1.2.
     *
     * Es la unica fuente de verdad que la UI puede consultar: `RelayClient` ya
     * emite cada transicion (`CONNECTING`, `AUTHENTICATING`, `ONLINE`,
     * `RECONNECTING`, `DISCONNECTED`) por `onStateChange`, y se siembra con
     * [RelayClient.connectionState] para no perder la que ya hubiera antes de
     * suscribirse. Sin esto, la UI solo puede mostrar un DISCONNECTED estatico
     * aunque el transporte este ONLINE (D3).
     *
     * El handler corre en el hilo del transporte; escribir `StateFlow.value`
     * desde cualquier hilo es seguro y no bloquea.
     */
    val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(transport.connectionState()).also { flow ->
            transport.onStateChange { state -> flow.value = state }
        }.asStateFlow()

    val core = KeyMessageCoreImpl(
        localIdentity = localIdentity,
        localPrivateKey = keyPair.privateKey,
        transport = transport,
        ed25519 = ed25519,
        messageStore = messageStore,
        offlineQueue = offlineQueue,
        duplicateStore = duplicateStore,
        // F2/D3. Sin esto el core recibe `{ null }`: `AckManagerImpl` no puede
        // verificar ninguna firma y devuelve `success` sin procesar, asi que
        // cada `ACK` que llega se pierde en silencio y el emisor nunca ve
        // confirmarse un mensaje.
        //
        // La clave de cada peer viene del registro de contactos, NO se deriva ni
        // se inventa: una clave adivinada autentica contra la identidad
        // equivocada, que es peor que no autenticar nada.
        //
        // 1.4/R2: un peer sin clave registrada no se descarta en silencio;
        // resolvePeerSigningKey deja constancia en el log (solo el IdentityId,
        // nunca payload ni plaintext).
        peerPublicKeyResolver = { peerId ->
            resolvePeerSigningKey(peerId, peerKeys.signingKeyOf(peerId))
        }
    )

    init {
        // Deja constancia del endpoint efectivo (override de runtime o default):
        // es lo que un test adb lee para confirmar S3a/S3b sin ambiguedad.
        LogBuffer.log("RELAY", "endpoint resuelto: $relayUrl")

        // NI6: STORED is custody confirmation, never authorization to delete the local copy.
        transport.setStoredHandler { receipt ->
            // Keep the local durable copy; optionally notify the UI via a state callback later.
        }

        // RELAY_EXPIRED: the relay dropped the message; mark it EXPIRED locally (KM-0004 §14.4).
        transport.setRelayExpiredHandler { notice ->
            messageStore.updateState(notice.originalMessageId, MessageState.EXPIRED, null)
        }
    }

    private fun loadOrCreateKeyPair(application: Application): KeyPair {
        val prefs = application.getSharedPreferences("km_keys", android.content.Context.MODE_PRIVATE)
        val pubKey = prefs.getString("public_key", null)
        val privKey = prefs.getString("private_key", null)

        if (pubKey != null && privKey != null) {
            return KeyPair(
                java.util.Base64.getDecoder().decode(pubKey),
                java.util.Base64.getDecoder().decode(privKey)
            )
        }

        val pair = ed25519.generateKeyPair()
        prefs.edit()
            .putString("public_key", java.util.Base64.getEncoder().encodeToString(pair.publicKey))
            .putString("private_key", java.util.Base64.getEncoder().encodeToString(pair.privateKey))
            .apply()
        return pair
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Etiqueta de `LogBuffer` para el registro de claves de firma de peers. */
internal const val PEER_KEY_LOG_TAG: String = "PEER_KEY"

/**
 * Resuelve la clave publica de firma de un peer a partir del Base64 que la app
 * tiene registrado. R2 / 1.4.
 *
 * POR QUE ESTA FUERA DE [AppContainer]
 *
 * Para poder probar el camino "peer sin clave" sin levantar Android: la app
 * entrega el valor de `PeerSigningKeys.signingKeyOf`, y la funcion decide. Al
 * ser `internal` y de nivel de archivo, el test unitario la llama directamente
 * con un `log` inyectable.
 *
 * QUE HACE CUANDO NO HAY CLAVE
 *
 * Loguea EXPLICITAMENTE que ese peer no tiene clave de firma y que su `ACK`
 * entrante se descartara, y devuelve `null`. Antes esto ocurria en silencio
 * (`AckManagerImpl` recibe `{ null }`, devuelve `success` sin verificar y el
 * `ACK` se pierde), asi que el emisor se quedaba esperando una confirmacion que
 * ya habia llegado. Con el log, la causa es visible en `LogBuffer` / adb.
 *
 * QUE NUNCA LOGUEA
 *
 * Solo el `IdentityId` del peer, que ya es publico. Ni el payload, ni el
 * plaintext, ni la propia clave (que tambien es publica, pero no hace falta).
 *
 * @param storedBase64 clave registrada, o `null` si el peer no tiene.
 * @param log destino del aviso; por defecto `LogBuffer`, inyectable en tests.
 */
internal fun resolvePeerSigningKey(
    peerId: IdentityId,
    storedBase64: String?,
    log: (String, String) -> Unit = { tag, msg -> LogBuffer.log(tag, msg) },
): ByteArray? {
    if (storedBase64 == null) {
        log(
            PEER_KEY_LOG_TAG,
            "sin clave de firma registrada para ${peerId.value}: el ACK entrante se descartara",
        )
        return null
    }
    val decoded = runCatching { java.util.Base64.getDecoder().decode(storedBase64) }.getOrNull()
    if (decoded == null) {
        log(
            PEER_KEY_LOG_TAG,
            "la clave de firma registrada para ${peerId.value} no es Base64 valido: el ACK entrante se descartara",
        )
    }
    return decoded
}
