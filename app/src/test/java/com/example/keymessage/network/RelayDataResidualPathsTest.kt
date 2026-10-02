package com.example.keymessage.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprobacion de que NO QUEDA NINGUNA RUTA que convierta bytes de payload a
 * texto en el camino de datos del relé.
 *
 * Un `grep` sobre el arbol de fuentes da positivo, y tiene que dar positivo:
 * las rutas de JSON de control SIEMPRE viajan como texto. La question no es si
 * hay texto, sino si hay texto **usado como transporte de bytes**. Este test
 * separa las dos cosas y clasifica cada conversion que sobrevive.
 *
 * Es una comprobacion de FUENTES, no de comportamiento: no demuestra que el
 * runtime este bien, solo que la conversion prohibida no ha vuelto a
 * aparecer. El comportamiento lo cubren `RelayDataCodecTest` (propiedad) y
 * `RelayClientWireTest` (sobre un WebSocket de verdad). Las tres juntas son la
 * garantia; esta sola, no.
 *
 * Ante un fallo, el mensaje lista cada conversion encontrada con la funcion
 * que la contiene, para que clasificarla sea un paso y no una investigacion.
 */
class RelayDataResidualPathsTest {

    // =====================================================================
    // Allow-list: cada conversion de bytes a texto que SOBRE, y por que es
    // legitima. Anadir una linea aqui es una decision, no un accident.
    // =====================================================================

    private data class Site(val file: String, val function: String, val line: String, val why: String)

    private val ALLOWED = listOf(
        Site(
            file = RELAY_CLIENT,
            function = "handleStored",
            line = "val receipt = JsonRelayControlCodec().decodeStored(text.toByteArray()).getOrNull() ?: return",
            why = "STORED es JSON de CONTROL: lo parsea JsonRelayControlCodec.decodeStored (§13). " +
                "El `text` es el frame entero, no una carga Base64.",
        ),
        Site(
            file = RELAY_CLIENT,
            function = "handleRelayExpired",
            line = "val notice = JsonRelayControlCodec().decodeRelayExpired(text.toByteArray()).getOrNull() ?: return",
            why = "RELAY_EXPIRED es JSON de CONTROL: lo parsea JsonRelayControlCodec.decodeRelayExpired (§13).",
        ),
        Site(
            file = RELAY_CLIENT,
            function = "computeAuthSignature",
            line = "val relayIdBytes = relayIdentityId.value.toByteArray(Charsets.US_ASCII)",
            why = "TEXTO -> BYTES, no al reves, y en US_ASCII explicito: arma el transcript canonico de 152 B (§9.5).",
        ),
        Site(
            file = RELAY_CLIENT,
            function = "computeAuthSignature",
            line = "val identityBytes = localIdentity.value.toByteArray(Charsets.US_ASCII)",
            why = "TEXTO -> BYTES, no al reves, y en US_ASCII explicito: arma el transcript canonico de 152 B (§9.5).",
        ),
        Site(
            file = RELAY_CLIENT,
            function = "computeAuthSignature",
            line = "}.toByteArray()",
            why = "ByteArrayOutputStream -> ByteArray: el transcript recien armado, todavia sin firmar. No sale de la maquina.",
        ),
    )

    // =====================================================================
    // Tests
    // =====================================================================

    @Test
    fun `no residual route turns payload bytes into text`() {
        val seen = sites()
        val unexplained = seen.filter { site ->
            ALLOWED.none { it.file == site.file && it.function == site.function && it.line == site.line }
        }

        assertTrue(
            "Hay conversiones de bytes a texto sin clasificar en el camino de datos del relé:\n" +
                unexplained.joinToString("\n") { "  ${it.file}:${it.function}\n      ${it.line}" } +
                "\n\nSi una es legitima, añadela a ALLOWED con su motivo." +
                "\nSi no lo es, es exactamente el defecto que este test busca.",
            unexplained.isEmpty(),
        )
    }

    @Test
    fun `the allow-listed sites are still there`() {
        val seen = sites().map { "${it.file}:${it.function} -> ${it.line}" }.toSet()
        val missing = ALLOWED.filterNot { "${it.file}:${it.function} -> ${it.line}" in seen }

        assertEquals(
            "Sitios de la allow-list que ya no existen. Si el codigo cambio de forma, actualiza ALLOWED: " +
                missing.joinToString("; ") { "${it.function}: ${it.line}" },
            emptyList<Site>(),
            missing,
        )
    }

