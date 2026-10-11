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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * E1 / 1.3. PIN OBLIGATORIO de la identidad del relevo, sobre un WebSocket de
 * verdad (`MockWebServer`).
 *
 * QUE FIJA
 *
 * 1. MATCH: un challenge que reproduce el pin se acepta y la firma cubre el
 *    transcript canonico de 152 B con la identidad FIJADA (contrato KM-0002
 *    intacto).
 * 2. MISMATCH: un challenge con otra identidad se rechaza ANTES de firmar; no
 *    sale ningun `AUTH_RESPONSE`.
 * 3. AUSENTE/MALFORMADO: sin pin valido no se abre la conexion ni se envia
 *    siquiera `AUTH_REQUEST`: rechazo seguro.
 * 4. La identidad anunciada por el challenge NO sustituye a la fijada: nunca se
 *    adopta (sin TOFU).
 */
class RelayClientAuthPinningTest {

    private val ed25519 = Ed25519Impl()
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")

    /** Pin valido (64 hex minusculas). */
    private val pinnedId = "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8"

    /** Otra identidad valida: lo que un relevo distinto anunciaria. */
    private val rogueId = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"

    private val challengeNonce = ByteArray(16) { (it * 31 + 7).toByte() }
    private val challengeTimestamp = 1_790_000_000_000L

    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<RelayClient>()
    private val frames = CopyOnWriteArrayList<String>()

    @Volatile
    private var relaySideSocket: WebSocket? = null

    private lateinit var keyPair: KeyPair

