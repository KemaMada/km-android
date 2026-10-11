package com.example.keymessage

import android.content.ContentResolver
import android.provider.Settings

/**
 * Resuelve y VALIDA la identidad del relevo que la app fija (pin). E1 / 1.3.
 *
 * PRECEDENCIA, EXPLICITA
 *
 *     1. Override en tiempo de ejecucion (`Settings.Global.km_relay_identity`)
 *     2. Default en tiempo de compilacion (`BuildConfig.RELAY_IDENTITY`)
 *
 * Es el mismo mecanismo que `km_relay_ws_url` (ver [RelayEndpoint]): el default
 * de desarrollo se congela en `BuildConfig` y un telefono fisico se retargetea
 * desde `adb`, sin recompilar:
 *
 *     adb shell settings put global km_relay_identity e365dd1c…
 *
 * POR QUE ES UN PIN Y NO SE ADOPTA LO QUE EL RELE ANUNCIA
 *
 * La identidad del relevo es una decision de CONFIANZA, no un dato que el otro
 * extremo pueda imponer. Adoptar el `responderIdentityId` del `AUTH_CHALLENGE`
 * seria TOFU: el primer extremo que conteste en la URL quedaria aceptado sin
 * verificacion. Aqui el valor sale SIEMPRE de la configuracion local, y el
 * challenge solo puede COINCIDIR con el o ser rechazado (ver
 * [acceptedPinFor]).
 *
 * VALIDACION ESTRICTA: exactamente 64 caracteres hex en minusculas, nada mas.
 * Ni 40 hex (la forma antigua), ni mayusculas, ni prefijos. Un valor que no
 * cumple NO es un pin.
 *
 * SIN DEGRADACION SILENCIOSA: un override informado pero malformado NO cae al
 * default; es un rechazo seguro (devuelve `null`). Degradar al default ante un
 * override escrito a mano seria autenticar contra una identidad distinta de la
 * que el operador pidio, en silencio.
 */
object RelayIdentityPin {

    /** Nombre de la clave de `Settings.Global` que lee el override. */
    const val SETTINGS_KEY: String = "km_relay_identity"

    /** 64 hex minusculas, y solo eso. */
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** Default de desarrollo, congelado en compilacion. */
    val defaultIdentity: String get() = BuildConfig.RELAY_IDENTITY

    /** `true` si [value] es exactamente 64 hex minusculas. */
    fun isValid(value: String?): Boolean = value != null && HEX64.matches(value)

    /**
     * Version pura, sin Android: resuelve a partir de un lector inyectable.
     * Es la que prueba `RelayIdentityPinTest`.
     *
     * @param readOverride devuelve el valor de `km_relay_identity`, o
     *   `null`/vacio cuando no hay override.
     * @return un pin valido, o `null` si no hay ninguno (rechazo seguro).
     */
    fun resolve(readOverride: () -> String?): String? {
        val override = readOverride()?.trim()
        val candidate = if (!override.isNullOrEmpty()) {
            // Override explicito: si es invalido, NO se degrada al default.
            override
        } else {
            defaultIdentity.trim()
        }
        return candidate.takeIf { isValid(it) }
    }

    /** Liga [resolve] a `Settings.Global` a traves de [contentResolver]. */
    fun resolve(contentResolver: ContentResolver?): String? = resolve {
        contentResolver?.let { resolver ->
            runCatching { Settings.Global.getString(resolver, SETTINGS_KEY) }.getOrNull()
        }
    }

    /**
     * Devuelve el pin ACEPTADO para el `responderIdentityId` [announced] que
     * anuncia un `AUTH_CHALLENGE`, o `null` si la autenticacion debe rechazarse.
     *
     * Es el unico punto que decide si un challenge es admisible. Exige que el
     * pin sea valido Y que el challenge reproduzca EXACTAMENTE ese pin. En
     * particular devuelve `null` cuando [pinned] es `null` aunque [announced]
     * sea un identificador valido: la identidad anunciada NUNCA sustituye a la
     * fijada (sin TOFU).
     */
    fun acceptedPinFor(pinned: String?, announced: String?): String? =
        pinned?.takeIf { isValid(it) && announced == it }
}