    @Test
    fun `the data path never hands a raw String to the socket`() {
        val sends = sends()

        // Las unicas formas legitimas: el sobre de datos construido por el
        // codec, y el JSON de control ya serializado. `String(payload)` — el
        // defecto — no esta, y no hay ninguna otra via.
        fun isAllowed(argument: String): Boolean =
            argument.startsWith("messageEnvelope(") ||
                argument == "msg.toString()" ||
                argument == "pong.toString()"

        val rejected = sends.filterNot { isAllowed(it.substringAfter("-> ")) }
        assertEquals(
            "Llamadas a send() con una forma no permitida:\n" + rejected.joinToString("\n"),
            emptyList<String>(),
            rejected,
        )

        val overTheCodec = sends.filter { it.contains("messageEnvelope(") }.map { it.substringBefore(" ->") }
        assertEquals(
            "send() y drainPendingMessages() deben enviar UN frame cada uno por el codec, y no mas: $overTheCodec",
            listOf("RelayClient.kt:drainPendingMessages", "RelayClient.kt:send"),
            overTheCodec.sorted(),
        )
    }

    // =====================================================================
    // Analisis de fuentes
    // =====================================================================

    /**
     * Toda linea que convierte BYTES en TEXTO, con la funcion que la contiene.
     *
     * El detector es deliberadamente estrecho: `String(<algo>)` (el
     * constructor que produjo el defecto) y `.toByteArray()` (la operacion
     * inversa). `optString`, `getString`, `toString` y `encodeToString` no se
     * cuentan: devuelven o producen texto que ES texto, no un byte disfrazado.
     */
    private fun sites(): List<Site> {
        val found = mutableListOf<Site>()
        for ((name, source) in sources()) {
            var function = "<top level>"
            for (raw in source.lines()) {
                val line = code(raw)
                if (line.isEmpty()) continue
                FUNCTION_DECLARATION.find(line)?.let { function = it.groupValues[1] }
                if (!isByteToText(line)) continue
                found += Site(file = name, function = function, line = line, why = "")
            }
        }
        return found
    }

    private fun sends(): List<String> {
        val found = mutableListOf<String>()
        for ((name, source) in sources()) {
            var function = "<top level>"
            for (raw in source.lines()) {
                val line = code(raw)
                if (line.isEmpty()) continue
                FUNCTION_DECLARATION.find(line)?.let { function = it.groupValues[1] }
                val argument = SEND.find(line)?.groupValues?.get(1)?.trim()
                if (argument != null) found += "$name:$function -> $argument"
            }
        }
        return found
    }

    private fun isByteToText(line: String): Boolean =
        STRING_CONSTRUCTOR.containsMatchIn(line) || TO_BYTE_ARRAY.containsMatchIn(line)

    /**
     * La linea SIN comentarios ni documentacion.
     *
     * Sin esto, el KDoc que explica por que existio el fallo se contaria como
     * el fallo: hay que distinguir el texto que DOCUMENTA la conversion de la
     * conversion que la HACE.
     */
    private fun code(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return raw.substringBefore("//").trim()
    }

    private fun sources(): List<Pair<String, String>> =
        networkSources().map { it.name to it.readText() }

    /**
     * Las fuentes del camino de datos del relé. Localizacion explicita y con
     * fallo ruidoso: si el test no encuentra los ficheros, FALLA. Nunca pasa
     * por no haber comprobado nada.
     */
    private fun networkSources(): List<File> {
        val start = System.getProperty("user.dir")
            ?: throw AssertionError("No hay `user.dir`: este test NO se salta, falla.")
        val roots = generateSequence(File(start)) { it.parentFile }.take(8)
        for (root in roots) {
            for (relative in RELATIVE_ROOTS) {
                val dir = File(root, "$relative/com/example/keymessage/network")
                if (dir.isDirectory) {
                    return listOf(File(dir, RELAY_CLIENT), File(dir, RELAY_DATA_CODEC))
                }
            }
        }
        throw AssertionError(
            "No se localizaron las fuentes del relé desde $start. Este test NO se salta: falla."
        )
    }

    private companion object {
        const val RELAY_CLIENT = "RelayClient.kt"
        const val RELAY_DATA_CODEC = "RelayDataCodec.kt"

        val RELATIVE_ROOTS = listOf("src/main/java", "app/src/main/java")

        /** `String(x)`: el constructor que destruyo el payload. */
        val STRING_CONSTRUCTOR = Regex("(?<![\\w.])String\\(")

        val TO_BYTE_ARRAY = Regex("\\.toByteArray\\(")

        val SEND = Regex("\\.send\\((.*)\\)")

        /** `fun nombre(` a cualquier nivel: basta para ubicar el sitio. */
        val FUNCTION_DECLARATION = Regex("fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    }
}