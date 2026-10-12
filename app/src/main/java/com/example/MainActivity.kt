package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.ArchitecturalMapScreen
import com.example.ui.screens.FieldSimulationScreen
import com.example.ui.screens.NarrativeOracleScreen
import com.example.ui.screens.SystemsAndByokScreen
import com.example.ui.theme.NoesisTheme
import com.example.viewmodel.NoesisTab
import com.example.viewmodel.NoesisViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: NoesisViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ ->
                viewModel.soundEngine.updateMediaNotification()
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.soundEngine.updateMediaNotification()
                    }
                } else {
                    viewModel.soundEngine.updateMediaNotification()
                }
            }

            NoesisTheme(themeMode = uiState.preferences.themeMode) {
                NoesisAppShell(
                    viewModel = viewModel,
                    uiState = uiState
                )
            }
        }
    }
}

@Composable
fun NoesisAppShell(
    viewModel: NoesisViewModel,
    uiState: com.example.viewmodel.NoesisUiState
) {
    val strings = uiState.strings

    if (uiState.currentTab != NoesisTab.FIELD) {
        BackHandler {
            viewModel.selectTab(NoesisTab.FIELD)
        }
    }

    // Interactive "Como Funciona?" Guide Overlay Modal
    if (uiState.showHelpModal) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleHelpModal(false) },
            title = {
                Column {
                    Text(
                        text = strings.guideModalTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.guideModalSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GuideSectionItem(title = strings.guideStep1Title, body = strings.guideStep1Body)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    GuideSectionItem(title = strings.guideStep2Title, body = strings.guideStep2Body)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    GuideSectionItem(title = strings.guideStep3Title, body = strings.guideStep3Body)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    GuideSectionItem(title = strings.guideAudioNoteTitle, body = strings.guideAudioNoteBody)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.toggleHelpModal(false) }) {
                    Text(strings.guideCloseButton)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = strings.appSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.toggleHelpModal(true) },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("help_guide_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = strings.helpButtonLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.helpButtonLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                val items = listOf(
                    Triple(NoesisTab.FIELD, strings.tabField, Pair(Icons.Filled.BlurCircular, Icons.Outlined.BlurCircular)),
                    Triple(NoesisTab.ARCHITECTURE, strings.tabArchitecture, Pair(Icons.Filled.AccountBalance, Icons.Outlined.AccountBalance)),
                    Triple(NoesisTab.ORACLE, strings.tabOracle, Pair(Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome)),
                    Triple(NoesisTab.SETTINGS, strings.tabSettings, Pair(Icons.Filled.Tune, Icons.Outlined.Tune))
                )
                items.forEach { (tab, label, icons) ->
                    val selected = uiState.currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = if (selected) icons.first else icons.second,
                                contentDescription = label
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.currentTab) {
                NoesisTab.FIELD -> FieldSimulationScreen(
                    uiState = uiState,
                    onSelectParticle = viewModel::selectParticle,
                    onCommitParticlePosition = viewModel::commitParticlePosition,
                    onUpdateParticleImportanceAndHeaviness = viewModel::updateParticleImportanceAndHeaviness,
                    onInjectConcept = viewModel::injectConceptParticle,
                    onRemoveParticle = viewModel::removeParticle,
                    onResetParticles = viewModel::resetFieldParticles,
                    onCollideAndSynthesize = { viewModel.collideAndRunSimulation(null) }
                )

                NoesisTab.ARCHITECTURE -> ArchitecturalMapScreen(
                    uiState = uiState,
                    onSelectZone = viewModel::selectArchitecturalZone
                )

                NoesisTab.ORACLE -> NarrativeOracleScreen(
                    uiState = uiState,
                    onUpdateBiofeedback = viewModel::updateExplicitBiofeedback,
                    onUpdateDraftPrompt = viewModel::updateDraftPrompt,
                    onRunSimulation = { prompt -> viewModel.collideAndRunSimulation(prompt) },
                    onSaveStateToJournal = viewModel::saveCurrentStateToJournal
                )

                NoesisTab.SETTINGS -> SystemsAndByokScreen(
                    uiState = uiState,
                    onSelectTheme = viewModel::setThemeMode,
                    onSelectLanguage = viewModel::setLanguage,
                    onSaveCurrentStateToJournal = viewModel::saveCurrentStateToJournal,
                    onOpenJournalEntry = viewModel::openJournalEntry,
                    onDeleteJournalEntry = viewModel::deleteJournalEntry,
                    onVerifyAndSaveProvider = viewModel::verifyAndSaveByokProvider,
                    onActivateProviderModel = viewModel::activateProviderModel,
                    onUseBuiltInEngine = viewModel::useBuiltInEngine,
                    onDeleteProvider = viewModel::deleteProvider,
                    onExportBackup = viewModel::exportBackup,
                    onImportBackup = viewModel::importBackup
                )
            }

            uiState.statusBannerMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissStatusBanner) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    }
}

@Composable
private fun GuideSectionItem(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
