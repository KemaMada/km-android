package com.example.keymessage.util

/**
 * R7 / tarea 2.8. Superficie de depuracion legible por adb sin depurador.
 *
 * QUE PROBLEMA RESUELVE
 *
 * La prueba E2E se maneja por `adb` y necesita afirmar hechos que solo existen
 * dentro del proceso: la propia `IdentityId`, el endpoint de relevo efectivo,
 * el ultimo `messageId` enviado y recibido, el estado de entrega y el estado de
 * conexion. Antes ninguno de esos valores salia del proceso salvo por la UI, y
 * un test de `adb` no puede tocar la UI con fiabilidad. Aqui cada valor se
 * escribe tambien en [LogBuffer], que persiste en `files/km-debug.log`, de modo
 * que:
 *
 *     adb exec-out run-as com.example.keymessage cat files/km-debug.log
 *
 * (build de debug, `isDebuggable = true`) o la pantalla "Debug Log" los
 * muestran sin tocar nada mas.
 *
 * QUE NUNCA EXPONE
 *
 * Solo identificadores publicos (`IdentityId`, `MessageId` -un UUID-), la URL
 * del endpoint y nombres de estado. Jamas el payload, el plaintext ni la clave.
 * Es una lista de identidades y estados, no de contenidos.
 *
 * POR QUE ES UN OBJETO, NO UN PARAMETRO
 *
 * Los valores los producen sitios muy distintos (el contenedor al arrancar, el
 * repositorio al enviar/observar). Inyectarlos por constructor obligaria a
 * atravesar medio grafo de dependencias para dejar una traza de depuracion.
 * El objeto es un punto de escritura unico y JVM-puro (no toca Android), asi
 * que se prueba en JVM.
 */
object DebugSurface {

    /** Etiqueta de `LogBuffer` bajo la que salen los valores afirmables. */
    const val TAG: String = "DEBUG"

    private val values = LinkedHashMap<String, String>()

    /**
     * Destino de las lineas. Por defecto [LogBuffer] (que las guarda en memoria
     * y en `km-debug.log`); `setSink` lo cambia en tests.
     */
    private var sink: (String, String) -> Unit = { tag, msg -> LogBuffer.log(tag, msg) }

    /** Identidad de relevo propia, 64 hex. R7. */
    fun setIdentityId(id: String) = put("identityId", id)

    /** Endpoint de relevo efectivo (override de runtime o default). R7. */
    fun setRelayEndpoint(url: String) = put("relayEndpoint", url)

    /** Estado de conexion del transporte: DISCONNECTED..ONLINE. R7. */
    fun setConnectionState(state: String) = put("connectionState", state)

    /** Ultimo `messageId` (UUID) que la app emitio. R7. */
    fun recordSent(messageId: String) = put("lastSentMessageId", messageId)

    /** Ultimo `messageId` (UUID) que la app recibio de un peer. R7. */
    fun recordReceived(messageId: String) = put("lastReceivedMessageId", messageId)

    /** Ultimo estado de entrega observado (MessageState). R7. */
    fun setDeliveryState(state: String) = put("deliveryState", state)

    private fun put(key: String, value: String) {
        val changed = synchronized(values) {
            if (values[key] == value) false else { values[key] = value; true }
        }
        // Solo se emite cuando CAMBIA: el log no se llena con el mismo valor
        // repetido y una linea siempre significa una transicion real.
        if (changed) sink(TAG, "$key=$value")
    }

    /**
     * Copia de los valores actuales. Es lo que un test JVM afirma directamente;
     * `adb` lee las lineas de `km-debug.log`.
     */
    fun snapshot(): Map<String, String> = synchronized(values) { LinkedHashMap(values) }

    /** Limpia la superficie. Para tests y para un estado inicial conocido. */
    fun reset() {
        synchronized(values) { values.clear() }
    }

    /**
     * Sustituye el destino de las lineas. `internal` para no ofrecerlo como API
     * de produccion; solo los tests lo usan para capturar sin depender del
     * [LogBuffer] global.
     */
    internal fun setSink(capture: (String, String) -> Unit) {
        sink = capture
    }

    /** Restaura el destino por defecto ([LogBuffer]). */
    internal fun resetSink() {
        sink = { tag, msg -> LogBuffer.log(tag, msg) }
    }
}
