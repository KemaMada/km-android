package com.example.keymessage.network

import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.protocol.ConnectionState
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El canal `ACK` de `RelayClient`: `sendAck` hacia fuera, y la recepcion de un
 * `ACK` de seis campos hacia dentro.
 *
 * POR QUE ESTE TEST EXISTIA EN ESTA FORMA ANTES Y AHORA NO PUEDE EXISTIR IGUAL
 *
 * Antes, un `ACK` entrante se repartia con `deliverPayload`, igual que un
 * `MESSAGE`: se buscaba el campo `data`. El `ACK` de §11.2 no tiene `data` —sus
 * seis campos son texto—, asi que TODOS los `ACK` entrantes se descartaban en
 * silencio y ningun handler los veia jamas. El test que lo fijaba ("un `ACK`
 * entrante entrega los bytes exactos de la carga") estaba probando un contrato
 * que ningun otro extremo habla. Este fija el contrato que si se habla: el del
 * escritorio, byte a byte.
 *
 * Y `sendAck` no existia. Sin el, `Transport` no se implementaba y el modulo no
 * compilaba; con el, el `ACK` sale por un frame `ACK` y no por un `MESSAGE`
 * disfrazado, que es lo que hacia que cada `ACK` generase otro `ACK` en el otro
 * extremo.
 *
 * EL `originalMessageId` NO SE ACUÑA AQUI
 *
 * Viaja el del `Ack` que firmo el receptor, y eso es lo que empareja el `ACK`
 * con el registro del emisor. Un identificador nuevo aqui dejaria la entrega sin
 * poder confirmarse sin que nada fallara de forma visible, asi que el test lo
 * compara con el del `Ack` de partida y no solo con "algo que parece un UUID".
 */
class RelayClientAckTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    private val ed25519 = Ed25519Impl()
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
    private val bobId = IdentityId("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private val relayId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8")

    private val challengeNonce = ByteArray(16) { (it * 31 + 7).toByte() }
    private val challengeTimestamp = 1_790_000_000_000L

    private val framesFromClient = LinkedBlockingQueue<String>()
    private val receivedAcks = LinkedBlockingQueue<ByteArray>()
    private val receivedMessages = LinkedBlockingQueue<ByteArray>()
    private val online = CountDownLatch(1)

    @Volatile
    private var relaySideSocket: WebSocket? = null

    private val relayStub = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            relaySideSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            framesFromClient.add(text)
            when (JSONObject(text).optString("type")) {
                "AUTH_REQUEST" -> webSocket.send(authChallenge())
                "AUTH_RESPONSE" -> webSocket.send(authOk())
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    private fun authChallenge(): String = JSONObject().apply {
        put("type", "AUTH_CHALLENGE")
        // task 1.1: el challenge SI lleva dialecto (string); el cliente lo exige.
        put("protocolVersion", "2.0")
        put("messageId", UUID.randomUUID().toString())
        put("timestamp", challengeTimestamp)
        put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(challengeNonce))
        put("responderIdentityId", relayId.value)
    }.toString()

    private fun authOk(): String = JSONObject().apply {
        put("type", "AUTH_OK")
        put("messageId", UUID.randomUUID().toString())
        put("timestamp", challengeTimestamp + 2)
        put("protocolVersion", "2.0")
        put("initiatorIdentityId", aliceId.value)
        put("responderIdentityId", relayId.value)
        put("sessionId", UUID.randomUUID().toString())
        put(
            "serverSignature",
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(64) { (it * 5 + 1).toByte() }),
        )
    }.toString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(relayStub))

        val keyPair = ed25519.generateKeyPair()
        client = RelayClient(
            url = server.url("/ws").toString(),
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            pinnedRelayIdentityId = relayId.value,
            ed25519 = ed25519,
            maxReconnectDelayMs = 500,
        )
        client.onStateChange { state -> if (state == ConnectionState.ONLINE) online.countDown() }
        client.setMessageHandler { receivedMessages.add(it) }
        client.setAckHandler { receivedAcks.add(it) }
    }

    @After
    fun tearDown() {
        client.disconnect()
        server.shutdown()
    }

    private fun connectOnline() {
        client.connect().getOrThrow()
        assertTrue("el cliente no llego a ONLINE", online.await(10, TimeUnit.SECONDS))
    }

    private fun sendToClient(frame: String) {
        val socket = relaySideSocket ?: throw AssertionError("no hay WebSocket abierto")
        socket.send(frame)
    }

    private fun awaitFrame(type: String, timeoutSeconds: Long = 10): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val frame = framesFromClient.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val json = JSONObject(frame)
            if (json.optString("type") == type) return json
        }
        throw AssertionError("llego el limite de tiempo esperando un frame de tipo $type")
    }

    /** Un `ACK` firmado de verdad por Bob, en el vocabulario de `km-core`. */
    private fun signedAck(
        originalMessageId: MessageId = MessageId(UUID.randomUUID()),
        status: AckStatus = AckStatus.DELIVERED,
        timestamp: Long = 1_790_000_000_000,
    ): ByteArray = JsonEncoder().encodeAck(
        Ack(
            from = bobId,
            to = aliceId,
            originalMessageId = originalMessageId,
            status = status,
            timestamp = timestamp,
            signature = ByteArray(64) { (it * 17 + 3).toByte() },
        )
    ).getOrThrow()

    /** El mismo `ACK` puesto en el cable con los seis campos de §11.2. */
    private fun ackFrame(ack: Ack): String = RelayDataCodec.encodeAck(ack)

    private fun decode(bytes: ByteArray): Ack = JsonDecoder().decodeAck(bytes).getOrThrow()

    // =====================================================================
    // Ida: sendAck()
    // =====================================================================

    @Test
    fun `sendAck sale por un frame ACK y no por un MESSAGE`() {
        connectOnline()
        val ack = decode(signedAck())

        client.sendAck(signedAck(originalMessageId = ack.originalMessageId)).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_ACK)

        assertEquals("ACK", frame.getString("type"))
        // Y el `MESSAGE` no sale: si saliera, el otro extremo lo trataria como
        // un mensaje entrante MAS y responderia con otro `ACK`, y el ciclo se
        // cierra solo.
        assertNull(
            "un ACK no puede salir por el canal MESSAGE",
            framesFromClient.poll(400, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `sendAck conserva el originalMessageId del Ack firmado`() {
        connectOnline()
        val original = MessageId(UUID.randomUUID())
        val ack = decode(signedAck(originalMessageId = original))

        client.sendAck(signedAck(originalMessageId = original)).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_ACK)

        // Es el identificador del mensaje que CONFIRMA. Uno nuevo aqui haria que
        // el emisor no encontrara nada contra lo que emparejar.
        assertEquals(original.value.toString(), frame.getString("originalMessageId"))
        assertEquals(
            "y es el mismo que traia el Ack de km-core",
            ack.originalMessageId.value.toString(),
            frame.getString("originalMessageId"),
        )

        // Y no se acuña uno por envio: dos ACKs de dos mensajes distintos
        // citan identificadores distintos. Un "se conserva" que en realidad
        // cumple porque siempre sale el mismo valor no estaria probando nada.
        val otro = MessageId(UUID.randomUUID())
        client.sendAck(signedAck(originalMessageId = otro)).getOrThrow()
        val segundo = awaitFrame(RelayDataCodec.TYPE_ACK)

        assertNotEquals(
            "dos mensajes distintos no pueden confirmar con el mismo identificador",
            frame.getString("originalMessageId"),
            segundo.getString("originalMessageId"),
        )
        assertEquals(otro.value.toString(), segundo.getString("originalMessageId"))
    }

    @Test
    fun `sendAck escribe los seis campos de la seccion 11_2 y ningun messageId`() {
        connectOnline()
        val original = MessageId(UUID.randomUUID())
        val ack = decode(signedAck(originalMessageId = original))

        client.sendAck(signedAck(originalMessageId = original)).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_ACK)

        assertEquals(bobId.value, frame.getString("from"))
        assertEquals(aliceId.value, frame.getString("to"))
        assertEquals("DELIVERED", frame.getString("status"))
        assertEquals(1_790_000_000_000L, frame.getLong("timestamp"))
        assertTrue("la firma viaja", frame.has("signature"))
        // El `ACK` no lleva `messageId` propio: su unico identificador es el
        // original, y un segundo sitio donde acuñar uno seria un segundo sitio
        // donde equivocarse.
        assertTrue("el ACK no lleva messageId propio", !frame.has("messageId"))
        // Y no lleva `data`: ese es el campo del `MESSAGE`.
        assertTrue("el ACK no lleva data", !frame.has("data"))
    }

    @Test
    fun `sendAck con la sesion caida falla y NO se encola`() {
        // Sin conectar, `send` encola. Un `ACK` no: no hay a quien reenviarlo y
        // su valor caduca cuando el emisor agota los reintentos.
        val original = MessageId(UUID.randomUUID())
        val result = client.sendAck(signedAck(originalMessageId = original))

        assertTrue("sendAck sin conexion debe fallar", result.isFailure)
        assertTrue("y no puede haber salido nada", framesFromClient.isEmpty())
    }

    // =====================================================================
    // Vuelta: la recepcion de un ACK
    // =====================================================================

    @Test
    fun `un ACK entrante de seis campos llega al handler de ACK`() {
        connectOnline()
        val original = MessageId(UUID.randomUUID())
        val ack = decode(signedAck(originalMessageId = original))

        sendToClient(ackFrame(ack))

        val delivered = receivedAcks.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("el ACK no llego a ningun handler")
        assertEquals(ack.originalMessageId, decode(delivered).originalMessageId)
        assertEquals(ack.from, decode(delivered).from)
        assertEquals(ack.status, decode(delivered).status)
    }

    @Test
    fun `un ACK entrante NO llega al handler de mensajes`() {
        // La separacion de canales es lo que corta el ciclo de amplificacion. Un
        // `ACK` que se entregara como mensaje entrante entraria por
        // `ReceiverImpl`, que responderia con OTRO `ACK`.
        connectOnline()
        sendToClient(ackFrame(decode(signedAck())))

        assertTrue("llego un ACK", receivedAcks.poll(5, TimeUnit.SECONDS) != null)
        assertNull(
            "y el handler de mensajes tiene que quedarse vacio",
            receivedMessages.poll(500, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `la firma del ACK entrante se entrega byte a byte`() {
        // El relé reenvía el `ACK` verbatim (§11.1 ya enmendada). Lo que llega es
        // exactamente lo que se firmó, sin re-serializar ni normalizar.
        connectOnline()
        val signature = ByteArray(64) { (it * 23 + 11).toByte() }
        val ack = Ack(
            from = bobId,
            to = aliceId,
            originalMessageId = MessageId(UUID.randomUUID()),
            status = AckStatus.DELIVERED,
            timestamp = 1_790_000_000_000,
            signature = signature,
        )

        sendToClient(ackFrame(ack))

        val delivered = decode(receivedAcks.poll(5, TimeUnit.SECONDS)!!)
        assertTrue(
            "la firma debe llegar intacta, byte a byte",
            delivered.signature.contentEquals(signature),
        )
    }

    @Test
    fun `un ACK al que le falta un campo NO llega a nadie`() {
        connectOnline()
        val full = JSONObject(ackFrame(decode(signedAck())))

        listOf("originalMessageId", "status", "timestamp", "signature", "from", "to").forEach { campo ->
            full.remove(campo)
            sendToClient(full.toString())
        }

        assertNull(
            "§14.1 es atomico: ningun ACK incompleto puede llegar a un handler",
            receivedAcks.poll(600, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `un ACK con originalMessageId que no es UUID se descarta`() {
        connectOnline()
        val ack = JSONObject(ackFrame(decode(signedAck())))
        ack.put("originalMessageId", "esto-no-es-un-uuid")

        sendToClient(ack.toString())

        assertNull(
            "un originalMessageId imposible no puede convertirse en direccionamiento",
            receivedAcks.poll(600, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `un ACK con status desconocido se descarta`() {
        connectOnline()
        val ack = JSONObject(ackFrame(decode(signedAck())))
        ack.put("status", "INVENTADO")

        sendToClient(ack.toString())

        assertNull("un status que no existe no se inventa", receivedAcks.poll(600, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `un MESSAGE no puede disfrazarse de ACK`() {
        // Y al reves: un `MESSAGE` no tiene `originalMessageId`, asi que no puede
        // entrar por el canal `ACK` y hacer que `AckManager` espere la
        // confirmacion de un mensaje que nadie confirmo.
        connectOnline()
        val payload = ByteArray(44) { (it * 29 + 3).toByte() }
        sendToClient(
            JSONObject().apply {
                put("type", RelayDataCodec.TYPE_MESSAGE)
                put("from", bobId.value)
                put("to", aliceId.value)
                put("data", Base64.getEncoder().encodeToString(payload))
            }.toString(),
        )

        assertNull("un MESSAGE no puede vestirse de ACK", receivedAcks.poll(600, TimeUnit.MILLISECONDS))
        assertTrue("pero si llega como mensaje", receivedMessages.poll(5, TimeUnit.SECONDS) != null)
    }

    @Test
    fun `el ida y vuelta por el codec conserva el originalMessageId`() {
        // Lo que el emisor necesita: que el `originalMessageId` que vuelve sea
        // el del mensaje que el emisor guardo. Sin esto, `AckManagerImpl` busca
        // un registro que no existe, devuelve `success` y el emisor se queda
        // esperando una confirmacion que ya llego.
        val original = MessageId(UUID.randomUUID())
        val bytes = signedAck(originalMessageId = original)

        val porElCable = RelayDataCodec.decodeAck(RelayDataCodec.encodeAck(decode(bytes)))
        val deVuelta = RelayDataCodec.toCoreAckBytes(porElCable!!)

        assertNotNull(deVuelta)
        assertEquals(original, decode(deVuelta!!).originalMessageId)
    }
}