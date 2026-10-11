package com.example.keymessage

import com.example.keymessage.util.LogBuffer
import com.km.model.IdentityId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * R2 / 1.4. Un peer SIN clave de firma registrada no puede autenticar su `ACK`.
 * Ese descarte tiene que quedar en el log, no ocurrir en silencio: sin el, el
 * emisor se queda esperando una confirmacion que ya llego y nada lo explica.
 *
 * Se prueba la funcion que usa `AppContainer` como `peerPublicKeyResolver`, con
 * el `log` inyectado (assertable) y tambien con el default (que escribe en el
 * `LogBuffer` real, para no dejar un no-op en produccion).
 */
class PeerKeyResolutionTest {

    private val peerId =
        IdentityId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    @Test
    fun `un peer sin clave registrada loguea y devuelve null`() {
        val logs = mutableListOf<Pair<String, String>>()

        val resolved = resolvePeerSigningKey(peerId, null) { tag, msg -> logs.add(tag to msg) }

        assertNull("sin clave no se puede resolver", resolved)
        assertEquals("el descarte no puede ser silencioso", 1, logs.size)
        assertEquals(PEER_KEY_LOG_TAG, logs[0].first)
        assertTrue("el aviso nombra al peer", logs[0].second.contains(peerId.value))
    }

    @Test
    fun `con la clave registrada devuelve los bytes y no loguea`() {
        val signingKey = ByteArray(32) { (it * 7 + 1).toByte() }
        val logs = mutableListOf<Pair<String, String>>()

        val resolved = resolvePeerSigningKey(
            peerId,
            Base64.getEncoder().encodeToString(signingKey),
        ) { tag, msg -> logs.add(tag to msg) }

        assertNotNull(resolved)
        assertArrayEquals(signingKey, resolved)
        assertTrue("un peer con clave no genera aviso", logs.isEmpty())
    }

    @Test
    fun `el default escribe en el LogBuffer real y no en un no-op`() {
        LogBuffer.clear()

        resolvePeerSigningKey(peerId, null)

        val line = LogBuffer.getLines().lastOrNull { it.contains(PEER_KEY_LOG_TAG) }
        assertNotNull("el aviso tiene que llegar al LogBuffer", line)
        assertTrue("y nombrar al peer", line!!.contains(peerId.value))
    }

    @Test
    fun `una clave registrada que no es Base64 tampoco se descarta en silencio`() {
        val logs = mutableListOf<Pair<String, String>>()

        val resolved = resolvePeerSigningKey(peerId, "no-es-base64!!!") { tag, msg ->
            logs.add(tag to msg)
        }

        assertNull(resolved)
        assertEquals(1, logs.size)
        assertEquals(PEER_KEY_LOG_TAG, logs[0].first)
        assertTrue(logs[0].second.contains(peerId.value))
    }
}
