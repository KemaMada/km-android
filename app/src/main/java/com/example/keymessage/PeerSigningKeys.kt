package com.example.keymessage

import android.content.SharedPreferences
import com.km.model.IdentityId

/**
 * Claves de FIRMA Ed25519 de los peers conocidos, por `identityId`.
 *
 * QUE ES Y QUE NO ES
 *
 * Es un almacen de la mitad PUBLICA de una relacion que ya se establecio en otro
 * sitio. No implementa nada de KM-ID-0001, no verifica ContactBundles y no
 * deriva claves: guarda lo que la app ya tiene, y lo devuelve a quien lo pide.
 *
 * POR QUE ESTA SEPARADO DEL CORE
 *
 * `KeyMessageCoreImpl` recibe un `peerPublicKeyResolver: (IdentityId) -> ByteArray?`
 * y se lo pasa a `AckManagerImpl`, que sin el devuelve `success` sin verificar y
 * sin procesar: el `ACK` llega, y el emisor se queda esperando uno que ya tiene.
 * El valor por defecto es `{ null }` justamente para no romper a quien construye
 * el core sin claves —y este almacen es la pieza que faltaba para que un
 * consumidor Android pueda dejar de usar ese valor por defecto—.
 *
 * POR QUE NO DESCUBRE NADA
 *
 * Una clave que no esta registrada NO se estima, NO se deriva y NO se inventa.
 * Un resolver que devolviera una clave supuesta autenticaria contra la identidad
 * equivocada, que es un fallo peor que no autenticar: no autentica y ademas
 * entrega un `ACK` a quien no le corresponde. Descubrir la clave del peer por su
 * `ContactBundle` verificado es Checkpoint C.
 *
 * PERSISTENCIA
 *
 * `SharedPreferences`, una entrada por identidad. Se escribe con `apply()`, que
 * es asincrono: registrar un peer no tiene que bloquear el hilo de la UI, y un
 * proceso que muere justo despues pierde como mucho la ultima clave anadida, no
 * el directorio entero.
 */
class PeerSigningKeys(
    private val prefs: SharedPreferences,
) {

    /**
     * Registra la clave publica de firma de [peerId], en Base64 estándar.
     *
     * Se guarda TAL CUAL, sin decodificar ni validar el tamano. Dos razones: el
     * Base64 se copia y se pega desde la UI, y un error de tecleo tiene que
     * desaparecer al abrir el contacto, no al registrarlo. Quien lo recibe es
     * `Ed25519.verify`, que rechaza una firma que no cuadra; aqui lo que se
     * decidiria es si ese `ACK` se procesa, y un Base64 roto produce una clave
     * que no verifica nada, que es el mismo resultado que no tener clave.
     */
    fun put(peerId: IdentityId, base64SigningKey: String) {
        prefs.edit().putString(peerId.value.lowercase(), base64SigningKey).apply()
    }

    /** Clave de firma de [peerId] tal como se registro, o `null`. */
    fun signingKeyOf(peerId: IdentityId): String? =
        prefs.getString(peerId.value.lowercase(), null)

    /** Identidades con clave registrada. */
    fun known(): Set<IdentityId> =
        prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.length == IDENTITY_ID_HEX && it.all { c -> c in "0123456789abcdef" } }
            .map { IdentityId(it) }
            .toSet()

    companion object {
        const val PREFS_NAME: String = "km_peers"

        /** `identityId` = SHA-256 -> 32 B -> 64 hex (KM-ID-0001 seccion 6). */
        const val IDENTITY_ID_HEX: Int = 64
    }
}