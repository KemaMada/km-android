package com.example.keymessage.ui

import com.example.keymessage.data.ConversationGateway
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.data.RECIPIENT_LOG_TAG
import com.example.keymessage.data.isValidRecipient
import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.km.api.KeyMessageCore
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.protocol.ConnectionState
import com.km.protocol.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identidad local fija para los fakes (nunca se envia fuera del test). */
private val localIdentity = IdentityId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

/**
 * 2.3 (D1) y 2.4 (D3).
 *
 * D1/R3: un destinatario que no sea exactamente 64 hex minusculas se rechaza
 * ANTES de llegar a un sobre `MESSAGE`. El `IdentityId` de `km-core` es un
 * `value class` sin validacion, asi que el literal de 62 hex que D1 documento
 * se CONSTRUYE sin error: si no se filtra, `RelayDataCodec` lo pondria en el
 * campo `to` y el relevo descartaria el frame entero ("MESSAGE descartado").
 *
 * D3/R4: el estado de conexion de la UI se recolecta del transporte VIVO
 * (`AppContainer.connectionState`). Antes no se recolectaba nada y
 * `uiState.connectionState` quedaba clavado en DISCONNECTED aunque el core
 * estuviera ONLINE.
 *
 * El VM se construye con un `ConversationGateway` falso y un `scope` inyectado
 * (`Dispatchers.Unconfined`): en JVM no existe `Dispatchers.Main`, asi que
 * `viewModelScope` no puede usarse en el test.
 */
class ConversationViewModelTest {

    /** El `IdentityId` de relevo real de la app en la E2E (obs #124). */
    private val validPeer = IdentityId("6e567d78a536041bd04854ba93f09fb0eca9a087418b918d6f5ab838354ca89f")

    /** El literal exacto que D1 documento: 62 hex, no 64 (MainActivity.kt:94). */
    private val placeholder62Hex = IdentityId("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0")

    // =====================================================================
    // 2.4 / D3: estado de conexion veridico
    // =====================================================================

    @Test
    fun `el estado de conexion refleja el ONLINE del transporte - D3`() {
        val transportState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val vm = ConversationViewModel(
            FakeGateway(), FakeCore(), validPeer, "Peer",
            transportState,
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )

        transportState.value = ConnectionState.ONLINE

        assertEquals(
            "el transporte esta ONLINE: la UI tiene que mostrar ONLINE",
            ConnectionState.ONLINE,
            vm.uiState.value.connectionState,
        )
        assertNotEquals(
            "ONLINE nunca puede pintarse como Disconnected (D3)",
            ConnectionState.DISCONNECTED,
            vm.uiState.value.connectionState,
        )
    }