    private fun relayStub(announcedId: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            relaySideSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.add(text)
            when (JSONObject(text).optString("type")) {
                "AUTH_REQUEST" -> webSocket.send(authChallenge(announcedId))
                "AUTH_RESPONSE" -> webSocket.send(authOk())
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    private fun authChallenge(announcedId: String): String = JSONObject().apply {
        put("type", "AUTH_CHALLENGE")
        put("messageId", UUID.randomUUID().toString())
        put("timestamp", challengeTimestamp)
        put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(challengeNonce))
        put("responderIdentityId", announcedId)
    }.toString()

    private fun authOk(): String = JSONObject().apply {
        put("type", "AUTH_OK")
        put("messageId", UUID.randomUUID().toString())
        put("timestamp", challengeTimestamp + 2)
        put("protocolVersion", "2.0")
        put("identityId", aliceId.value)
        put("responderIdentityId", pinnedId)
        put("sessionId", UUID.randomUUID().toString())
        put("serverSignature", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64)))
    }.toString()

    @Before
    fun setUp() {
        LogBuffer.clear()
        frames.clear()
        relaySideSocket = null
        keyPair = ed25519.generateKeyPair()
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.disconnect() } }
        servers.forEach { runCatching { it.shutdown() } }
        clients.clear()
        servers.clear()
        LogBuffer.clear()
    }

    private fun startRelay(announcedId: String): MockWebServer {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(relayStub(announcedId)))
        servers.add(server)
        return server
    }

    private fun buildClient(url: String, pin: String?): RelayClient {
        val client = RelayClient(
            url = url,
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            pinnedRelayIdentityId = pin,
            ed25519 = ed25519,
            maxReconnectDelayMs = 500,
        )
        clients.add(client)
        return client
    }

    private fun framesOfType(type: String): List<JSONObject> =
        frames.map { JSONObject(it) }.filter { it.optString("type") == type }

    private fun awaitFrame(type: String, timeoutSeconds: Long = 10): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            framesOfType(type).firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no llego un frame $type.\nFrames observados: $frames")
    }

    private fun awaitLog(timeoutSeconds: Long = 10, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            LogBuffer.getLines().firstOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no aparecio la linea de log.\n" + LogBuffer.getLines().joinToString("\n"))
    }

    // =====================================================================
    // 1. Match
    // =====================================================================

    @Test
    fun `un challenge con el pin fijado se acepta y firma el transcript de 152 B`() {
        val server = startRelay(pinnedId)
        val client = buildClient(server.url("/ws").toString(), pinnedId)
        client.connect().getOrThrow()

        val request = awaitFrame("AUTH_REQUEST")
        assertEquals(
            "el responder declarado es el PIN, no la identidad que anuncie el relevo",
            pinnedId,
            request.getString("responderIdentityId"),
        )

        val response = awaitFrame("AUTH_RESPONSE")
        val transcript = ByteArrayOutputStream().apply {
            write(challengeNonce)
            write(ByteBuffer.allocate(8).putLong(challengeTimestamp).array())
            write(pinnedId.toByteArray(Charsets.US_ASCII))
            write(aliceId.value.toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        assertEquals(
            "16 nonce + 8 ts + 64 responder + 64 identity = 152 B (KM-0002)",
            152,
            transcript.size,
        )

        assertArrayEquals(
            "el transcript se firma con el pin fijado; el contrato de auth no cambia",
            ed25519.sign(keyPair.privateKey, transcript).bytes,
            Base64.getUrlDecoder().decode(response.getString("signature")),
        )
    }

    // =====================================================================
    // 2. Mismatch
    // =====================================================================

    @Test
    fun `un challenge con otra identidad se rechaza antes de firmar`() {
        val server = startRelay(rogueId)
        val client = buildClient(server.url("/ws").toString(), pinnedId)
        client.connect().getOrThrow()
        awaitFrame("AUTH_REQUEST")

        // Si el cliente firmara, el AUTH_RESPONSE llegaria en milisegundos.
        Thread.sleep(800)
        assertTrue(
            "no puede salir AUTH_RESPONSE con un challenge en mismatch: $frames",
            framesOfType("AUTH_RESPONSE").isEmpty(),
        )
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())
        awaitLog {
            it.contains("AUTH_CHALLENGE rechazado") &&
                it.contains(pinnedId) &&
                it.contains(rogueId)
        }
    }

    // =====================================================================
    // 3. Ausente / malformado => rechazo seguro
    // =====================================================================

    @Test
    fun `sin pin no se abre conexion ni se envia AUTH_REQUEST`() {
        val server = startRelay(rogueId)
        val client = buildClient(server.url("/ws").toString(), null)
        client.connect().getOrThrow()

        Thread.sleep(500)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())
        assertTrue("sin pin no puede salir ningun frame: $frames", frames.isEmpty())
        awaitLog { it.contains("sin identidad de relevo fijada valida") }
    }

    @Test
    fun `un pin malformado es rechazo seguro`() {
        val server = startRelay(rogueId)
        val client = buildClient(server.url("/ws").toString(), "NO-ES-UN-PIN")
        client.connect().getOrThrow()

        Thread.sleep(500)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())
        assertTrue("un pin invalido no puede abrir conexion: $frames", frames.isEmpty())
    }

    @Test
    fun `un challenge con identidad malformada se rechaza`() {
        val server = startRelay("corto")
        val client = buildClient(server.url("/ws").toString(), pinnedId)
        client.connect().getOrThrow()
        awaitFrame("AUTH_REQUEST")

        Thread.sleep(800)
        assertTrue(
            "un responder malformado no puede producir AUTH_RESPONSE: $frames",
            framesOfType("AUTH_RESPONSE").isEmpty(),
        )
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())
    }

    // =====================================================================
    // 4. La identidad del challenge no sustituye a la fijada
    // =====================================================================

    @Test
    fun `la identidad del challenge no sustituye a la fijada`() {
        // El relevo anuncia una identidad VALIDA (rogueId), pero distinta del
        // pin. Si el cliente la adoptara (TOFU), autenticaria; debe rechazar.
        val server = startRelay(rogueId)
        val client = buildClient(server.url("/ws").toString(), pinnedId)
        client.connect().getOrThrow()
        awaitFrame("AUTH_REQUEST")

        Thread.sleep(800)
        assertTrue(framesOfType("AUTH_RESPONSE").isEmpty())
        assertTrue(
            "el cliente no puede quedar ONLINE adoptando la identidad anunciada",
            client.connectionState() != ConnectionState.ONLINE,
        )
        val line = awaitLog { it.contains("AUTH_CHALLENGE rechazado") }
        assertTrue("el rechazo nombra la identidad fijada: $line", line.contains(pinnedId))
        assertTrue("y la anunciada: $line", line.contains(rogueId))
    }
}
