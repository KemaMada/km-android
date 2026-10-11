package com.example.keymessage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E3 / 1.1. Fija la precedencia del endpoint del relevo:
 *
 *     override en runtime (`Settings.Global.km_relay_ws_url`) > default de
 *     `BuildConfig.RELAY_WS_URL` (`ws://10.0.2.2:8080/ws`).
 *
 * El valor del default se comprueba contra el LITERAL y no solo contra
 * `BuildConfig`: si alguien cambia el `buildConfigField` a un endpoint que el
 * emulador no alcanza, el test tiene que caer, no acompanarlo.
 *
 * El override se inyecta con `resolve { ... }`, que es exactamente la misma
 * logica que corre en el dispositivo: lo unico que cambia en produccion es de
 * donde sale el `String` (un `ContentResolver` en vez de un lambda).
 */
class RelayEndpointTest {

    private val emulatorDefault = "ws://10.0.2.2:8080/ws"

    @Test
    fun `el default es el loopback del emulador`() {
        assertEquals(emulatorDefault, RelayEndpoint.defaultUrl)
        assertEquals(emulatorDefault, RelayEndpoint.resolve { null })
    }

    @Test
    fun `un override en runtime retargetea sin rebuild`() {
        assertEquals(
            "ws://127.0.0.1:8080/ws",
            RelayEndpoint.resolve { "ws://127.0.0.1:8080/ws" },
        )
        assertEquals(
            "ws://192.168.1.42:9000/ws",
            RelayEndpoint.resolve { "ws://192.168.1.42:9000/ws" },
        )
    }

    @Test
    fun `el override tiene precedencia sobre el default de BuildConfig`() {
        val override = "ws://10.9.9.9:8080/ws"
        val effective = RelayEndpoint.resolve { "  $override\n" }
        assertEquals(override, effective)
        // Y no es el default, que es lo que probaria que el override se ignora.
        assertEquals(false, effective == RelayEndpoint.defaultUrl)
    }

    @Test
    fun `un override vacio o en blanco cae al default`() {
        assertEquals(emulatorDefault, RelayEndpoint.resolve { "" })
        assertEquals(emulatorDefault, RelayEndpoint.resolve { "   " })
    }

    @Test
    fun `la clave de Settings Global es la del contrato adb`() {
        // El override se escribe con `adb shell settings put global km_relay_ws_url`.
        // Si esta constante cambia, ese comando deja de surtir efecto en silencio:
        // el test fija el nombre que promete el contrato de S3a/S3b.
        assertEquals("km_relay_ws_url", RelayEndpoint.SETTINGS_KEY)
    }
}
