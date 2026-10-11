package com.example.keymessage

import com.km.identity.KmIds
import com.km.model.IdentityId
import java.util.Base64

/**
 * Deriva el `IdentityId` de relevo (64 hex) a partir de la clave publica de
 * FIRMA Ed25519 de un contacto. R2 / R3.
 *
 * DOS ESPACIOS DE IDENTIFICADORES QUE NO SE PUEDEN CONFUNDIR
 *
 * Los contactos de la app se guardan con un id de 40 hex
 * (`DhtNode.hashNodeId` = primeros 20 bytes de SHA-256 de la clave), que es el
 * espacio del stack DHT/legacy. El relevo NO habla ese idioma: su
 * `IdentityId` es `SHA-256("KM-ID-IDENTITY" || pubKey)` -> 32 bytes -> 64 hex
 * (KM-ID-0001 seccion 6), y `JsonWireCodec.identityId` exige `length == 64`.
 * Meter un id de 40 hex en un sobre lo descarta entero. Por eso la unica via
 * correcta es DERIVAR el `IdentityId` de la clave publica, nunca reutilizar
 * `Contact.id`.
 *
 * ESTA FUNCION NO ACEPTA EL ID DE 40 HEX
 *
 * [identityIdOf] recibe una clave publica EN BASE64, no un id en hex. Un id de
 * 40 hex pasado por error se decodifica como Base64 a 30 bytes y se RECHAZA
 * (`null`), igual que una clave que no sea Ed25519 de 32 bytes. Nunca se emite
 * un `IdentityId` a partir de un espacio de identificadores ajeno.
 *
 * `java.util.Base64` y no `android.util.Base64`: esta clase se prueba en JVM
 * (los stubs de Android lanzan "not mocked") y `java.util.Base64` esta en el
 * classpath de la app sin cambios.
 */
object ContactIdentity {

    /** `IdentityId` = SHA-256 -> 32 B -> 64 hex (KM-ID-0001 seccion 6). */
    const val IDENTITY_ID_HEX: Int = 64

    /** Una clave publica Ed25519 cruda. */
    private const val ED25519_PUBLIC_KEY_BYTES: Int = 32

    /**
     * `IdentityId` del contacto a partir de su clave publica de firma Ed25519
     * en Base64 estandar, o `null` si no es una clave de 32 bytes.
     *
     * El hex devuelto es SIEMPRE minusculas de longitud 64.
     */
    fun identityIdOf(base64PublicKey: String): IdentityId? {
        val raw = base64PublicKey.trim()
        if (raw.isEmpty()) return null
        val key = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return null
        if (key.size != ED25519_PUBLIC_KEY_BYTES) return null
        return IdentityId(bytesToHex(KmIds.identityId(key)))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
