package com.example.keymessage.network

import com.example.keymessage.util.LogBuffer
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * R5/S9 — tarea 2.7 (y 2.5). Los caminos de FALLO del `RelayClient` y la
 * presencia del relé tienen que dejar rastro en el log; antes no dejaban
 * ninguno y por eso un endpoint de senalizacion muerto era invisible (obs #126).
 *
 * QUE FIJA
 *
 * 1. Un cierre anormal registra `code` y `reason`.
 * 2. Un fallo de socket registra la CLASE y el MENSAJE de la excepcion.
 * 3. Un `AUTH_FAIL` registra `errorCode` y `errorMessage` (los mandaba el relé y
 *    la app los tiraba).
 * 4. `PEER_ONLINE` registra la `IdentityId` de 64 hex del peer (2.5/S4).
 * 5. La URL efectiva de conexion queda registrada.
 * 6. NINGUNA linea de log puede contener la carga ni el plaintext.
 *
 * Se levanta un WebSocket de verdad con `MockWebServer`, igual que
 * `RelayClientAckTest`, para ejercitar los callbacks reales de OkHttp.
 */
class RelayClientFailureLogTest {

    private val ed25519 = Ed25519Impl()
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
    private val bobId = IdentityId("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private val relayId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8")

    private val challengeNonce = ByteArray(16) { (it * 31 + 7).toByte() }
    private val challengeTimestamp = 1_790_000_000_000L

    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<RelayClient>()

    @Volatile
    private var relaySideSocket: WebSocket? = null

    /** Relé simulado que completa el handshake (dialecto B). */
    private val handshakeStub = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            relaySideSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (JSONObject(text).optString("type")) {
                "AUTH_REQUEST" -> webSocket.send(authChallenge())
                "AUTH_RESPONSE" -> webSocket.send(authOk())
                else -> Unit
            }
        }
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
        put(
            "serverSignature",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64)),
        )
    }.toString()

    @Before
    fun setUp() {
        // El log es un singleton de proceso: se limpia para que cada asercion
        // sea sobre lo que produce ESTE test, no sobre residuos anteriores.
        LogBuffer.clear()
        relaySideSocket = null
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.disconnect() } }
        servers.forEach { runCatching { it.shutdown() } }
        clients.clear()
        servers.clear()
        LogBuffer.clear()
    }

    private fun startRelay(listener: WebSocketListener): MockWebServer {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        servers.add(server)
        return server
    }

    private fun buildClient(url: String): RelayClient {
        val keyPair: KeyPair = ed25519.generateKeyPair()
        val client = RelayClient(
            url = url,
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            pinnedRelayIdentityId = relayId.value,
            ed25519 = ed25519,
            maxReconnectDelayMs = 500,
        )
        clients.add(client)
        return client
    }

    private fun connectOnline(client: RelayClient) {
        val online = CountDownLatch(1)
        client.onStateChange { state -> if (state == ConnectionState.ONLINE) online.countDown() }
        client.connect().getOrThrow()
        assertTrue("el cliente no llego a ONLINE", online.await(10, TimeUnit.SECONDS))
    }

    /** Espera a que aparezca una linea de log que cumpla [predicate]. */
    private fun awaitLog(timeoutSeconds: Long = 10, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            LogBuffer.getLines().firstOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError(
            "no aparecio la linea de log esperada.\nLineas observadas:\n" +
                LogBuffer.getLines().joinToString("\n"),
        )
    }

    // =====================================================================
    // 2.7 — cierre y fallo
    // =====================================================================

    @Test
    fun `un cierre anormal del socket registra codigo y razon`() {
        val server = startRelay(handshakeStub)
        val client = buildClient(server.url("/ws").toString())
        connectOnline(client)

        LogBuffer.clear()
        relaySideSocket?.close(4001, "RELAY_DRAIN")

        val line = awaitLog { it.contains("4001") && it.contains("RELAY_DRAIN") }
        assertTrue("debe salir por la etiqueta del relé: $line", line.contains("[$RELAY_LOG_TAG]"))
    }

    @Test
    fun `un fallo de socket registra la clase y el mensaje de la excepcion`() {
        // Endpoint muerto: se arranca un MockWebServer para obtener una URL y se
        // apaga antes de conectar, de modo que el TCP connect se rechaza y
        // OkHttp entrega la excepcion por `onFailure`.
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/ws").toString()
        dead.shutdown()

        val client = buildClient(deadUrl)
        client.connect()

        val line = awaitLog { it.contains("fallo de socket") }
        assertTrue(
            "debe nombrar la clase de excepcion, no solo 'fallo': $line",
            Regex("fallo de socket: \\w*Exception").containsMatchIn(line),
        )
    }

    @Test
    fun `AUTH_FAIL registra errorCode y errorMessage`() {
        val failStub = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "AUTH_REQUEST") {
                    webSocket.send(
                        JSONObject().apply {
                            put("type", "AUTH_FAIL")
                            put("errorCode", "INVALID_SIGNATURE")
                            put("errorMessage", "firma del cliente invalida")
                        }.toString(),
                    )
                }
            }
        }
        val server = startRelay(failStub)
        val client = buildClient(server.url("/ws").toString())
        client.connect()

        awaitLog {
            it.contains("AUTH_FAIL") &&
                it.contains("INVALID_SIGNATURE") &&
                it.contains("firma del cliente invalida")
        }
    }

    @Test
    fun `la URL de conexion queda registrada`() {
        val server = startRelay(handshakeStub)
        val url = server.url("/ws").toString()
        val client = buildClient(url)
        client.connect()

        awaitLog { it.contains("conectando a") && it.contains(url) }
    }

    // =====================================================================
    // 2.5 — PEER_ONLINE con la IdentityId del peer
    // =====================================================================

    @Test
    fun `PEER_ONLINE registra la IdentityId de 64 hex del peer`() {
        val server = startRelay(handshakeStub)
        val client = buildClient(server.url("/ws").toString())
        val seen = LinkedBlockingQueue<IdentityId>()
        client.onPeerOnline { seen.add(it) }
        connectOnline(client)

        LogBuffer.clear()
        relaySideSocket?.send(
            JSONObject().apply {
                put("type", "PEER_ONLINE")
                put("identityId", bobId.value)
            }.toString(),
        )

        val line = awaitLog { it.contains("[$PEER_ONLINE_LOG_TAG]") && it.contains(bobId.value) }
        assertTrue("la linea debe llevar la identidad del peer: $line", line.contains(bobId.value))
        // Y el handler sigue recibiendo la presencia: el log no sustituye al
        // despacho, lo documenta.
        assertEquals(bobId, seen.poll(5, TimeUnit.SECONDS))
    }

    // =====================================================================
    // Seguridad del log
    // =====================================================================

    @Test
    fun `ningun log expone la carga ni el plaintext`() {
        val server = startRelay(handshakeStub)
        val client = buildClient(server.url("/ws").toString())
        val received = LinkedBlockingQueue<ByteArray>()
        client.setMessageHandler { received.add(it) }
        connectOnline(client)

        LogBuffer.clear()

        val plaintext = "CANARIO-SECRETO-E2E-1234567890"
        val payload = plaintext.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(payload)
        relaySideSocket?.send(
            JSONObject().apply {
                put("type", RelayDataCodec.TYPE_MESSAGE)
                put("from", bobId.value)
                put("to", aliceId.value)
                put("data", encoded)
            }.toString(),
        )
        assertArrayEquals("el mensaje debe entregarse intacto", payload, received.poll(5, TimeUnit.SECONDS))

        // Y un cierre para cubrir tambien el camino de fallo.
        relaySideSocket?.close(4002, "cierre de prueba")
        awaitLog { it.contains("4002") }
        Thread.sleep(200)

        val leaks = LogBuffer.getLines().filter { line ->
            line.contains(plaintext) ||
                line.contains(encoded) ||
                line.contains("\"data\"")
        }
        assertTrue("ningun log puede contener payload/plaintext; fugas: $leaks", leaks.isEmpty())
    }
}
