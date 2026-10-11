package com.example.keymessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1 / 1.3. Fija la PRECEDENCIA y la VALIDACION ESTRICTA del pin de identidad
 * del relevo:
 *
 *     override en runtime (`Settings.Global.km_relay_identity`) > default de
 *     `BuildConfig.RELAY_IDENTITY` (el daemon de desarrollo).
 *
 * Y, tan importante como la precedencia, las dos reglas de seguridad:
 *
 *   - un valor malformado NO degrada al default: es rechazo seguro (`null`);
 *   - la identidad que anuncia un challenge NUNCA sustituye a la fijada.
 *
 * El valor del default se comprueba contra el LITERAL y no solo contra
 * `BuildConfig`: si alguien cambia el `buildConfigField` a otra identidad, el
 * test tiene que caer, no acompanarlo.
 */
class RelayIdentityPinTest {

    private val devId = "cadd3afc301de94d9161ce7834b8c6b6396d49dd529a830269dfcc09c4cda590"
    private val prodId = "e365dd1cac805d1a7f1673071aec8b434b250980dcc2be9183b27b86752ff4f3"
    private val otherId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `el default es la identidad del daemon de desarrollo`() {
        assertEquals(devId, RelayIdentityPin.defaultIdentity)
        assertEquals(devId, RelayIdentityPin.resolve { null })
    }

    @Test
    fun `un override valido de runtime tiene precedencia sobre el default`() {
        val effective = RelayIdentityPin.resolve { prodId }
        assertEquals(prodId, effective)
        assertNotEquals(RelayIdentityPin.defaultIdentity, effective)
    }

    @Test
    fun `un override en blanco cae al default`() {
        assertEquals(devId, RelayIdentityPin.resolve { "" })
        assertEquals(devId, RelayIdentityPin.resolve { "   " })
    }

    @Test
    fun `un override valido con espacios se recorta`() {
        assertEquals(prodId, RelayIdentityPin.resolve { "  $prodId\n" })
    }

    @Test
    fun `un override malformado es rechazo seguro y NO degrada al default`() {
        assertNull(
            "mayusculas no son hex-minusculas",
            RelayIdentityPin.resolve { prodId.uppercase() },
        )
        assertNull("63 hex", RelayIdentityPin.resolve { prodId.dropLast(1) })
        assertNull("65 hex", RelayIdentityPin.resolve { prodId + "a" })
        assertNull("no hex", RelayIdentityPin.resolve { "no-es-hex" })
        assertNull("40 hex (forma antigua)", RelayIdentityPin.resolve { devId.take(40) })
    }

    @Test
    fun `isValid exige exactamente 64 hex minusculas`() {
        assertTrue(RelayIdentityPin.isValid(devId))
        assertTrue(RelayIdentityPin.isValid(prodId))
        assertTrue(RelayIdentityPin.isValid(otherId))

        assertFalse("mayusculas", RelayIdentityPin.isValid(devId.uppercase()))
        assertFalse("63 hex", RelayIdentityPin.isValid(devId.dropLast(1)))
        assertFalse("65 hex", RelayIdentityPin.isValid(devId + "a"))
        assertFalse("no hex", RelayIdentityPin.isValid(devId.dropLast(1) + "g"))
        assertFalse("vacio", RelayIdentityPin.isValid(""))
        assertFalse("null", RelayIdentityPin.isValid(null))
    }

    @Test
    fun `la clave de Settings Global es la del contrato adb`() {
        // El override se escribe con `adb shell settings put global km_relay_identity`.
        // Si esta constante cambia, ese comando deja de surtir efecto en silencio.
        assertEquals("km_relay_identity", RelayIdentityPin.SETTINGS_KEY)
    }

    @Test
    fun `acceptedPinFor acepta solo el pin reproducido exactamente`() {
        assertEquals(devId, RelayIdentityPin.acceptedPinFor(devId, devId))
        assertNull("otra identidad", RelayIdentityPin.acceptedPinFor(devId, prodId))
        assertNull("ausente", RelayIdentityPin.acceptedPinFor(devId, null))
        assertNull("distinta caja", RelayIdentityPin.acceptedPinFor(devId, devId.uppercase()))
    }

    @Test
    fun `la identidad del challenge no sustituye a la fijada`() {
        // Sin pin, un challenge con identidad VALIDA no se adopta (no TOFU).
        assertNull(RelayIdentityPin.acceptedPinFor(null, prodId))
        // Con un pin invalido, tampoco.
        assertNull(RelayIdentityPin.acceptedPinFor("no-es-hex", prodId))
    }
}
