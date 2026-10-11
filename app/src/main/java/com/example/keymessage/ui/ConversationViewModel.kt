package com.example.keymessage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.keymessage.data.ConversationGateway
import com.example.keymessage.data.RECIPIENT_LOG_TAG
import com.example.keymessage.data.isValidRecipient
import com.example.keymessage.util.LogBuffer
import com.km.api.KeyMessageCore
import com.km.model.IdentityId
import com.km.model.Message
import com.km.protocol.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConversationUiState(
    val messages: List<Message> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val peerId: IdentityId = IdentityId(""),
    val peerName: String = ""
)

class ConversationViewModel(
    private val gateway: ConversationGateway,
    private val core: KeyMessageCore,
    private val peerId: IdentityId,
    private val peerName: String,
    connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED),
    injectedScope: CoroutineScope? = null,
    private val log: (String, String) -> Unit = LogBuffer::log,
) : ViewModel() {

    /**
     * El alcance de las corrutinas del VM. Por defecto `viewModelScope` (se
     * cancela con el VM); los tests inyectan uno controlado porque en JVM no
     * existe `Dispatchers.Main`. Como parametro por defecto `viewModelScope`
     * no resuelve (no hay receiver `this` en la cabecera), por eso se resuelve
     * aqui, en el cuerpo.
     */
    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _uiState = MutableStateFlow(
        ConversationUiState(peerId = peerId, peerName = peerName)
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        // Los mensajes de la conversacion con el peer, por el gateway.
        scope.launch {
            gateway.observeConversation(peerId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }

        // 2.4/D3: el estado de conexion hay que COLECCIONARLO, no asumirlo.
        // Antes el ViewModel nunca recogia el estado del transporte y
        // `uiState.connectionState` quedaba clavado en su inicial
        // `DISCONNECTED` aunque el core estuviera ONLINE: el indicador de la
        // UI mentia. R4 obliga a que la pantalla refleje el estado REAL, asi
        // que aqui se recolecta el `StateFlow` vivo que `AppContainer`
        // mantiene a partir de `transport.onStateChange`, y cada transicion
        // del transporte se propaga a la UI.
        scope.launch {
            connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 2.3/R3: el destinatario tiene que ser 64 hex minusculas ANTES de
        // llegar a un sobre. `IdentityId` no valida al construirse (un literal
        // de 62 hex como el de D1 se crea sin error) y el relevo descarta el
        // frame entero si `to` no mide 64. Rechazar aqui es la primera
        // barrera; `ConversationRepository` la vuelve a aplicar como segunda.
        if (!isValidRecipient(peerId)) {
            log(
                RECIPIENT_LOG_TAG,
                "destinatario ${peerId.value} rechazado: no son 64 hex minusculas; el mensaje no se envia",
            )
            return
        }

        scope.launch {
            gateway.sendMessage(peerId, text.toByteArray())
        }
    }

    fun retry() {
        scope.launch {
            core.start()
        }
    }

    class Factory(
        private val repository: ConversationGateway,
        private val core: KeyMessageCore,
        private val peerId: IdentityId,
        private val peerName: String,
        private val connectionState: StateFlow<ConnectionState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConversationViewModel(repository, core, peerId, peerName, connectionState) as T
        }
    }
}