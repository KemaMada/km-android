package com.example.keymessage

import android.content.ContentResolver
import android.provider.Settings

/**
 * Resuelve la URL del relevo WebSocket. E3 / R1 / S3a-S3b.
 *
 * PRECEDENCIA, EXPLICITA
 *
 *     1. Override en tiempo de ejecucion (`Settings.Global.km_relay_ws_url`)
 *     2. Default en tiempo de compilacion (`BuildConfig.RELAY_WS_URL`)
 *
 * POR QUE NO BASTA `BuildConfig`
 *
 * `BuildConfig` se congela al compilar. Un telefono fisico no puede alcanzar
 * `ws://10.0.2.2:8080/ws`: esa direccion es el loopback del HOST visto desde el
 * emulador, y en un telefono real no resuelve a nada. Para apuntar el telefono a
 * un relevo en la LAN/VPS habria que recompilar, y eso hace inejercitable la
 * prueba E2E fisica (S3b). `Settings.Global` es escribible desde adb sin tocar
 * la app:
 *
 *     adb shell settings put global km_relay_ws_url ws://192.168.x.y:8080/ws
 *
 * y la app lo lee en el arranque, sin rebuild.
 *
 * POR QUE NO `Settings.Secure` NI SharedPreferences
 *
 * `Secure` es por-usuario y puede requerir permisos; `SharedPreferences`
 * exigiria abrir la app para sembrar el valor. `Global` es el mismo espacio que
 * usan las pruebas de dispositivo para flags de depuracion y se escribe desde
 * adb directamente.
 *
 * Un valor vacio o en blanco NO es un override: cae al default, para que un
 * `settings put ... ""` deje el comportamiento del emulador intacto en vez de
 * dejar la app sin endpoint.
 */
object RelayEndpoint {

    /** Nombre de la clave de `Settings.Global` que lee el override. */
    const val SETTINGS_KEY: String = "km_relay_ws_url"

    /** Default del emulador, congelado en compilacion. */
    val defaultUrl: String get() = BuildConfig.RELAY_WS_URL

    /**
     * Version pura, sin Android: resuelve a partir de un lector inyectable.
     * Es la que prueba `RelayEndpointTest`.
     *
     * @param readOverride devuelve el valor de `km_relay_ws_url`, o `null`/vacio
     *   cuando no hay override.
     */
    fun resolve(readOverride: () -> String?): String {
        val override = readOverride()?.trim()
        return if (!override.isNullOrEmpty()) override else defaultUrl
    }

    /** Liga [resolve] a `Settings.Global` a traves de [contentResolver]. */
    fun resolve(contentResolver: ContentResolver?): String = resolve {
        contentResolver?.let { resolver ->
            runCatching { Settings.Global.getString(resolver, SETTINGS_KEY) }.getOrNull()
        }
    }
}
