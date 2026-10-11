package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.keymessage.ContactIdentity
import com.example.keymessage.model.Contact
import com.example.keymessage.ui.ConversationViewModel
import com.km.model.MessageState
import com.km.protocol.ConnectionState

/**
 * La pantalla "nuevo chat" del relevo.
 *
 * 2.2/D1: el destinatario SIEMPRE es un contacto real guardado, nunca un
 * literal. [contacts] es la lista guardada y [onSelect] entrega el id del
 * contacto elegido; [viewModel] solo existe cuando el contacto seleccionado
 * tiene una clave Ed25519 valida de la que derivar un `IdentityId` de 64 hex.
 * Sin [viewModel] la pantalla muestra el picker y no hay campo de envio: un
 * mensaje no puede salir sin un destinatario real.
 */
@Composable
fun NewChatScreen(
    contacts: List<Contact>,
    onSelect: (String?) -> Unit,
    viewModel: ConversationViewModel?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp)
    ) {
        if (viewModel == null) {
            // Sin contacto seleccionado (o con clave invalida) no hay
            // conversacion: el envio no existe hasta que se elige uno real.
            ContactPicker(contacts, onSelect)
        } else {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // 2.4/D3: el indicador refleja [ConnectionState] recolectado del
            // transporte vivo (AppContainer.connectionState): ONLINE se muestra
            // como "Online", nunca como Disconnected.
            ConnectionIndicator(uiState.connectionState)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(null) }) {
                    Text("‹ Contactos", color = Color(0xFF0F3460), fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    uiState.peerName,
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            MessageList(
                messages = uiState.messages,
                peerName = uiState.peerName,
                modifier = Modifier.weight(1f)
            )

            MessageInput(viewModel = viewModel)
        }
    }
}

@Composable
private fun ContactPicker(
    contacts: List<Contact>,
    onSelect: (String?) -> Unit
) {
    LazyColumn {
        item {
            Text(
                "Nuevo chat — elige un contacto",
                color = Color(0xFFE0E0E0),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        // Solo los contactos cuya clave publica deriva un IdentityId de 64 hex
        // son seleccionables: un contacto sin clave Ed25519 no puede ser
        // destinatario del relevo y se muestra deshabilitado (2.2/D1).
        items(contacts, key = { it.id }) { contact ->
            val enabled = remember(contact.publicKey) {
                ContactIdentity.identityIdOf(contact.publicKey) != null
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(contact.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    contact.name,
                    color = if (enabled) Color(0xFFE0E0E0) else Color(0xFF666666),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (!enabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "(sin clave Ed25519)",
                        color = Color(0xFF666666),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onSelect(null) }) {
                Text("Back", color = Color(0xFF0F3460), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.ONLINE -> Color(0xFF00FF00) to "Online"
        ConnectionState.CONNECTING -> Color(0xFFFFFF00) to "Connecting..."
        ConnectionState.AUTHENTICATING -> Color(0xFFFFFF00) to "Authenticating..."
        ConnectionState.RECONNECTING -> Color(0xFFFFA500) to "Reconnecting..."
        ConnectionState.DISCONNECTED -> Color(0xFFFF0000) to "Disconnected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MessageList(
    messages: List<com.km.model.Message>,
    peerName: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(messages, key = { it.messageId.value.toString() }) { msg ->
            val isSent = msg.state == MessageState.SENT || msg.state == MessageState.DELIVERED
            val bgColor = if (isSent) Color(0xFF16213E) else Color(0xFF0F3460)
            val align = if (isSent) Alignment.End else Alignment.Start

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalAlignment = align
            ) {
                Box(
                    modifier = Modifier
                        .background(bgColor, shape = MaterialTheme.shapes.medium)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = String(msg.payload),
                            color = Color(0xFFE0E0E0),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = stateLabel(msg.state),
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    viewModel: ConversationViewModel
) {
    var inputText by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFE0E0E0),
                focusedBorderColor = Color(0xFF0F3460),
                unfocusedBorderColor = Color(0xFF16213E)
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                viewModel.sendMessage(inputText)
                inputText = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F3460))
        ) {
            Text("Send", fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = { viewModel.retry() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE0E0E0))
        ) {
            Text("Retry", fontFamily = FontFamily.Monospace)
        }
    }
}

private fun stateLabel(state: MessageState): String = when (state) {
    MessageState.CREATED -> "created"
    MessageState.QUEUED -> "queued"
    MessageState.SENDING -> "sending"
    MessageState.SENT -> "sent"
    MessageState.DELIVERED -> "delivered"
    MessageState.FAILED -> "failed"
    MessageState.EXPIRED -> "expired"
}