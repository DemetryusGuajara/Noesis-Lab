package com.example.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.LlmPrefs
import com.example.ai.LocalLlmEngine
import com.example.i18n.StringsCatalog
import kotlinx.coroutines.launch

/**
 * First-run model setup dialog (first screen): explains that a neural model
 * improves reading accuracy, offers internal/SD storage choice (SD only when
 * detected), full/lite model choice, Ollama host field, OK to download
 * (ollama pull with live progress) and an option to import an on-device .task.
 */
@Composable
fun ModelSetupDialog(
    strings: StringsCatalog,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localEngine = remember { LocalLlmEngine(context) }

    var storage by remember { mutableStateOf(LlmPrefs.getStorage(context)) }
    var modelChoice by remember {
        mutableStateOf(
            when (LlmPrefs.getSlot(context)) {
                LlmPrefs.SLOT_LITE -> "lite"
                LlmPrefs.SLOT_ULTRA -> "ultra"
                else -> "full"
            }
        )
    }
    var dontShow by remember { mutableStateOf(false) }
    var phase by remember { mutableIntStateOf(0) } // 0 = choices, 1 = progress, 2 = done
    var progress by remember { mutableFloatStateOf(-1f) }
    var statusText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastPct by remember { mutableIntStateOf(-1) }

    val hasSd = remember { localEngine.hasSdCard() }
    if (!hasSd && storage == LlmPrefs.STORAGE_SD) {
        storage = LlmPrefs.STORAGE_INTERNAL
    }

    fun postProgress(p: Float, doneMb: Long, totalMb: Long, status: String) {
        val pct = if (p >= 0f) (p * 100).toInt() else -1
        if (pct != lastPct || statusText != status) {
            lastPct = pct
            progress = p
            statusText = if (totalMb > 0 && p >= 0f) {
                "$status · $pct% ($doneMb/$totalMb MB)"
            } else if (p >= 0f) {
                "$status · $pct%"
            } else if (status.isNotBlank()) {
                status
            } else {
                statusText
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            phase = 1
            errorText = null
            progress = -1f
            statusText = "importando..."
            lastPct = -1
            LlmPrefs.setStorage(context, storage)
            try {
                val cr = context.contentResolver
                var size = -1L
                cr.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && c.moveToFirst()) size = c.getLong(idx)
                }
                val stream = cr.openInputStream(uri)
                if (stream == null) {
                    errorText = "Não foi possível abrir o arquivo."
                } else {
                    val res = localEngine.importFromStream(stream, size) { p, done, total ->
                        postProgress(p, done / (1024 * 1024), total / (1024 * 1024), "importando")
                    }
                    if (res.isSuccess) {
                        phase = 2
                        statusText = "ok"
                    } else {
                        errorText = res.exceptionOrNull()?.message
                    }
                }
            } catch (e: Exception) {
                errorText = e.message
            }
        }
    }

    fun startDownload() {
        scope.launch {
            phase = 1
            errorText = null
            progress = -1f
            statusText = "conectando..."
            lastPct = -1
            LlmPrefs.setStorage(context, storage)
            LlmPrefs.setSlot(context, when (modelChoice) {
                "lite" -> LlmPrefs.SLOT_LITE
                "ultra" -> LlmPrefs.SLOT_ULTRA
                else -> LlmPrefs.SLOT_FULL
            })
            // Recria o engine para que o slot escolhido valha imediatamente.
            val engine = LocalLlmEngine(context)
            engine.onStateChange = { s ->
                when (s) {
                    is LocalLlmEngine.State.Downloading -> postProgress(
                        s.progress, s.downloadedMb, s.totalMb, "baixando"
                    )
                    is LocalLlmEngine.State.Initializing -> postProgress(
                        s.progress, -1L, -1L, "inicializando"
                    )
                    is LocalLlmEngine.State.Error -> { errorText = s.message }
                    else -> {}
                }
            }
            val res = engine.initialize()
            if (res.isSuccess) {
                phase = 2
                statusText = "ok"
            } else if (errorText == null) {
                errorText = res.exceptionOrNull()?.message
            }
        }
    }

    fun dismiss() {
        if (dontShow) LlmPrefs.setNoticeDismissed(context, true)
        onClose()
    }

    AlertDialog(
        onDismissRequest = { if (phase != 1) dismiss() },
        title = { Text(strings.modelDialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (phase == 0) {
                    Text(
                        text = strings.modelDialogBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = strings.modelStorageInternal + " / " + strings.modelStorageSd,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    StorageOptionRow(
                        label = strings.modelStorageInternal,
                        selected = storage == LlmPrefs.STORAGE_INTERNAL,
                        onSelect = { storage = LlmPrefs.STORAGE_INTERNAL }
                    )
                    if (hasSd) {
                        StorageOptionRow(
                            label = strings.modelStorageSd,
                            selected = storage == LlmPrefs.STORAGE_SD,
                            onSelect = { storage = LlmPrefs.STORAGE_SD }
                        )
                    }

                    ModelOptionRow(
                        title = strings.modelChoiceFull,
                        desc = strings.modelChoiceFullDesc,
                        selected = modelChoice == "full",
                        onSelect = { modelChoice = "full" }
                    )
                    ModelOptionRow(
                        title = strings.modelChoiceLite,
                        desc = strings.modelChoiceLiteDesc,
                        selected = modelChoice == "lite",
                        onSelect = { modelChoice = "lite" }
                    )
                    ModelOptionRow(
                        title = strings.modelChoiceUltra,
                        desc = strings.modelChoiceUltraDesc,
                        selected = modelChoice == "ultra",
                        onSelect = { modelChoice = "ultra" }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = strings.modelDialogDontShow,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (phase == 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    errorText?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                0 -> Button(
                    onClick = { startDownload() },
                    modifier = Modifier.testTag("model_dialog_ok_button")
                ) { Text(strings.modelDialogOk) }
                1 -> TextButton(onClick = {}, enabled = false) { Text("...") }
                else -> Button(onClick = { dismiss() }) { Text("OK") }
            }
        },
        dismissButton = {
            if (phase == 0) {
                Row {
                    OutlinedButton(
                        onClick = {
                            LlmPrefs.setStorage(context, storage)
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.testTag("model_dialog_import_button")
                    ) { Text(".task") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { dismiss() }) { Text(strings.modelDialogLater) }
                }
            } else if (phase == 1 && errorText != null) {
                TextButton(onClick = { phase = 0 }) { Text(strings.modelDialogLater) }
            }
        }
    )
}

@Composable
private fun StorageOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelOptionRow(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
