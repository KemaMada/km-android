package com.example.keymessage.network

import com.km.model.IdentityId
import org.json.JSONObject
import java.util.Base64

/**
 * Codec del camino de DATOS de `RelayClient`: el sobre `MESSAGE`/`ACK` y la
 * carga binaria que viaja dentro (KM-WIRE-RELAY §10).
 *
 * Existe como objeto aparte, sin dependencias de WebSocket ni de Android, por
 * dos razones:
 *
 * 1. Es el UNICO sitio donde un `ByteArray` de payload se convierte en texto.
 *    La conversion correcta es Base64 estándar (RFC 4648 §4), no
 *    `String(bytes)`: un payload binario no es texto, y el viaje por texto lo
 *    destruye (los bytes `0x80`..`0xFF` no son UTF-8 válido, y la lectura los
 *    convierte en U+FFFD, que al volver a codificar ocupa 3 bytes cada uno).
 * 2. Al no depender del socket, la propiedad `decode(encode(b)) == b` se puede
 *    medir en un test de JVM, sin red y sin `RelayClient`.
 *
 * Lo que este codec NO hace, a proposito (KM-WIRE-RELAY §6.1):
 *
 * - No mira el contenido de la carga. Un `SecureFrame` es opaco aqui: el
 *   relé solo transcodifica, y el cliente tampoco empieza a interpretarlo.
 * - No revalida `from`/`to` mas alla de "el campo esta y es un string". El
 *   anti-spoofing de identidad es del relé (§11); duplicarlo aqui seria dar al
 *   cliente una opinion sobre identidad que el contrato no le da.
 * - No repara Base64 invalido (§14.1). `decodePayload` y `decodeEnvelope` son
 *   ATOMICOS: devuelven `Result.failure` y ningun byte, nunca una carga
 *   parcial que el destinatario pudiera leer como un frame truncado.
 *
 * `nonce`, `publicKey` y `signature` NO pasan por aqui: viajan en Base64URL sin
 * padding porque entran en el transcript canonico de autenticacion (§9). Son
 * dos codificaciones distintas a proposito, y `RelayClient` las mantiene
 * separadas.
 */
object RelayDataCodec {

    /** Discriminante del sobre de datos (KM-WIRE-RELAY §10). */
    const val TYPE_MESSAGE: String = "MESSAGE"

    /** Control de APLICACION, opaco tambien para el relé (KM-WIRE-RELAY §11). */
    const val TYPE_ACK: String = "ACK"

    // Base64 ESTÁNDAR, no Base64URL: `data` no entra en ningun transcript de
    // firma y el relé lo decodifica con `Base64Standard` (km-daemon).
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** Carga binaria -> Base64 estándar. Total: todos los bytes de entrada. */
    fun encodePayload(payload: ByteArray): String = encoder.encodeToString(payload)

    /**
     * Base64 estándar -> carga binaria. ATOMICO (§14.1).
     *
     * Base64 no tiene bytes de control, ni `0x00` que trunque, ni alfabetos
     * que dependan del contenido: por eso no existe el caso "el byte nulo
     * corto la cadena" que sí existía en el viaje por texto.
     */
    fun decodePayload(encoded: String): Result<ByteArray> =
        runCatching { decoder.decode(encoded) }

    /**
     * Construye el sobre de salida.
     *
     * `messageId` y `timestamp` NO se emiten: §10 los marca como "Previsto" y
     * §6.1 prohíbe que el relé los lea. Añadirlos aquí solo daría al cliente
     * la forma de un campo que nadie consume.
     */
    fun encodeEnvelope(
        type: String,
        from: IdentityId,
        to: IdentityId,
        payload: ByteArray,
    ): String = JSONObject().apply {
        put("type", type)
        put("from", from.value)
        put("to", to.value)
        put("data", encoder.encodeToString(payload))
    }.toString()

    /**
     * Parsea el sobre de entrada y devuelve la carga YA en bytes.
     *
     * Falla entero y sin bytes parciales si falta `from`, `to` o `data`, si
     * alguno no es un string, o si `data` no es Base64 estándar válido.
     */
    fun decodeEnvelope(text: String): Result<RelayDataEnvelope> = runCatching {
        val json = JSONObject(text)
        RelayDataEnvelope(
            type = json.optString("type"),
            from = IdentityId(json.stringField("from")),
            to = IdentityId(json.stringField("to")),
            data = decoder.decode(json.stringField("data")),
        )
    }

    /**
     * Campo obligatorio y TEXTUAL.
     *
     * `opt()` en vez de `getString()` a proposito: `getString()` convierte
     * cualquier valor a texto, y un `data` numerico pasaria por aqui sin que
     * nadie se entere. El relé exige textual (`JsonNode.string`), y el cliente
     * exige lo mismo.
     */
    private fun JSONObject.stringField(field: String): String =
        opt(field) as? String
            ?: throw IllegalArgumentException("campo '$field' ausente o no textual")
}

/**
 * Sobre de datos entrante con la carga YA decodificada.
 *
 * Es la unica forma en la que un payload cruza esta capa: un `ByteArray` o
 * ningun byte, nunca un array parcial ni el JSON entero disfrazado de payload.
 */
data class RelayDataEnvelope(
    val type: String,
    val from: IdentityId,
    val to: IdentityId,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RelayDataEnvelope &&
            type == other.type &&
            from == other.from &&
            to == other.to &&
            data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}