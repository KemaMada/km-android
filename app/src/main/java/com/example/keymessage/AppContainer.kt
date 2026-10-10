package com.example.keymessage

import android.app.Application
import androidx.room.Room
import com.example.keymessage.network.RelayClient
import com.example.keymessage.storage.room.database.KeyMessageDatabase
import com.example.keymessage.storage.room.impl.RoomDuplicateStore
import com.example.keymessage.storage.room.impl.RoomMessageStore
import com.example.keymessage.storage.room.impl.RoomOfflineQueue
import com.km.api.KeyMessageCoreImpl
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.MessageState

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

    val transport = RelayClient(
        url = "ws://10.0.2.2:8080/ws",
        localIdentity = localIdentity,
        localPrivateKey = keyPair.privateKey,
        localPublicKey = keyPair.publicKey,
        relayIdentityId = relayIdentityId,
        ed25519 = ed25519
    )

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
        peerPublicKeyResolver = { peerId ->
            peerKeys.signingKeyOf(peerId)?.let { encoded ->
                runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
            }
        }
    )

    init {
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
