package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
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
                    onDragParticle = viewModel::updateParticlePosition,
                    onUpdateParticleParams = viewModel::updateParticleParameters,
                    onInjectConcept = viewModel::injectConceptParticle,
                    onRemoveParticle = viewModel::removeParticle,
                    onResetParticles = viewModel::resetFieldParticles,
                    onCollideAndSynthesize = { viewModel.collideAndRunSimulation(null) },
                    onToggleSound = viewModel::setSoundEnabled
                )

                NoesisTab.ARCHITECTURE -> ArchitecturalMapScreen(
                    uiState = uiState,
                    onSelectZone = viewModel::selectArchitecturalZone,
                    onTransmuteZone = viewModel::transmuteArchitecturalZone,
                    onMeditateInZone = { zone ->
                        viewModel.collideAndRunSimulation(
                            "Explorar a câmara arquitetônica '${zone.customTitle}' (${zone.conceptDomain}) com luminosidade ${(zone.luminosity * 100).toInt()}% e tensão ${(zone.tensionIndex * 100).toInt()}%."
                        )
                    }
                )

                NoesisTab.ORACLE -> NarrativeOracleScreen(
                    uiState = uiState,
                    onUpdateBiofeedback = viewModel::updateExplicitBiofeedback,
                    onRunSimulation = { prompt -> viewModel.collideAndRunSimulation(prompt) }
                )

                NoesisTab.SETTINGS -> SystemsAndByokScreen(
                    uiState = uiState,
                    onSelectTheme = viewModel::setThemeMode,
                    onSelectLanguage = viewModel::setLanguage,
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
