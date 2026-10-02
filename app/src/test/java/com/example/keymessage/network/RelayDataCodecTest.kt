package com.example.keymessage.network

import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.frame.SecureFrame
import com.km.frame.SecureFrameSpec
import com.km.model.IdentityId
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * Propiedad central del camino de datos de `RelayClient`:
 *
 *     decode(encode(bytes)) == bytes
 *
 * El fallo que se corrige aqui era exactamente el contrario: `String(bytes)`
 * y `bytes.toByteArray()`. Ese viaje por texto solo es invisible cuando el
 * payload resulta ser ASCII valido, asi que los vectores de este fichero
 * llevan, a proposito, contenido que NO sobrevive a un viaje por texto.
 */
class RelayDataCodecTest {

    private val alice = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c")
    private val bob = IdentityId("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")

    // =====================================================================
    // Propiedad central
    // =====================================================================

    /**
     * Ida y vuelta por el camino REAL, no por una version recortada: se
     * construye el sobre JSON, se parsea, y se saca el campo `data`.
     */
    private fun roundTrip(payload: ByteArray): ByteArray {
        val wire = RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, alice, bob, payload)
        val envelope = RelayDataCodec.decodeEnvelope(wire).getOrElse {
            throw AssertionError("el sobre recien construido no se pudo decodificar: ${it.message}")
        }
        return envelope.data
    }

    // --- vectores nombrados -----------------------------------------------

    @Test
    fun `vector - a single null byte`() {
        assertArrayEquals(byteArrayOf(0x00), roundTrip(byteArrayOf(0x00)))
    }

    @Test
    fun `vector - a single high byte`() {
        assertArrayEquals(byteArrayOf(0xFF.toByte()), roundTrip(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `vector - the four byte extremes`() {
        val bytes = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte())
        assertArrayEquals(bytes, roundTrip(bytes))
    }

    @Test
    fun `vector - a 44 byte SecureFrame header`() {
        val header = header44(seed = 11)
        assertEquals(SecureFrameSpec.HEADER_LENGTH, header.size)
        assertArrayEquals(header, roundTrip(header))
    }

    @Test
    fun `vector - a 44 byte SecureFrame header plus ciphertext`() {
        val frame = frameWithCiphertext(seed = 23)
        assertEquals(SecureFrameSpec.HEADER_LENGTH + CIPHERTEXT_LENGTH, frame.size)
        assertArrayEquals(frame, roundTrip(frame))
    }

    @Test
    fun `random bytes - assorted lengths including 0 and 1`() {
        val rng = Random(LENGTH_SEED)
        repeat(RANDOM_PAYLOADS) {
            val length = when (it % 4) {
                0 -> rng.nextInt(4)          // 0..3: incluye 0 y 1
                1 -> rng.nextInt(64)
                2 -> rng.nextInt(1024)
                else -> rng.nextInt(8192)
            }
            val payload = ByteArray(length).also(rng::nextBytes)
            assertArrayEquals(
                "idempotencia rota con $length bytes aleatorios (caso #$it)",
                payload,
                roundTrip(payload),
            )
        }
    }

    @Test
    fun `every length from 0 to 1024 round trips`() {
        val rng = Random(LENGTH_SEED)
        for (length in 0..1024) {
            val payload = ByteArray(length).also(rng::nextBytes)
            assertArrayEquals(
                "idempotencia rota con longitud exacta $length",
                payload,
                roundTrip(payload),
            )
        }
    }

    // --- no degeneracion de los vectores ---------------------------------
    //
    // Un SecureFrame lleno de ceros NO probaria nada: una lectura parcial que
    // conserve la longitud es indistinguible de un entero con relleno de ceros,
    // y ademas 0x00 SI sobrevive a un viaje por texto. Estos dos tests dejan
    // escrito que los vectores de arriba tienen el contenido que los hace
    // discriminatingos.

    @Test
    fun `the 44 byte vector is not a constant and not valid UTF-8`() {
        val header = header44(seed = 11)
        assertTrue(
            "el vector de 44 B no puede ser una constante: no distinguiria una corrupcion",
            header.distinct().size > 1,
        )
        assertTrue(
            "el vector de 44 B debe llevar bytes >= 0x80, que no son UTF-8 valido",
            header.any { (it.toInt() and 0xFF) >= 0x80 },
        )
    }

    @Test
    fun `the SecureFrame plus ciphertext vector is not a constant and not valid UTF-8`() {
        val frame = frameWithCiphertext(seed = 23)
        assertTrue("el vector completo no puede ser una constante", frame.distinct().size > 1)
        assertTrue(
            "el vector completo debe llevar bytes >= 0x80, que no son UTF-8 valido",
            frame.any { (it.toInt() and 0xFF) >= 0x80 },
        )
    }

    // =====================================================================
    // Regresion sobre los modos de fallo concretos
    // =====================================================================

    @Test
    fun `a null byte in the middle does not truncate the string or the JSON`() {
        for (nullAt in listOf(0, 1, 17, 43)) {
            val payload = ByteArray(44) { (it * 13 + 5).toByte() }
            payload[nullAt] = 0x00

            val wire = RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, alice, bob, payload)
            val encodedData = JSONObject(wire).getString("data")

            assertFalse(
                "un 0x00 en la posicion $nullAt metio un caracter de control en `data`",
                encodedData.any { it.code < 0x20 || it.code == 0x7F },
            )
            assertFalse("un 0x00 metio un NUL en el texto del sobre", wire.contains('\u0000'))
            assertEquals("el sobre se corto en el 0x00 de la posicion $nullAt", 4, JSONObject(wire).length())
            assertArrayEquals(payload, roundTrip(payload))
        }
    }

    @Test
    fun `raw bytes from 0x80 to 0xFF survive intact`() {
        val payload = ByteArray(128) { (0x80 + (it % 0x80)).toByte() }
        assertTrue("el vector deberia ser alto entero", payload.all { (it.toInt() and 0xFF) >= 0x80 })
        assertArrayEquals(payload, roundTrip(payload))
    }

    @Test
    fun `a payload whose standard Base64 needs plus and slash is not URL-alphabet`() {
        // 0xFB 0xEF 0xBE -> "++++" en Base64 estandar. Si alguien "arreglara" el
        // codec cambiando a Base64URL, este test lo delata.
        val payload = byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte())
        val wire = RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, alice, bob, payload)
        val encoded = JSONObject(wire).getString("data")

        assertEquals("++++", encoded)
        assertTrue("`data` debe usar el alfabeto Base64 ESTANDAR", encoded.contains('+'))
        assertArrayEquals(payload, roundTrip(payload))
    }

    @Test
    fun `an empty payload is a valid zero byte payload`() {
        assertArrayEquals(ByteArray(0), roundTrip(ByteArray(0)))

        val wire = RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, alice, bob, ByteArray(0))
        val envelope = RelayDataCodec.decodeEnvelope(wire).getOrThrow()
        assertEquals(0, envelope.data.size)
    }

    // =====================================================================
    // Forma del sobre
    // =====================================================================

    @Test
    fun `the outgoing envelope carries exactly the fields the relay reads`() {
        val payload = ByteArray(44) { (it * 37 + 11).toByte() }
        val json = JSONObject(RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, alice, bob, payload))

        assertEquals("MESSAGE", json.getString("type"))
        assertEquals(alice.value, json.getString("from"))
        assertEquals(bob.value, json.getString("to"))
        assertEquals(Base64.getEncoder().encodeToString(payload), json.getString("data"))
    }

    @Test
    fun `the incoming envelope reports who sent it and who it was for`() {
        val wire = RelayDataCodec.encodeEnvelope(RelayDataCodec.TYPE_MESSAGE, bob, alice, header44(11))
        val envelope = RelayDataCodec.decodeEnvelope(wire).getOrThrow()

        assertEquals(RelayDataCodec.TYPE_MESSAGE, envelope.type)
        assertEquals(bob, envelope.from)
        assertEquals(alice, envelope.to)
        assertArrayEquals(header44(11), envelope.data)
    }

    @Test
    fun `unknown fields are ignored`() {
        val wire = """{"type":"MESSAGE","from":"${bob.value}","to":"${alice.value}",
            |"data":"AQID","messageId":"e6f70819-2b3c-4d5e-9f60-1728394a5b6c",
            |"timestamp":1790000005000,"algo":{"que":"sea"}}|"""
            .trimMargin()
            .replace("\n", "")

        val envelope = RelayDataCodec.decodeEnvelope(wire).getOrThrow()
        assertArrayEquals(byteArrayOf(1, 2, 3), envelope.data)
    }

    // =====================================================================
    // Decodificacion ATOMICA (KM-WIRE-RELAY §14.1)
    // =====================================================================

    @Test
    fun `invalid base64 is discarded whole and yields no bytes`() {
        val rejected = listOf(
            "!!!!" to "caracteres fuera del alfabeto",
            "A" to "longitud imposible: len % 4 == 1",
            "AB C" to "espacio: no se tolera en silencio",
            "-_-_" to "alfabeto Base64URL: no es Base64 estandar",
            "AQ===" to "padding de mas",
            "====" to "unidad final de 4 bytes vacia",
        )
        for ((encoded, why) in rejected) {
            val result = RelayDataCodec.decodePayload(encoded)
            assertTrue("«$encoded» ($why) deberia fallar", result.isFailure)
            assertNull(
                "«$encoded» ($why) devolvio una carga parcial; §14.1 lo prohibe",
                result.getOrNull(),
            )
        }
    }

    @Test
    fun `a MESSAGE with invalid base64 is discarded whole`() {
        val wire = """{"type":"MESSAGE","from":"${bob.value}","to":"${alice.value}","data":"AQ==="}"""
        val result = RelayDataCodec.decodeEnvelope(wire)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a MESSAGE without a usable data field is discarded whole`() {
        val withoutData = """{"type":"MESSAGE","from":"${bob.value}","to":"${alice.value}"}"""
        val withoutFrom = """{"type":"MESSAGE","to":"${alice.value}","data":"AQID"}"""
        val withoutTo = """{"type":"MESSAGE","from":"${bob.value}","data":"AQID"}"""
        // `data` numerico: getString() lo habria convertido a "123" en
        // silencio. Aqui no: el rele exige textual (JsonNode.string).
        val numericData = """{"type":"MESSAGE","from":"${bob.value}","to":"${alice.value}","data":123}"""

        for (wire in listOf(withoutData, withoutFrom, withoutTo, numericData)) {
            val result = RelayDataCodec.decodeEnvelope(wire)
            assertTrue("deberia fallar: $wire", result.isFailure)
            assertNull("no debe inventar bytes: $wire", result.getOrNull())
        }
    }

    @Test
    fun `a frame that is not a JSON object is discarded whole`() {
        for (wire in listOf("no soy json", "[1,2,3]", "\"una cadena\"", "")) {
            assertNull("no debe inventar bytes: «$wire»", RelayDataCodec.decodeEnvelope(wire).getOrNull())
        }
    }

    // =====================================================================
    // MEDICION: el fallo antes y despues
    // =====================================================================

    /**
     * Caracteriza el camino RETIRADO, no el actual: `String(data)` y
     * `data.toByteArray()`. Vive en el repo como linea base de la regresion:
     * es la medicion que justifica el cambio, y no depende de nada del codigo
     * actual, asi que no puede quedarse obsoleta por un retoque.
     */
    @Test
    fun `MEASUREMENT - the legacy text path corrupted every 44 byte frame`() {
        var lengthChanged = 0
        var contentChanged = 0

        for (i in 0 until MEASUREMENT_SAMPLES) {
            val frame = header44(seed = i)
            val returned = String(frame).toByteArray()

            if (returned.size != frame.size) lengthChanged++
            if (!returned.contentEquals(frame)) contentChanged++
        }

        println(
            "MEDICION ANTIGUO (String(data).toByteArray()): " +
                "$MEASUREMENT_SAMPLES SecureFrames de 44 B -> " +
                "longitud distinta: $lengthChanged, contenido distinto: $contentChanged"
        )

        assertEquals(10_000, lengthChanged)
        assertEquals(10_000, contentChanged)
    }

    /**
     * La MISMA medicion sobre el camino NUEVO. El contrato es cero: no una
     * tolerancia, cero. Lo que se media antes era 10 000 de 10 000.
     */
    @Test
    fun `MEASUREMENT - the base64 path corrupts nothing`() {
        var lengthChanged = 0
        var contentChanged = 0

        for (i in 0 until MEASUREMENT_SAMPLES) {
            val frame = header44(seed = i)
            val returned = roundTrip(frame)

            if (returned.size != frame.size) lengthChanged++
            if (!returned.contentEquals(frame)) contentChanged++
        }

        println(
            "MEDICION NUEVO (Base64 estandar): " +
                "$MEASUREMENT_SAMPLES SecureFrames de 44 B -> " +
                "longitud distinta: $lengthChanged, contenido distinto: $contentChanged"
        )

        assertEquals(0, lengthChanged)
        assertEquals(0, contentChanged)
    }

    // =====================================================================
    // Vectores
    // =====================================================================

    private fun dhPublicKey(seed: Int): ByteArray =
        ByteArray(SecureFrameSpec.DH_PUBLIC_KEY_LENGTH) { ((it + seed) * 37 + 11).toByte() }

    /**
     * Los 44 B de cabecera de un SecureFrame (`version || type || length ||
     * dhPublicKey || PN || N`). Sin ciphertext a proposito: el vector es
     * exactamente la longitud que se media en la regresion.
     */
    private fun header44(seed: Int): ByteArray = BinarySecureFrameCodec.authenticatedHeader(
        type = FrameType.MESSAGE,
        ratchetHeader = RatchetHeader(
            dhPublicKey = dhPublicKey(seed),
            previousChainLength = 4u,
            messageNumber = 1_234_567u + seed.toUInt(),
        ),
        ciphertextLength = 37,
    )

    /** Cabecera de 44 B + un payload de [CIPHERTEXT_LENGTH] B. */
    private fun frameWithCiphertext(seed: Int): ByteArray = BinarySecureFrameCodec.encode(
        SecureFrame(
            version = SecureFrameSpec.VERSION,
            type = FrameType.MESSAGE,
            ratchetHeader = RatchetHeader(
                dhPublicKey = dhPublicKey(seed),
                previousChainLength = 4u,
                messageNumber = 1_234_567u + seed.toUInt(),
            ),
            ciphertext = ByteArray(CIPHERTEXT_LENGTH) { ((it + seed) * 29 + 7).toByte() },
        )
    )

    private companion object {
        const val MEASUREMENT_SAMPLES = 10_000

        /**
         * Semilla fija: un fallo tiene que ser reproducible, no "a veces".
         * `4B 4D 5F 4C` es el identificador del sitio, en ASCII.
         */
        const val LENGTH_SEED = 0x4B4D5F4CL

        const val RANDOM_PAYLOADS = 5_000
        const val CIPHERTEXT_LENGTH = 96
    }
}