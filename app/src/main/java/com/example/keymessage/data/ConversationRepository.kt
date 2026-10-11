package com.example.keymessage.data

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.example.keymessage.util.LogBuffer
import com.km.api.KeyMessageCore
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Etiqueta de `LogBuffer` para el rechazo de destinatarios no-64-hex. R3/2.3. */
internal const val RECIPIENT_LOG_TAG: String = "RECIPIENT"

/**
 * R3/2.3. ¿Es [peerId] un destinatario al que el relevo sepa entregar?
 *
 * `IdentityId` es un `value class` sin validacion: cualquier string se
 * construye, incluido el literal de 62 hex de 62 caracteres que D1 documento, y
 * `RelayDataCodec` lo pondria tal cual en el campo `to` del sobre. El relevo
 * `JsonWireCodec.identityId` exige exactamente `64` caracteres para no
 * descartar el frame entero ("MESSAGE descartado: carga Base64 invalida o
 * envelope incompleto").
 *
 * Los 64 hex del contrato (KM-ID-0001 seccion 6) se derivan SIEMPRE en
 * minusculas (`ContactIdentity`), de modo que un destinatario valido no puede
 * llegar aqui en mayusculas: se rechazan.
 */
internal fun isValidRecipient(peerId: IdentityId): Boolean {
    val hex = peerId.value
    return hex.length == 64 && hex.all { it in "0123456789abcdef" }
}

/**
 * La parte de [ConversationRepository] que [com.example.keymessage.ui.ConversationViewModel]
 * consume, declarada como interfaz para que el ViewModel se pruebe en JVM sin
 * Room ni core reales.
 *
 * Cumple el contrato de 2.3 en las DOS capas: el ViewModel rechaza antes de
 * llamar, y el repositorio lo vuelve a rechazar antes de tocar el core.
 */
interface ConversationGateway {
    fun observeConversation(peerId: IdentityId): Flow<List<Message>>
    suspend fun sendMessage(to: IdentityId, payload: ByteArray): Result<Unit>
}

class ConversationRepository(
    private val core: KeyMessageCore,
    private val messageDao: MessageDao,
    private val localIdentity: IdentityId
) : ConversationGateway {

    override fun observeConversation(peerId: IdentityId): Flow<List<Message>> {
        return messageDao.observeConversation(localIdentity.value, peerId.value)
            .map { entities -> entities.map { it.toMessage() } }
    }

    /**
     * R3/2.3. Envia solo si [to] es exactamente 64 hex minusculas.
     *
     * El rechazo ocurre ANTES de `createMessage`: un destinatario invalido no
     * debe poder convertirse nunca en un `MESSAGE` codificado ni en una fila de
     * la cola. El bloqueo aqui es la segunda barrera configurada por el
     * ViewModel — la primera —, y en el log no entra ni el payload ni el
     * plaintext del mensaje: solo la identidad del destinatario, que es
     * publica y ya aparece en el log del directorio de claves (R2).
     */
    override suspend fun sendMessage(to: IdentityId, payload: ByteArray): Result<Unit> {
        if (!isValidRecipient(to)) {
            LogBuffer.log(
                RECIPIENT_LOG_TAG,
                "destinatario ${to.value} rechazado antes del sobre: no son 64 hex minusculas; no se crea ni se envia el mensaje",
            )
            return Result.failure(
                IllegalArgumentException("recipient is not a 64-hex IdentityId: ${to.value}"),
            )
        }
        val message = core.createMessage(to, payload).getOrElse { return Result.failure(it) }
        val result = core.sendMessage(message)
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(Exception("Send failed: ${result.getOrNull()}"))
    }
}
