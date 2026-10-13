package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.LocalLlmEngine
import com.example.ai.LlmPrefs
import com.example.BuildConfig
import com.example.R
import com.example.data.JournalEntryEntity
import com.example.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.computeSynestheticSphereColor
import com.example.ui.theme.formatConceptTitleCase
import com.example.util.AppLog
import com.example.util.LogCollector
import com.example.viewmodel.NoesisUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemsAndByokScreen(
    uiState: NoesisUiState,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSaveCurrentStateToJournal: () -> Unit,
    onOpenJournalEntry: (JournalEntryEntity?) -> Unit,
    onDeleteJournalEntry: (Long) -> Unit,
    onVerifyAndSaveProvider: (String, String, String) -> Unit,
    onActivateProviderModel: (Long, String) -> Unit,
    onUseBuiltInEngine: () -> Unit,
    onDeleteProvider: (Long) -> Unit,
    onExportBackup: (android.net.Uri) -> Unit,
    onImportBackup: (android.net.Uri) -> Unit
) {
    val strings = uiState.strings
    var providerName by remember { mutableStateOf("") }
    var providerBaseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var providerApiKey by remember { mutableStateOf("") }
    var journalDropdownExpanded by remember { mutableStateOf(false) }

    val isDark = when (uiState.preferences.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportBackup(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportBackup(uri)
    }

    // Full-Screen Article Viewer Modal for Saved Logbook Entries (Item 16)
    uiState.openedJournalEntry?.let { entry ->
        JournalArticleModal(
            entry = entry,
            strings = strings,
            isDark = isDark,
            onClose = { onOpenJournalEntry(null) },
            onDelete = { onDeleteJournalEntry(entry.id) }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("systems_byok_screen")
    ) {
        // Section 1: Diário de Bordo (Logbook State Snapshots & Dropdown Article Viewer - Item 16)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("journal_logbook_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = strings.journalSectionTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.journalSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.journalSectionSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onSaveCurrentStateToJournal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_state_to_journal_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = strings.saveCurrentStateToJournalButton,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.saveCurrentStateToJournalButton)
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.journalEntries.isEmpty()) {
                    Text(
                        text = strings.journalEmptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { journalDropdownExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("journal_dropdown_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${strings.journalDropdownLabel} (${uiState.journalEntries.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = journalDropdownExpanded,
                            onDismissRequest = { journalDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.88f)
                        ) {
                            uiState.journalEntries.forEach { entry ->
                                val formattedDate = dateFormat.format(Date(entry.timestamp))
                                DropdownMenuItem(
                                    text = {
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = formattedDate,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = entry.titleSummary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    onClick = {
                                        journalDropdownExpanded = false
                                        onOpenJournalEntry(entry)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 2: Appearance & Multilingual Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = strings.appearanceSectionTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.appearanceSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.preferences.themeMode == ThemeMode.SYSTEM,
                        onClick = { onSelectTheme(ThemeMode.SYSTEM) },
                        label = { Text(strings.themeSystem) },
                        modifier = Modifier.testTag("theme_system_chip")
                    )
                    FilterChip(
                        selected = uiState.preferences.themeMode == ThemeMode.LIGHT,
                        onClick = { onSelectTheme(ThemeMode.LIGHT) },
                        label = { Text(strings.themeLight) },
                        modifier = Modifier.testTag("theme_light_chip")
                    )
                    FilterChip(
                        selected = uiState.preferences.themeMode == ThemeMode.DARK,
                        onClick = { onSelectTheme(ThemeMode.DARK) },
                        label = { Text(strings.themeDark) },
                        modifier = Modifier.testTag("theme_dark_chip")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = strings.languageSectionTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.languageSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = uiState.preferences.language == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = { Text("${lang.nativeName} (${lang.code.uppercase()})") },
                            modifier = Modifier.testTag("lang_chip_${lang.code}")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 3: SQLite Local Persistence & Portable JSON Backup/Restore
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sqlite_backup_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = strings.backupSectionTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.backupSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.backupSectionSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${strings.dbStatsLabel} ${uiState.particles.size} esferas · ${uiState.journalEntries.size} artigos no diário · ${uiState.logs.size} leituras",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            exportLauncher.launch("noesis_lab_backup_${System.currentTimeMillis()}.json")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = strings.exportBackupButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.exportBackupButton, style = MaterialTheme.typography.labelLarge)
                    }

                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_backup_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = strings.importBackupButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.importBackupButton, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 3.5: Modelo Qwen no aparelho (.task MediaPipe)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("local_llm_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            LocalLlmManagerCard(strings = strings)
        }

        if (com.example.BuildConfig.ENABLE_FILE_LOGGING) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dev_diagnostics_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                DevDiagnosticsCard(
                    providerSummaries = uiState.providers.map { p ->
                        "${p.name} (${p.selectedModel.ifBlank { "sem modelo" }})" + if (p.isActive) " [ativo]" else ""
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 4: OpenAI-Compatible BYOK Multi-Provider Manager
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("byok_providers_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = strings.byokSectionTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.byokSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.byokSectionSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val endpointPresets = listOf(
                        "OpenAI" to "https://api.openai.com/v1",
                        "OpenRouter" to "https://openrouter.ai/api/v1",
                        "Groq" to "https://api.groq.com/openai/v1",
                        "DeepSeek" to "https://api.deepseek.com/v1",
                        "Together" to "https://api.together.xyz/v1",
                        "Mistral" to "https://api.mistral.ai/v1"
                    )
                    endpointPresets.forEach { (label, url) ->
                        FilterChip(
                            selected = providerBaseUrl == url,
                            onClick = {
                                providerName = label
                                providerBaseUrl = url
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = providerName,
                    onValueChange = { providerName = it },
                    label = { Text(strings.providerNameLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = providerBaseUrl,
                    onValueChange = { providerBaseUrl = it },
                    label = { Text(strings.providerBaseUrlLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_url_input")
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = providerApiKey,
                    onValueChange = { providerApiKey = it },
                    label = { Text(strings.providerApiKeyLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_apikey_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        onVerifyAndSaveProvider(providerName, providerBaseUrl, providerApiKey)
                    },
                    enabled = !uiState.isVerifyingProvider && providerBaseUrl.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verify_ping_provider_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (uiState.isVerifyingProvider) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.verifyingModelsStatus, maxLines = 1)
                    } else {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = strings.verifyAndPingButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.verifyAndPingButton)
                    }
                }

                AnimatedVisibility(visible = uiState.lastVerificationReport != null) {
                    uiState.lastVerificationReport?.let { report ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (report.connectionSuccess) {
                                        "✓ ${report.verifiedResponsiveModels.size}/${report.totalDiscoveredModels} ${strings.verifiedModelsCount} (200 OK)"
                                    } else {
                                        "⚠ ${report.errorMessage ?: "Erro na verificação"}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (report.connectionSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                                )
                                if (report.pingDetails.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    report.pingDetails.forEach { detail ->
                                        val statusBadge = if (detail.respondedOk) {
                                            "✓ 200 OK (${detail.latencyMs}ms)"
                                        } else {
                                            "✗ HTTP ${detail.statusCode} (Filtrado)"
                                        }
                                        Text(
                                            text = "• ${detail.modelId} → $statusBadge",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (detail.respondedOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onUseBuiltInEngine,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = strings.builtInEngineLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.builtInEngineLabel, style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.height(10.dp))

                uiState.providers.forEach { provider ->
                    val verifiedModels = provider.verifiedModelsCsv
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (provider.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = provider.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteProvider(provider.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = strings.deleteProviderButton,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            if (verifiedModels.isEmpty()) {
                                Text(
                                    text = strings.noVerifiedModelsYet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "${verifiedModels.size} ${strings.verifiedModelsCount}:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    verifiedModels.forEach { modelId ->
                                        val isCurrentlyActive = provider.isActive && provider.selectedModel == modelId
                                        FilterChip(
                                            selected = isCurrentlyActive,
                                            onClick = { onActivateProviderModel(provider.id, modelId) },
                                            label = {
                                                Text(
                                                    text = if (isCurrentlyActive) "★ $modelId (${strings.activeModelBadge})" else modelId,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section 6: Sobre o Projeto (Version, GitHub, Apoio & Créditos)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("about_project_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val context = LocalContext.current
                val repoUrl = "https://github.com/mediamindlabs/noesis-lab"
                val profileUrl = "https://github.com/mediamindlabs"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Sobre",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sobre o Noesis Lab",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Botão com ícone do GitHub (sem texto, apenas o ícone oficial)
                    IconButton(
                        onClick = {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                            context.startActivity(browserIntent)
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("github_icon_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "Repositório no GitHub",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Versão atual dinâmica (atualiza a cada nova build)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "Versão: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(14.dp))

                // Apoio ao desenvolvimento com link para a página do GitHub (README com buy me a coffee)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Considere apoiar o desenvolvimento desse projeto",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Toque no ícone do GitHub para apoiar via Buy Me a Coffee no README",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                                context.startActivity(browserIntent)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = "Apoiar no GitHub",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // "Projeto desenvolvido com amor ❤️ Por MediaMind Labs" com link clicável para o perfil GitHub
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Projeto desenvolvido com amor ❤️ Por ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "MediaMind Labs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .testTag("mediamind_labs_link")
                            .clickable {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl))
                                context.startActivity(browserIntent)
                            }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/**
 * Full-Screen Editorial Article Viewer for a Saved Logbook Entry ("Diário de Bordo" - Item 16).
 * Displays the date/time header, rendered gravitational map with colors & gradients,
 * diagnosed mental rooms summary, user notes, and generated texts, plus a Delete button.
 */
@Composable
private fun JournalArticleModal(
    entry: JournalEntryEntity,
    strings: com.example.i18n.StringsCatalog,
    isDark: Boolean,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd 'de' MMMM 'de' yyyy · HH:mm", Locale.getDefault()) }
    val formattedDate = dateFormat.format(Date(entry.timestamp))
    val textMeasurer = rememberTextMeasurer()

    val parsedParticles = remember(entry.particlesSnapshotJson) {
        runCatching {
            val arr = JSONArray(entry.particlesSnapshotJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SnapshotParticle(
                    name = formatConceptTitleCase(o.optString("name", "Conceito")),
                    mass = o.optDouble("mass", 1.2).toFloat(),
                    charge = o.optDouble("charge", 0.4).toFloat(),
                    posX = o.optDouble("posX", 0.5).toFloat(),
                    posY = o.optDouble("posY", 0.5).toFloat()
                )
            }
        }.getOrDefault(emptyList())
    }

    val parsedZones = remember(entry.zonesSnapshotJson) {
        runCatching {
            val arr = JSONArray(entry.zonesSnapshotJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SnapshotZone(
                    customTitle = o.optString("customTitle", ""),
                    conceptDomain = o.optString("conceptDomain", ""),
                    clarity = o.optDouble("clarity", 0.7).toFloat(),
                    pressure = o.optDouble("pressure", 0.4).toFloat(),
                    empiricalInterpretation = o.optString("empiricalInterpretation", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Article Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DIÁRIO DE BORDO · REGISTRO DE ESTADO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.titleSummary,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = strings.journalArticleCloseButton,
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(14.dp))

                // 1. Snapshot of the Gravitational Map (Aba 1)
                Text(
                    text = "1. Fotografia do Campo de Emoções e Ideias",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                val canvasBg = MaterialTheme.colorScheme.surface
                val labelColor = MaterialTheme.colorScheme.onSurface

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = canvasBg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Gradient bridges
                        for (i in parsedParticles.indices) {
                            for (j in i + 1 until parsedParticles.size) {
                                val p1 = parsedParticles[i]
                                val p2 = parsedParticles[j]
                                val o1 = Offset(p1.posX * w, p1.posY * h)
                                val o2 = Offset(p2.posX * w, p2.posY * h)
                                val dist = hypot(o2.x - o1.x, o2.y - o1.y)
                                val maxDist = minOf(w, h) * 0.72f
                                if (dist < maxDist) {
                                    val c1 = computeSynestheticSphereColor(p1.mass, p1.charge, isDark)
                                    val c2 = computeSynestheticSphereColor(p2.mass, p2.charge, isDark)
                                    drawLine(
                                        brush = Brush.linearGradient(
                                            colors = listOf(c1.copy(alpha = 0.7f), c2.copy(alpha = 0.7f)),
                                            start = o1,
                                            end = o2
                                        ),
                                        start = o1,
                                        end = o2,
                                        strokeWidth = 2.5.dp.toPx()
                                    )
                                }
                            }
                        }

                        // Spheres
                        parsedParticles.forEach { p ->
                            val pos = Offset(p.posX * w, p.posY * h)
                            val c = computeSynestheticSphereColor(p.mass, p.charge, isDark)
                            val r = (14f + p.mass * 5f).dp.toPx()
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(c.copy(alpha = 0.25f), Color.Transparent),
                                    center = pos,
                                    radius = r * 2.2f
                                ),
                                radius = r * 2.2f,
                                center = pos
                            )
                            drawCircle(color = c, radius = r, center = pos)
                            val layout = textMeasurer.measure(
                                text = p.name,
                                style = TextStyle(color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            )
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    x = (pos.x - layout.size.width / 2f).coerceIn(4f, w - layout.size.width - 4f),
                                    y = (pos.y + r + 4.dp.toPx()).coerceIn(4f, h - layout.size.height - 4f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Snapshot of Diagnosed Mental Rooms (Aba 2)
                if (parsedZones.isNotEmpty()) {
                    Text(
                        text = "2. Diagnóstico dos Espaços da Mente na Data",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    parsedZones.take(4).forEach { z ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = z.customTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Clareza ${(z.clarity * 100).toInt()}% · Pressão ${(z.pressure * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { z.clarity },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. User Notes & Generated Reflection (Aba 3)
                if (entry.userNotesPrompt.isNotBlank()) {
                    Text(
                        text = "3. Suas Anotações e Contexto",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "“${entry.userNotesPrompt}”",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = "4. Leitura e Reflexão Sintetizada",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FormattedMarkdownText(
                            markdown = entry.narrativeText,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (entry.somaticExerciseText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Spacer(modifier = Modifier.height(12.dp))
                            FormattedMarkdownText(
                                markdown = entry.somaticExerciseText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Actions: Delete Entry or Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delete_journal_entry_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = strings.journalArticleDeleteButton,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.journalArticleDeleteButton,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(strings.journalArticleCloseButton)
                    }
                }
            }
        }
    }
}

private data class SnapshotParticle(
    val name: String,
    val mass: Float,
    val charge: Float,
    val posX: Float,
    val posY: Float
)

/**
 * On-device model manager: Qwen .task slots (full ~1.6 GB / lite ~550 MB),
 * internal/SD storage choice (SD only when detected), download with progress,
 * .task import from file picker, delete.
 */
@Composable
private fun LocalLlmManagerCard(
    strings: com.example.i18n.StringsCatalog,
) {
    val context = LocalContext.current
    val localLlm = remember { LocalLlmEngine(context) }
    var state by remember { mutableStateOf<LocalLlmEngine.State>(LocalLlmEngine.State.NotStarted) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var storage by remember { mutableStateOf(LlmPrefs.getStorage(context)) }
    var slot by remember { mutableStateOf(LlmPrefs.getSlot(context)) }
    val hasSd = remember { localLlm.hasSdCard() }

    LaunchedEffect(localLlm) {
        localLlm.onStateChange = { newState -> state = newState }
        if (localLlm.isModelDownloaded()) state = LocalLlmEngine.State.Ready
    }

    LaunchedEffect(slot, storage) {
        LlmPrefs.setSlot(context, slot)
        LlmPrefs.setStorage(context, storage)
        state = if (localLlm.isModelDownloaded()) {
            LocalLlmEngine.State.Ready
        } else {
            LocalLlmEngine.State.NotStarted
        }
    }

    val currentState = state
    val isBusy = currentState is LocalLlmEngine.State.Downloading ||
        currentState is LocalLlmEngine.State.Initializing
    val isReady = currentState is LocalLlmEngine.State.Ready
    val errorMessage = (currentState as? LocalLlmEngine.State.Error)?.message
    val downloadProgress = when (currentState) {
        is LocalLlmEngine.State.Downloading -> currentState.progress
        is LocalLlmEngine.State.Initializing -> currentState.progress
        else -> 0f
    }
    val downloadedMb = (currentState as? LocalLlmEngine.State.Downloading)?.downloadedMb ?: 0L
    val totalMb = (currentState as? LocalLlmEngine.State.Downloading)?.totalMb ?: 0L

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                var size = -1L
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && c.moveToFirst()) size = c.getLong(idx)
                }
                val stream = context.contentResolver.openInputStream(uri)
                if (stream == null) {
                    state = LocalLlmEngine.State.Error("Não foi possível abrir o arquivo.")
                } else {
                    localLlm.importFromStream(stream, size)
                }
            } catch (e: Exception) {
                state = LocalLlmEngine.State.Error(e.message ?: "falha na importação")
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = "Modelo no aparelho",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Modelo Neural no Aparelho (Qwen .task)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Completo Qwen 2.5 1.5B (~1,6 GB), leve Qwen 2.5 0.5B (~550 MB) ou emergência SmolLM 135M (~150 MB). Roda 100% offline. Use Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = slot == LlmPrefs.SLOT_FULL,
                onClick = { slot = LlmPrefs.SLOT_FULL },
                label = { Text("1.5B completo") }
            )
            FilterChip(
                selected = slot == LlmPrefs.SLOT_LITE,
                onClick = { slot = LlmPrefs.SLOT_LITE },
                label = { Text("0.5B leve") }
            )
            FilterChip(
                selected = slot == LlmPrefs.SLOT_ULTRA,
                onClick = { slot = LlmPrefs.SLOT_ULTRA },
                label = { Text("135M emergência") }
            )
            FilterChip(
                selected = slot == LlmPrefs.SLOT_GEMMA,
                onClick = { slot = LlmPrefs.SLOT_GEMMA },
                label = { Text("Gemma 3 1B") }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = storage == LlmPrefs.STORAGE_INTERNAL,
                onClick = { storage = LlmPrefs.STORAGE_INTERNAL },
                label = { Text("Interno") }
            )
            if (hasSd) {
                FilterChip(
                    selected = storage == LlmPrefs.STORAGE_SD,
                    onClick = { storage = LlmPrefs.STORAGE_SD },
                    label = { Text("Cartão SD") }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedVisibility(visible = errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Text(
                    text = "⚠ $errorMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        AnimatedVisibility(visible = isReady) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "✅ ${localLlm.slotLabel()} pronto (${localLlm.getModelSizeMb()} MB, ${localLlm.getStorageLabel()})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Usado automaticamente nas leituras (sem BYOK).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("delete_local_model_button"),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(text = "Remover", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        AnimatedVisibility(visible = isBusy) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (downloadProgress >= 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (totalMb > 0 && downloadProgress >= 0f) {
                        "Baixando... ${(downloadProgress * 100).toInt()}% ($downloadedMb / $totalMb MB)"
                    } else if (downloadProgress >= 0f) {
                        "Baixando... ${(downloadProgress * 100).toInt()}%"
                    } else {
                        "Trabalhando no modelo..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isReady && !isBusy) {
                Button(
                    onClick = {
                        coroutineScope.launch { localLlm.initialize() }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_local_model_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Baixar modelo",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Baixar", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (!isBusy) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Importar .task")
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Remover modelo") },
                text = {
                    Text("O modelo (${localLlm.getModelSizeMb()} MB) será removido. Você poderá baixá-lo de novo quando quiser.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            localLlm.deleteModel()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text("Remover") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
                }
            )
        }
    }
}

/**
 * DEV-only diagnostics (BuildConfig.ENABLE_FILE_LOGGING): constant-watch status
 * plus one-tap log export. After a crash, reopen the app, export here and save
 * the document anywhere.
 */
@Composable
private fun DevDiagnosticsCard(providerSummaries: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }

    val sessionFile = remember(tick) { LogCollector.sessionLogFile(context) }
    val sessionKb = remember(tick) { if (sessionFile.exists()) sessionFile.length() / 1024 else 0L }
    val ringLines = remember(tick) { AppLog.snapshot().size }
    val crashes = remember(tick) {
        LogCollector.logsDir(context)
            .listFiles { f -> f.name.startsWith("noesis-crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "exportando..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    writeDiagnosticsReport(context, uri, providerSummaries)
                    "exportado ✅"
                }.getOrElse { e -> "falha: ${e.message}" }
            }
            status = result
            tick++
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Diagnóstico DEV",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Diagnóstico DEV",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Watch constante: anel ${ringLines} linhas · sessão ${sessionKb} KB · ${crashes.size} crash(es). Após um crash, reabra e exporte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (crashes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            crashes.take(3).forEach { c ->
                Text(
                    text = "💥 ${c.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { exportLauncher.launch("noesis-log.txt") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("export_log_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Exportar log")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                LogCollector.sessionLogFile(context).delete()
                                LogCollector.logsDir(context)
                                    .listFiles { f -> f.name.startsWith("noesis-crash-") }
                                    ?.forEach { it.delete() }
                            }
                        }
                        status = "logs limpos"
                        tick++
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Limpar")
            }
        }
    }
}

private fun devAppVersion(context: android.content.Context): String {
    return try {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (_: Exception) {
        "unknown"
    }
}

private fun devSafeRead(f: java.io.File): String {
    return try {
        f.readText()
    } catch (_: Exception) {
        "(ilegível)"
    }
}

private fun devTailOf(f: java.io.File, maxLines: Int): String {
    return try {
        if (!f.exists()) return "(sem sessão)"
        f.readLines().takeLast(maxLines).joinToString("\n")
    } catch (_: Exception) {
        "(ilegível)"
    }
}

private fun writeDiagnosticsReport(
    context: android.content.Context,
    uri: Uri,
    providerSummaries: List<String>
) {
    val sb = StringBuilder()
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    sb.appendLine("=== Noesis Lab — relatório de diagnóstico ===")
    sb.appendLine("gerado em: $ts")
    sb.appendLine("app: ${devAppVersion(context)}")
    sb.appendLine("device: ${Build.BRAND} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
    sb.appendLine("slot=${LlmPrefs.getSlot(context)} storage=${LlmPrefs.getStorage(context)}")
    sb.appendLine("provedores: " + if (providerSummaries.isEmpty()) "(nenhum)" else providerSummaries.joinToString(" | "))
    sb.appendLine()
    sb.appendLine("=== anel AppLog (${AppLog.snapshot().size} linhas) ===")
    AppLog.snapshot().forEach { sb.appendLine(it) }
    sb.appendLine()
    val dir = LogCollector.logsDir(context)
    val crashFiles = dir.listFiles { f -> f.name.startsWith("noesis-crash-") && f.name.endsWith(".txt") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
    sb.appendLine("=== crash reports (${crashFiles.size}) ===")
    crashFiles.forEach { f ->
        sb.appendLine("--- ${f.name} ---")
        sb.appendLine(devSafeRead(f))
    }
    sb.appendLine("=== sessão (últimas 600 linhas) ===")
    sb.appendLine(devTailOf(LogCollector.sessionLogFile(context), 600))
    sb.appendLine("=== logcat fresco (processo + tags, até 2500) ===")
    AppLog.dumpFilteredLogcat(2500).forEach { sb.appendLine(it) }
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(sb.toString().toByteArray())
    } ?: throw IllegalStateException("não foi possível abrir o destino")
}

private data class SnapshotZone(
    val customTitle: String,
    val conceptDomain: String,
    val clarity: Float,
    val pressure: Float,
    val empiricalInterpretation: String
)
