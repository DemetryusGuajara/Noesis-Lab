package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ChatMessageEntity
import com.example.i18n.StringsCatalog
import com.example.ui.screens.FormattedMarkdownText

/**
 * Fase 3: thread de conversa da leitura (chat). Histórico imutável +
 * caixa de resposta sempre aberta; cada resposta gera texto adaptado +
 * nova leva de exercícios.
 */
@Composable
fun ChatThread(
    messages: List<ChatMessageEntity>,
    strings: StringsCatalog,
    sendEnabled: Boolean,
    isSimulating: Boolean,
    progressText: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var replyLocal by rememberSaveable { mutableStateOf("") }

    // The reading card/article already shows the first assistant message: never repeat it here.
    val visible = remember(messages) {
        val firstAssistant = messages.indexOfFirst { it.role == "assistant" }
        if (firstAssistant >= 0) messages.filterIndexed { i, _ -> i != firstAssistant }
        else messages
    }

    Column(modifier = modifier.fillMaxWidth()) {
        visible.forEach { m: ChatMessageEntity ->
            if (m.role == "user") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        FormattedMarkdownText(
                            markdown = m.text,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (m.somaticText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            ) {
                                FormattedMarkdownText(
                                    markdown = m.somaticText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        if (isSimulating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = replyLocal,
                onValueChange = { replyLocal = it },
                placeholder = { Text(strings.uiThreadReplyPlaceholder) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("thread_reply_input")
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val clean = replyLocal.trim()
                    if (clean.isNotBlank() && sendEnabled) {
                        replyLocal = ""
                        onSend(clean)
                    }
                },
                enabled = sendEnabled && replyLocal.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("thread_reply_send")
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = strings.uiSend,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