    @Test
    fun `el estado de conexion se siembra con el estado actual del transporte - D3`() {
        val transportState = MutableStateFlow(ConnectionState.ONLINE)

        val vm = ConversationViewModel(
            FakeGateway(), FakeCore(), validPeer, "Peer",
            transportState,
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(ConnectionState.ONLINE, vm.uiState.value.connectionState)
    }

    @Test
    fun `el estado de conexion tambien refleja la bajada real del transporte - D3`() {
        val transportState = MutableStateFlow(ConnectionState.ONLINE)
        val vm = ConversationViewModel(
            FakeGateway(), FakeCore(), validPeer, "Peer",
            transportState,
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )

        transportState.value = ConnectionState.DISCONNECTED

        assertEquals(
            "si el transporte cae de verdad, Disconnected es veridico y se muestra",
            ConnectionState.DISCONNECTED,
            vm.uiState.value.connectionState,
        )
    }

    // =====================================================================
    // 2.3 / D1: rechazo de destinatarios no-64-hex
    // =====================================================================

    @Test
    fun `el literal de D1 tiene 62 hex - el test fija el defecto`() {
        // Si alguien "arregla" el literal haciendolo de 64, ya no es el defecto
        // D1 y este test tiene que avisar: el placeholder de 62 hex es el caso
        // exacto que el relevo rechazaba descartando el frame entero.
        assertEquals(62, placeholder62Hex.value.length)
    }

    @Test
    fun `un destinatario de 62 hex se bloquea antes de llegar al gateway - D1`() {
        val gateway = FakeGateway()
        val logs = mutableListOf<Pair<String, String>>()
        val vm = ConversationViewModel(
            gateway, FakeCore(), placeholder62Hex, "placeholder",
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
            log = { tag, msg -> logs.add(tag to msg) },
        )

        vm.sendMessage("canary")

        assertTrue("el envio no puede salir hacia el gateway", gateway.sent.isEmpty())
        assertTrue("el bloqueo no puede ser silencioso", logs.any { it.first == RECIPIENT_LOG_TAG })
    }

    @Test
    fun `cualquier destinatario no-64-hex se rechaza - D1`() {
        val invalid = listOf(
            IdentityId(""), // vacio
            IdentityId("0123456789abcdef0123456789abcdef01234567"), // 40 hex: espacio DHT legacy
            placeholder62Hex, // 62 hex: el bug documentado en D1
            IdentityId("g".repeat(64)), // 64, pero no hex
            IdentityId("A".repeat(64)), // 64 hex en MAYUSCULAS: el contrato es minusculas
            IdentityId("0".repeat(63)),
            IdentityId("0".repeat(65)),
        )
        for (peerId in invalid) {
            assertFalse(
                "debe rechazarse un destinatario de ${peerId.value.length} caracteres",
                isValidRecipient(peerId),
            )
        }
    }

    @Test
    fun `un destinatario real de 64 hex pasa el filtro - D1`() {
        assertTrue(isValidRecipient(validPeer))
    }

    @Test
    fun `con un destinatario real el envio llega al gateway`() {
        val gateway = FakeGateway()
        val vm = ConversationViewModel(
            gateway, FakeCore(), validPeer, "Peer",
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )

        vm.sendMessage("canary")

        assertEquals("el envio valido tiene que llegar", 1, gateway.sent.size)
        assertEquals(validPeer, gateway.sent[0].first)
        assertEquals("canary", String(gateway.sent[0].second))
    }

    // =====================================================================
    // 2.3 / D1: segunda barrera en ConversationRepository
    // =====================================================================

    @Test
    fun `el repositorio rechaza un destinatario de 62 hex antes de tocar el core`() {
        val core = FakeCore()
        val repo = ConversationRepository(core, FakeMessageDao(), localIdentity)

        val result = runBlocking { repo.sendMessage(placeholder62Hex, "canary".toByteArray()) }

        assertTrue("el intento invalido tiene que fallar", result.isFailure)
        assertTrue("createMessage no puede haberse llamado", core.created.isEmpty())
        assertTrue("sendMessage no puede haberse llamado", core.sent.isEmpty())
    }

    @Test
    fun `el repositorio pasa un destinatario real al core`() {
        val core = FakeCore()
        val repo = ConversationRepository(core, FakeMessageDao(), localIdentity)

        val result = runBlocking { repo.sendMessage(validPeer, "canary".toByteArray()) }

        assertTrue("un destinatario real tiene que enviarse", result.isSuccess)
        assertEquals(1, core.created.size)
        assertEquals(validPeer, core.created[0].first)
        assertEquals("canary", String(core.created[0].second))
    }

    // =====================================================================
    // Fakes
    // =====================================================================

    private class FakeGateway : ConversationGateway {
        val sent = mutableListOf<Pair<IdentityId, ByteArray>>()

        override fun observeConversation(peerId: IdentityId): Flow<List<Message>> = emptyFlow()

        override suspend fun sendMessage(to: IdentityId, payload: ByteArray): Result<Unit> {
            sent.add(to to payload.copyOf())
            return Result.success(Unit)
        }
    }

    private class FakeCore : KeyMessageCore {
        val created = mutableListOf<Pair<IdentityId, ByteArray>>()
        val sent = mutableListOf<Message>()
        val starts = mutableListOf<Unit>()

        override fun start(): Result<Unit> {
            starts.add(Unit)
            return Result.success(Unit)
        }

        override fun stop() {}

        override fun createMessage(to: IdentityId, payload: ByteArray): Result<Message> {
            created.add(to to payload.copyOf())
            return Result.success(
                Message(
                    messageId = MessageId.random(),
                    from = localIdentity,
                    to = to,
                    payload = payload.copyOf(),
                    timestamp = 0L,
                )
            )
        }

        override fun sendMessage(message: Message): Result<SendResult> {
            sent.add(message)
            return Result.success(SendResult(message.messageId, MessageState.SENT))
        }

        override fun getMessageState(messageId: MessageId): Result<MessageState> =
            Result.success(MessageState.SENT)

        override fun getConversation(peerId: IdentityId): Result<List<Message>> =
            Result.success(emptyList())

        override fun registerAckHandler(handler: (Ack) -> Unit) {}
        override fun registerMessageHandler(handler: (Message) -> Unit) {}
        override fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit) {}
    }

    private class FakeMessageDao : MessageDao {
        override suspend fun insert(message: MessageEntity) {}
        override suspend fun updateState(messageId: String, state: String, failureCode: String?) {}
        override suspend fun get(messageId: String): MessageEntity? = null
        override suspend fun listConversation(id1: String, id2: String): List<MessageEntity> = emptyList()
        override fun observeConversation(id1: String, id2: String): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun delete(messageId: String) {}
    }
}