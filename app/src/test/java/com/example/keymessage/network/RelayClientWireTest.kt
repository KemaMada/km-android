package com.example.keymessage.network

import com.km.codec.JsonRelayControlCodec
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.RelayExpiredNotice
import com.km.model.StoredReceipt
import com.km.protocol.ConnectionState
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Regresion sobre las rutas EXISTENTES de `RelayClient`, contra un WebSocket
 * de verdad (`MockWebServer`).
 *
 * Dos mitades distintas:
 *
 * 1. El camino de DATOS: el sobre de salida, la carga de entrada, los bytes
 *    con `0x00` y los que no son UTF-8.
 * 2. Lo que NO puede romperse al arreglarlo: el handshake sigue firmando el
 *    mismo transcript de 152 B, `STORED` y `RELAY_EXPIRED` siguen viajando
 *    como JSON para `JsonRelayControlCodec`, `PING` sigue contestando `PONG`,
 *    y la cola de pendientes usa el MISMO camino que `send()`.
 */
class RelayClientWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    private val ed25519 = Ed25519Impl()
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c")
    private val bobId = IdentityId("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private val relayId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0")

    /** Nonce y timestamp del challenge: fijos, para poder recomputar la firma. */
    private val challengeNonce = ByteArray(16) { (it * 31 + 7).toByte() }
    private val challengeTimestamp = 1_790_000_000_000L

    private val framesFromClient = LinkedBlockingQueue<String>()
    private val receivedMessages = LinkedBlockingQueue<ByteArray>()
    private val receivedAcks = LinkedBlockingQueue<ByteArray>()
    private val storedReceipts = LinkedBlockingQueue<StoredReceipt>()
    private val expiredNotices = LinkedBlockingQueue<RelayExpiredNotice>()
    private val online = CountDownLatch(1)

    @Volatile
    private var relaySideSocket: WebSocket? = null

    private lateinit var keyPair: KeyPair

    // =====================================================================
    // Relé simulado: habla dialecto B y no sabe nada de SecureFrame
    // =====================================================================

    private val relayStub = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            relaySideSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            framesFromClient.add(text)
            when (JSONObject(text).optString("type")) {
                "AUTH_REQUEST" -> webSocket.send(authChallenge())
                "AUTH_RESPONSE" -> webSocket.send(authOk())
                "MESSAGE" -> webSocket.send(envelope(RelayDataCodec.TYPE_MESSAGE, bobId, aliceId, payload44()))
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
        put("identityId", aliceId.value)
        put("responderIdentityId", relayId.value)
        put("sessionId", UUID.randomUUID().toString())
        put("serverSignature", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64)))
    }.toString()

    private fun envelope(type: String, from: IdentityId, to: IdentityId, payload: ByteArray): String =
        JSONObject().apply {
            put("type", type)
            put("from", from.value)
            put("to", to.value)
            put("data", Base64.getEncoder().encodeToString(payload))
        }.toString()

    // =====================================================================
    // Arranque y parada
    // =====================================================================

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(relayStub))

        keyPair = ed25519.generateKeyPair()
        client = RelayClient(
            url = server.url("/ws").toString(),
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            relayIdentityId = relayId,
            ed25519 = ed25519,
            maxReconnectDelayMs = 500,
        )
        client.onStateChange { state -> if (state == ConnectionState.ONLINE) online.countDown() }
        client.setMessageHandler { receivedMessages.add(it) }
        client.setAckHandler { receivedAcks.add(it) }
        client.setStoredHandler { storedReceipts.add(it) }
        client.setRelayExpiredHandler { expiredNotices.add(it) }
    }

    @After
    fun tearDown() {
        client.disconnect()
        server.shutdown()
    }

    /** Lleva el cliente a ONLINE, con el handshake completo de por medio. */
    private fun connectOnline() {
        client.connect().getOrThrow()
        assertTrue("el cliente no llego a ONLINE", online.await(10, TimeUnit.SECONDS))
    }

    /** Envía un frame al cliente por el WebSocket abierto. */
    private fun sendToClient(frame: String) {
        val socket = relaySideSocket
            ?: throw AssertionError("no hay WebSocket abierto: el cliente no se conecto")
        socket.send(frame)
    }

    /** Primer frame del cliente cuyo `type` sea [type]. */
    private fun awaitFrame(type: String, timeoutSeconds: Long = 10): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val frame = framesFromClient.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val json = JSONObject(frame)
            if (json.optString("type") == type) return json
        }
        throw AssertionError("llego el limite de tiempo esperando un frame de tipo $type")
    }

    private fun neverDelivered(queue: LinkedBlockingQueue<ByteArray>, windowMs: Long = 600): ByteArray? =
        queue.poll(windowMs, TimeUnit.MILLISECONDS)

    // =====================================================================
    // Ida: el sobre de salida
    // =====================================================================

    @Test
    fun `an outgoing payload travels as a base64 MESSAGE envelope`() {
        connectOnline()
        val payload = payload44()

        client.send(payload, bobId).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_MESSAGE)

        assertEquals("MESSAGE", frame.getString("type"))
        assertEquals(aliceId.value, frame.getString("from"))
        assertEquals(bobId.value, frame.getString("to"))
        assertArrayEquals(
            "el relé debe recibir los bytes EXACTOS que salieron del cliente",
            payload,
            Base64.getDecoder().decode(frame.getString("data")),
        )
    }

    @Test
    fun `an outgoing payload keeps its null bytes and its high bytes`() {
        connectOnline()
        val payload = ByteArray(44) { (it * 29 + 3).toByte() }.also { it[7] = 0x00; it[23] = 0x00 }
        assertTrue("el vector debe llevar bytes >= 0x80", payload.any { (it.toInt() and 0xFF) >= 0x80 })

        client.send(payload, bobId).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_MESSAGE)

        assertArrayEquals(payload, Base64.getDecoder().decode(frame.getString("data")))
    }

    @Test
    fun `an outgoing payload that needs plus and slash uses the standard alphabet`() {
        connectOnline()
        val payload = byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte())

        client.send(payload, bobId).getOrThrow()
        val frame = awaitFrame(RelayDataCodec.TYPE_MESSAGE)

        assertEquals("++++", frame.getString("data"))
        assertArrayEquals(payload, Base64.getDecoder().decode(frame.getString("data")))
    }

    // =====================================================================
    // Vuelta: la carga se decodifica
    // =====================================================================

    @Test
    fun `an incoming MESSAGE delivers the exact payload bytes`() {
        connectOnline()
        val payload = payload44()

        sendToClient(envelope(RelayDataCodec.TYPE_MESSAGE, bobId, aliceId, payload))

        assertArrayEquals(payload, receivedMessages.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an incoming ACK delivers the exact payload bytes`() {
        connectOnline()
        val payload = ByteArray(64) { (it * 13 + 5).toByte() }.also { it[0] = 0x00; it[63] = 0xFF.toByte() }

        sendToClient(envelope(RelayDataCodec.TYPE_ACK, bobId, aliceId, payload))

        assertArrayEquals(payload, receivedAcks.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an incoming MESSAGE with invalid base64 delivers nothing`() {
        connectOnline()

        sendToClient("""{"type":"MESSAGE","from":"${bobId.value}","to":"${aliceId.value}","data":"AQ==="}""")

        assertNull(
            "§14.1 es atomico: un Base64 invalido NO puede entregar carga parcial",
            neverDelivered(receivedMessages),
        )
    }

    @Test
    fun `an incoming frame with no data field delivers nothing`() {
        connectOnline()

        sendToClient("""{"type":"MESSAGE","from":"${bobId.value}","to":"${aliceId.value}"}""")
        sendToClient("""{"type":"ACK","from":"${bobId.value}","to":"${aliceId.value}"}""")

        assertNull("un MESSAGE sin `data` no puede entregar el JSON entero", neverDelivered(receivedMessages))
        assertNull("un ACK sin `data` no puede entregar el JSON entero", neverDelivered(receivedAcks))
    }

    // =====================================================================
    // Regresion: las rutas de JSON de control NO se han tocado
    // =====================================================================

    @Test
    fun `STORED still arrives as JSON and is parsed by the control codec`() {
        connectOnline()
        val messageId = UUID.randomUUID()
        val originalId = UUID.randomUUID()
        val stored = JsonRelayControlCodec()
            .encodeStored(
                StoredReceipt(
                    messageId = MessageId(messageId),
                    timestamp = 1_790_000_000_000L,
                    originalMessageId = MessageId(originalId),
                    to = aliceId,
                    expiresAt = 1_790_006_000_000L,
                    relayNodeId = relayId,
                )
            ).getOrThrow()

        // Texto plano: es lo que emite el relé. Si `STORED` hubiera pasado por
        // `deliverPayload`, no llegaria nada.
        sendToClient(String(stored, Charsets.UTF_8))

        val receipt = storedReceipts.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("llegó un STORED y no se entregó a ningun handler")
        assertEquals(messageId, receipt.messageId.value)
        assertEquals(originalId, receipt.originalMessageId.value)
        assertEquals(aliceId, receipt.to)
        assertEquals(relayId, receipt.relayNodeId)
    }

    @Test
    fun `RELAY_EXPIRED still arrives as JSON and is parsed by the control codec`() {
        connectOnline()
        val messageId = UUID.randomUUID()
        val originalId = UUID.randomUUID()
        val expired = JsonRelayControlCodec()
            .encodeRelayExpired(
                RelayExpiredNotice(
                    messageId = MessageId(messageId),
                    timestamp = 1_790_000_000_000L,
                    originalMessageId = MessageId(originalId),
                    to = aliceId,
                    reason = "TTL",
                )
            ).getOrThrow()

        sendToClient(String(expired, Charsets.UTF_8))

        val notice = expiredNotices.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("llegó un RELAY_EXPIRED y no se entregó a ningun handler")
        assertEquals(messageId, notice.messageId.value)
        assertEquals(originalId, notice.originalMessageId.value)
        assertEquals("TTL", notice.reason)
    }

    @Test
    fun `the auth handshake still signs the same transcript`() {
        connectOnline()

        val request = awaitFrame("AUTH_REQUEST")
        assertEquals(aliceId.value, request.getString("identityId"))
        assertEquals(relayId.value, request.getString("responderIdentityId"))
        assertEquals("2.0", request.getString("protocolVersion"))
        assertArrayEquals(
            "`publicKey` viaja en Base64URL sin padding, no en Base64 estandar (§9.3)",
            keyPair.publicKey,
            Base64.getUrlDecoder().decode(request.getString("publicKey")),
        )

        val response = awaitFrame("AUTH_RESPONSE")
        val transcript = ByteArrayOutputStream().apply {
            write(challengeNonce)
            write(ByteBuffer.allocate(8).putLong(challengeTimestamp).array())
            write(relayId.value.toByteArray(Charsets.US_ASCII))
            write(aliceId.value.toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        val expected = ed25519.sign(keyPair.privateKey, transcript).bytes

        assertArrayEquals(
            "el camino de datos no puede haber tocado el transcript de autenticacion",
            expected,
            Base64.getUrlDecoder().decode(response.getString("signature")),
        )
    }

    @Test
    fun `PING is still answered with PONG`() {
        connectOnline()

        sendToClient(
            JSONObject().apply {
                put("type", "PING")
                put("messageId", "3f0c1a2b-0000-4000-8000-000000000001")
                put("timestamp", 1_790_000_000_000L)
            }.toString()
        )

        val pong = awaitFrame("PONG")
        assertEquals("3f0c1a2b-0000-4000-8000-000000000001", pong.getString("originalMessageId"))
    }

    // =====================================================================
    // Regresion: la cola de pendientes usa el MISMO camino
    // =====================================================================

    @Test
    fun `a message queued while offline is drained with the same envelope`() {
        // Sin conectar: `send()` encola y no envia nada.
        val payload = payload44()
        client.send(payload, bobId).getOrThrow()
        assertTrue("no deberia haberse enviado nada aun", framesFromClient.isEmpty())

        connectOnline()
        val frame = awaitFrame(RelayDataCodec.TYPE_MESSAGE)

        assertEquals(aliceId.value, frame.getString("from"))
        assertEquals(bobId.value, frame.getString("to"))
        assertArrayEquals(
            "el camino de la cola no puede ser distinto del de send()",
            payload,
            Base64.getDecoder().decode(frame.getString("data")),
        )
    }

    @Test
    fun `a queued message with null and high bytes survives the drain`() {
        val payload = ByteArray(44) { (it * 41 + 9).toByte() }.also { it[0] = 0x00; it[43] = 0xFF.toByte() }
        client.send(payload, bobId).getOrThrow()

        connectOnline()
        val frame = awaitFrame(RelayDataCodec.TYPE_MESSAGE)

        assertArrayEquals(payload, Base64.getDecoder().decode(frame.getString("data")))
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    /** 44 B de cabecera de SecureFrame, con contenido no constante y no UTF-8. */
    private fun payload44(): ByteArray = BinarySecureFrameCodec.authenticatedHeader(
        type = FrameType.MESSAGE,
        ratchetHeader = RatchetHeader(
            dhPublicKey = ByteArray(32) { ((it + 3) * 37 + 11).toByte() },
            previousChainLength = 4u,
            messageNumber = 1_234_567u,
        ),
        ciphertextLength = 37,
    )
}