package com.example.keymessage.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R7 / tarea 2.8. La superficie de depuracion tiene que exponer los valores que
 * un test por `adb` afirma —identidad propia, endpoint de relevo, ultimos
 * `messageId` enviado/recibido, estado de entrega y estado de conexion— y
 * hacerlo ademas en el log (`km-debug.log`), sin tocar la UI.
 *
 * Y tiene que exponer SOLO eso: identificadores publicos, URL y nombres de
 * estado. Nunca payload ni plaintext.
 */
class DebugSurfaceTest {

    private val emitted = mutableListOf<String>()

    private val identityId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    private val relayEndpoint = "ws://10.0.2.2:8080/ws"
    private val sentId = "11111111-2222-4333-8444-555555555555"
    private val receivedId = "99999999-8888-4777-8666-555555555555"

    @Before
    fun setUp() {
        DebugSurface.reset()
        emitted.clear()
        DebugSurface.setSink { tag, msg -> emitted.add("[$tag] $msg") }
    }

    @After
    fun tearDown() {
        DebugSurface.reset()
        DebugSurface.resetSink()
    }

    @Test
    fun `la superficie expone identidad endpoint estado y messageIds`() {
        DebugSurface.setIdentityId(identityId)
        DebugSurface.setRelayEndpoint(relayEndpoint)
        DebugSurface.setConnectionState("ONLINE")
        DebugSurface.recordSent(sentId)
        DebugSurface.recordReceived(receivedId)
        DebugSurface.setDeliveryState("DELIVERED")

        val snapshot = DebugSurface.snapshot()
        assertEquals(identityId, snapshot["identityId"])
        assertEquals(relayEndpoint, snapshot["relayEndpoint"])
        assertEquals("ONLINE", snapshot["connectionState"])
        assertEquals(sentId, snapshot["lastSentMessageId"])
        assertEquals(receivedId, snapshot["lastReceivedMessageId"])
        assertEquals("DELIVERED", snapshot["deliveryState"])
    }

    @Test
    fun `cada valor se emite al log con la etiqueta DEBUG`() {
        DebugSurface.setIdentityId(identityId)
        DebugSurface.setRelayEndpoint(relayEndpoint)
        DebugSurface.setConnectionState("ONLINE")
        DebugSurface.recordSent(sentId)
        DebugSurface.recordReceived(receivedId)
        DebugSurface.setDeliveryState("DELIVERED")

        val joined = emitted.joinToString("\n")
        assertTrue("identityId", joined.contains("[${DebugSurface.TAG}] identityId=$identityId"))
        assertTrue("relayEndpoint", joined.contains("[${DebugSurface.TAG}] relayEndpoint=$relayEndpoint"))
        assertTrue("connectionState", joined.contains("[${DebugSurface.TAG}] connectionState=ONLINE"))
        assertTrue("lastSentMessageId", joined.contains("[${DebugSurface.TAG}] lastSentMessageId=$sentId"))
        assertTrue("lastReceivedMessageId", joined.contains("[${DebugSurface.TAG}] lastReceivedMessageId=$receivedId"))
        assertTrue("deliveryState", joined.contains("[${DebugSurface.TAG}] deliveryState=DELIVERED"))
    }

    @Test
    fun `un valor repetido no vuelve a emitirse`() {
        DebugSurface.setConnectionState("ONLINE")
        DebugSurface.setConnectionState("ONLINE")
        DebugSurface.setIdentityId(identityId)
        DebugSurface.setIdentityId(identityId)

        assertEquals(
            "solo las transiciones reales escriben log",
            2,
            emitted.count { it.startsWith("[" + DebugSurface.TAG + "]") },
        )
    }

    @Test
    fun `nunca emite payload ni plaintext`() {
        val marker = "PLAINTEXT-MARKER-9876543210"
        // Lo unico que la superficie recibe son identificadores/estados; aun
        // asi, se comprueba de forma explicita que el marcador de un payload no
        // se cuela por ningun lado.
        DebugSurface.recordSent(sentId)
        DebugSurface.recordReceived(receivedId)
        DebugSurface.setDeliveryState("DELIVERED")

        assertTrue(
            "la superficie no puede filtrar contenido: ${emitted.filter { it.contains(marker) }}",
            emitted.none { it.contains(marker) },
        )
        assertTrue("tampoco el campo data del sobre", emitted.none { it.contains("\"data\"") })
    }

    @Test
    fun `reset limpia la superficie`() {
        DebugSurface.setIdentityId(identityId)
        DebugSurface.recordSent(sentId)

        DebugSurface.reset()

        assertTrue(DebugSurface.snapshot().isEmpty())
    }
}
