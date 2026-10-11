package com.example.keymessage

import com.km.crypto.Ed25519Impl
import com.km.identity.KmIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * R2/R3 / 1.3. El `IdentityId` de relevo se DERIVA de la clave publica Ed25519
 * del contacto, y su espacio (64 hex) no se puede confundir con el de los
 * contactos guardados por la app (40 hex de `DhtNode.hashNodeId`).
 *
 * El caso "40 hex" no es decorativo: es exactamente lo que hay en `Contact.id`,
 * y era la tentacion que D1 documenta. Si ese id llegara al rele, `identityId`
 * no mide 64 y el sobre se descarta entero.
 */
class ContactIdentityTest {

    private val ed25519 = Ed25519Impl()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `una clave Ed25519 de 32 bytes produce un IdentityId de 64 hex`() {
        val publicKey = ed25519.generateKeyPair().publicKey
        val base64 = Base64.getEncoder().encodeToString(publicKey)

        val id = ContactIdentity.identityIdOf(base64)

        assertNotNull("una clave Ed25519 valida tiene que derivar", id)
        assertEquals(64, id!!.value.length)
        // Misma derivacion canonica que el core (KM-ID-0001 seccion 6).
        assertEquals(hex(KmIds.identityId(publicKey)), id.value)
        assertTrue(
            "el hex tiene que ser minusculas",
            id.value.all { it in "0123456789abcdef" },
        )
    }

    @Test
    fun `un id de 40 hex de DhtNode NO se emite como IdentityId`() {
        // Contact.id tal cual lo produce DhtNode.hashNodeId: 40 hex, espacio ajeno.
        val dhtNodeId = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(40, dhtNodeId.length)

        assertNull(
            "un id de 40 hex no puede convertirse en IdentityId de relevo",
            ContactIdentity.identityIdOf(dhtNodeId),
        )
    }

    @Test
    fun `un IdentityId de 64 hex tampoco se re-deriva`() {
        // Ya es un destino del rele, no una clave: re-derivarlo daria otro id.
        val alreadyHex = "6e567d78a536041bd04854ba93f09fb0eca9a087418b918d6f5ab838354ca89f"
        assertEquals(64, alreadyHex.length)
        assertNull(ContactIdentity.identityIdOf(alreadyHex))
    }

    @Test
    fun `una clave que no sea Ed25519 de 32 bytes se rechaza`() {
        // Una clave RSA de la UI (294 B) no es una clave de identidad Ed25519.
        assertNull(ContactIdentity.identityIdOf(Base64.getEncoder().encodeToString(ByteArray(294))))
        assertNull(ContactIdentity.identityIdOf(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertNull(ContactIdentity.identityIdOf(Base64.getEncoder().encodeToString(ByteArray(33))))
        assertNull(ContactIdentity.identityIdOf(""))
        assertNull(ContactIdentity.identityIdOf("   "))
        assertNull(ContactIdentity.identityIdOf("no-es-base64!!!"))
    }

    @Test
    fun `cualquier valor aceptado mide exactamente 64 hex`() {
        val candidates = listOf(
            "0123456789abcdef0123456789abcdef01234567",
            Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
            Base64.getEncoder().encodeToString(ByteArray(63) { 2 }),
        )
        for (candidate in candidates) {
            ContactIdentity.identityIdOf(candidate)?.let { id ->
                assertEquals(64, id.value.length)
            }
        }
    }
}
