package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.MysticSoundEngine
import com.example.data.AiProviderEntity
import com.example.data.ArchitecturalZoneEntity
import com.example.data.ConceptParticleEntity
import com.example.data.NoesisDatabase
import com.example.data.NoesisRepository
import com.example.data.SimulationLogEntity
import com.example.data.UserPreferencesState
import com.example.i18n.AppLanguage
import com.example.i18n.AppLocalizer
import com.example.i18n.StringsCatalog
import com.example.network.GroundingAndAiService
import com.example.network.GroundingBundle
import com.example.network.ProviderVerificationReport
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

enum class NoesisTab {
    FIELD,
    ARCHITECTURE,
    ORACLE,
    SETTINGS
}

data class NoesisUiState(
    val currentTab: NoesisTab = NoesisTab.FIELD,
    val showHelpModal: Boolean = false,
    val preferences: UserPreferencesState = UserPreferencesState(),
    val strings: StringsCatalog = AppLocalizer.getCatalog(AppLanguage.SYSTEM),
    val particles: List<ConceptParticleEntity> = emptyList(),
    val selectedParticleId: Long? = null,
    val zones: List<ArchitecturalZoneEntity> = emptyList(),
    val selectedZoneId: String? = "zone_time",
    val logs: List<SimulationLogEntity> = emptyList(),
    val providers: List<AiProviderEntity> = emptyList(),
    val isSimulating: Boolean = false,
    val latestGrounding: GroundingBundle? = null,
    val isVerifyingProvider: Boolean = false,
    val lastVerificationReport: ProviderVerificationReport? = null,
    val statusBannerMessage: String? = null,
    val phaseCoherence: Float = 0.78f,
    val entropyIndex: Float = 0.34f,
    val meanFrequencyHz: Float = 432f,
    val meanValence: Float = 0.50f
)

class NoesisViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NoesisDatabase.getInstance(application)
    private val repository = NoesisRepository(application, database.noesisDao())
    private val networkService = GroundingAndAiService()
    val soundEngine = MysticSoundEngine()

    private val _currentTab = MutableStateFlow(NoesisTab.FIELD)
    private val _showHelpModal = MutableStateFlow(false)
    private val _selectedParticleId = MutableStateFlow<Long?>(null)
    private val _selectedZoneId = MutableStateFlow<String?>("zone_time")
    private val _isSimulating = MutableStateFlow(false)
    private val _latestGrounding = MutableStateFlow<GroundingBundle?>(null)
    private val _isVerifyingProvider = MutableStateFlow(false)
    private val _lastVerificationReport = MutableStateFlow<ProviderVerificationReport?>(null)
    private val _statusBannerMessage = MutableStateFlow<String?>(null)

    private var lastTouchTimestamp = 0L
    private val recentTouchIntervalsMs = ArrayDeque<Long>()

    private val coreDataFlow = combine(
        repository.preferencesFlow,
        repository.particlesFlow,
        repository.zonesFlow,
        repository.logsFlow,
        repository.providersFlow
    ) { prefs, particles, zones, logs, providers ->
        Quint(prefs, particles, zones, logs, providers)
    }

    private val transientFlow = combine(
        _currentTab,
        _selectedParticleId,
        _selectedZoneId,
        _isSimulating,
        combine(_showHelpModal, _isVerifyingProvider, _lastVerificationReport, _statusBannerMessage) { h, v, r, m ->
            Quad(h, v, r, m)
        }
    ) { tab, selPart, selZone, sim, extra ->
        Quint(tab, selPart, selZone, sim, extra)
    }

    val uiState: StateFlow<NoesisUiState> = combine(coreDataFlow, transientFlow) { core, trans ->
        val prefs = core.first
        val particles = core.second
        val zones = core.third
        val logs = core.fourth
        val providers = core.fifth

        val strings = AppLocalizer.getCatalog(prefs.language)
        val meanFreq = if (particles.isNotEmpty()) {
            particles.map { it.frequencyHz }.average().toFloat()
        } else 432f

        val avgCharge = if (particles.isNotEmpty()) {
            particles.map { it.charge }.average().toFloat()
        } else 0.3f

        val normalizedValence = ((avgCharge + 1f) / 2f).coerceIn(0f, 1f)

        val chargeVariance = if (particles.size > 1) {
            particles.map { abs(it.charge - avgCharge) }.average().toFloat()
        } else 0.25f

        val entropy = (chargeVariance * 0.6f + prefs.bioArousal * 0.4f).coerceIn(0.08f, 0.96f)
        val coherence = (1.0f - entropy * 0.65f + prefs.bioFocus * 0.25f).coerceIn(0.12f, 0.99f)

        soundEngine.setSoundEnabled(prefs.soundEnabled)
        soundEngine.setBinauralEnabled(prefs.binauralEnabled)
        soundEngine.updateEmotionalFieldAcoustics(
            meanFrequencyHz = meanFreq,
            arousal = prefs.bioArousal,
            valence = (normalizedValence * 0.6f + prefs.bioValence * 0.4f).coerceIn(0f, 1f),
            coherence = coherence
        )

        NoesisUiState(
            currentTab = trans.first,
            showHelpModal = trans.fifth.first,
            preferences = prefs,
            strings = strings,
            particles = particles,
            selectedParticleId = trans.second ?: particles.firstOrNull()?.id,
            zones = zones,
            selectedZoneId = trans.third ?: zones.firstOrNull()?.zoneId,
            logs = logs,
            providers = providers,
            isSimulating = trans.fourth,
            latestGrounding = _latestGrounding.value,
            isVerifyingProvider = trans.fifth.second,
            lastVerificationReport = trans.fifth.third,
            statusBannerMessage = trans.fifth.fourth,
            phaseCoherence = coherence,
            entropyIndex = entropy,
            meanFrequencyHz = meanFreq,
            meanValence = normalizedValence
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NoesisUiState()
    )

    init {
        viewModelScope.launch {
            repository.seedInitialDataIfEmpty()
            soundEngine.startAmbientDroneIfNeeded()
        }
    }

    fun toggleHelpModal(show: Boolean) {
        _showHelpModal.value = show
    }

    fun selectTab(tab: NoesisTab) {
        _currentTab.value = tab
        soundEngine.triggerTouchHarmonic(frequencyHz = 528f, valence = 0.6f, durationMs = 240)
    }

    fun selectParticle(particle: ConceptParticleEntity) {
        _selectedParticleId.value = particle.id
        recordImplicitKineticTouch(dragSpeedPx = 10f)
        soundEngine.triggerTouchHarmonic(
            frequencyHz = particle.frequencyHz,
            valence = particle.charge,
            durationMs = 380
        )
    }

    fun commitParticlePosition(particle: ConceptParticleEntity, newX: Float, newY: Float, dragSpeed: Float) {
        recordImplicitKineticTouch(dragSpeedPx = dragSpeed)
        viewModelScope.launch {
            repository.updateParticle(
                particle.copy(
                    posX = newX.coerceIn(0.08f, 0.92f),
                    posY = newY.coerceIn(0.10f, 0.90f)
                )
            )
        }
    }

    fun updateParticleParameters(particle: ConceptParticleEntity, mass: Float, charge: Float, freqHz: Float) {
        soundEngine.triggerTouchHarmonic(frequencyHz = freqHz, valence = charge, durationMs = 240)
        viewModelScope.launch {
            repository.updateParticle(
                particle.copy(
                    mass = mass.coerceIn(0.3f, 2.5f),
                    charge = charge.coerceIn(-1f, 1f),
                    frequencyHz = freqHz.coerceIn(136f, 852f)
                )
            )
        }
    }

    fun injectConceptParticle(name: String, archetypeKey: String = "CUSTOM") {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return

        val preset = resolveArchetypeSpec(cleanName, archetypeKey)
        soundEngine.triggerTouchHarmonic(
            frequencyHz = preset.frequencyHz,
            valence = preset.charge,
            durationMs = 450
        )

        viewModelScope.launch {
            val id = repository.addParticle(
                ConceptParticleEntity(
                    name = cleanName,
                    archetypeKey = preset.archetypeKey,
                    mass = preset.mass,
                    charge = preset.charge,
                    frequencyHz = preset.frequencyHz,
                    spin = preset.spin,
                    posX = Random.nextFloat() * 0.56f + 0.22f,
                    posY = Random.nextFloat() * 0.52f + 0.24f,
                    colorHex = preset.colorHex,
                    philosophicalNote = preset.note
                )
            )
            _selectedParticleId.value = id
        }
    }

    fun removeParticle(id: Long) {
        soundEngine.triggerTouchHarmonic(frequencyHz = 220f, valence = -0.2f, durationMs = 260)
        viewModelScope.launch {
            repository.deleteParticle(id)
        }
    }

    fun resetFieldParticles() {
        soundEngine.triggerTouchHarmonic(frequencyHz = 528f, valence = 0.8f, durationMs = 500)
        viewModelScope.launch {
            repository.resetParticlesToDefault()
        }
    }

    fun selectArchitecturalZone(zone: ArchitecturalZoneEntity) {
        _selectedZoneId.value = zone.zoneId
        soundEngine.triggerTouchHarmonic(
            frequencyHz = zone.resonantHz,
            valence = zone.luminosity - zone.tensionIndex,
            durationMs = 420
        )
    }

    fun transmuteArchitecturalZone(zone: ArchitecturalZoneEntity, newLuminosity: Float, newTension: Float) {
        soundEngine.triggerTouchHarmonic(
            frequencyHz = zone.resonantHz * (0.9f + newLuminosity * 0.25f),
            valence = newLuminosity - newTension,
            durationMs = 350
        )
        viewModelScope.launch {
            repository.updateZone(
                zone.copy(
                    luminosity = newLuminosity.coerceIn(0.1f, 1.0f),
                    tensionIndex = newTension.coerceIn(0.05f, 0.95f),
                    lastVisitedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateExplicitBiofeedback(arousal: Float, valence: Float, focus: Float) {
        viewModelScope.launch {
            repository.updateBiofeedback(arousal, valence, focus)
        }
    }

    private fun recordImplicitKineticTouch(dragSpeedPx: Float) {
        val now = System.currentTimeMillis()
        if (lastTouchTimestamp > 0L) {
            val delta = (now - lastTouchTimestamp).coerceIn(40L, 5000L)
            recentTouchIntervalsMs.addLast(delta)
            if (recentTouchIntervalsMs.size > 8) recentTouchIntervalsMs.removeFirst()

            val avgInterval = recentTouchIntervalsMs.average().toFloat()
            val cadenceFactor = ((600f - avgInterval.coerceIn(100f, 600f)) / 500f).coerceIn(0f, 1f)
            val speedFactor = (dragSpeedPx / 60f).coerceIn(0f, 1f)
            val currentPrefs = uiState.value.preferences
            val blendedArousal = (currentPrefs.bioArousal * 0.85f + (cadenceFactor * 0.5f + speedFactor * 0.5f) * 0.15f)
                .coerceIn(0.1f, 0.95f)

            viewModelScope.launch {
                repository.updateBiofeedback(
                    arousal = blendedArousal,
                    valence = currentPrefs.bioValence,
                    focus = currentPrefs.bioFocus
                )
            }
        }
        lastTouchTimestamp = now
    }

    fun collideAndRunSimulation(customPrompt: String? = null) {
        val state = uiState.value
        if (state.isSimulating) return

        val conceptNames = state.particles.map { it.name }
        val promptToUse = customPrompt?.takeIf { it.isNotBlank() } ?: when (state.strings.languageCode) {
            "en" -> "What does the combination of ${conceptNames.joinToString(", ")} reveal about my current state?"
            "es" -> "¿Qué revela la combinación de ${conceptNames.joinToString(", ")} sobre mi momento actual?"
            "fr" -> "Que révèle la combinaison de ${conceptNames.joinToString(", ")} sur mon état actuel ?"
            else -> "O que a combinação entre ${conceptNames.joinToString(", ")} revela sobre o meu momento atual?"
        }

        _currentTab.value = NoesisTab.ORACLE
        _isSimulating.value = true
        soundEngine.triggerTouchHarmonic(frequencyHz = 639f, valence = 0.75f, durationMs = 600)

        viewModelScope.launch {
            try {
                val particlesSummary = state.particles.joinToString("; ") {
                    "${it.name} (Tamanho=${"%.1f".format(it.mass)}, Sensação=${if (it.charge >= 0) "Leve/Aberta" else "Tensa/Pesada"}, Tom=${it.frequencyHz.toInt()}Hz)"
                }

                val activeProvider = state.providers.firstOrNull { it.isActive && it.selectedModel.isNotBlank() }
                val effectiveLang = AppLanguage.resolveEffectiveLanguage(state.preferences.language)

                val result = networkService.orchestrateConsciousSimulation(
                    userPrompt = promptToUse,
                    particlesSummary = particlesSummary,
                    conceptNames = conceptNames,
                    arousal = state.preferences.bioArousal,
                    valence = state.preferences.bioValence,
                    focus = state.preferences.bioFocus,
                    languageInstruction = state.strings.languageInstructionForAI,
                    languageCode = effectiveLang.code,
                    activeProviderName = activeProvider?.name,
                    activeBaseUrl = activeProvider?.baseUrl,
                    activeApiKey = activeProvider?.apiKey,
                    activeModelId = activeProvider?.selectedModel
                )

                _latestGrounding.value = result.grounding

                repository.addSimulationLog(
                    SimulationLogEntity(
                        userPrompt = promptToUse,
                        activeConceptsCsv = conceptNames.joinToString(", "),
                        biofeedbackArousal = state.preferences.bioArousal,
                        biofeedbackValence = state.preferences.bioValence,
                        biofeedbackFocus = state.preferences.bioFocus,
                        wikiSourcesJson = "[]",
                        academicSourcesJson = "[]",
                        narrativeResponse = result.narrativeMarkdown,
                        somaticSuggestion = result.somaticShiftSuggestion,
                        providerUsed = result.providerLabel,
                        languageCode = effectiveLang.code
                    )
                )
            } finally {
                _isSimulating.value = false
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { repository.setLanguage(language) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSoundEnabled(enabled) }
    }

    fun setBinauralEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBinauralEnabled(enabled) }
    }

    fun verifyAndSaveByokProvider(
        name: String,
        baseUrl: String,
        apiKey: String
    ) {
        if (baseUrl.isBlank()) return
        _isVerifyingProvider.value = true
        _lastVerificationReport.value = null

        val pingWord = when (uiState.value.strings.languageCode) {
            "en" -> "hi"
            "es" -> "hola"
            "fr" -> "salut"
            else -> "oi"
        }

        viewModelScope.launch {
            try {
                val report = networkService.verifyProviderAndPingModels(
                    rawBaseUrl = baseUrl,
                    apiKey = apiKey,
                    pingWord = pingWord
                )
                _lastVerificationReport.value = report

                val verifiedModels = report.verifiedResponsiveModels
                val firstModel = verifiedModels.firstOrNull() ?: ""
                repository.saveVerifiedProvider(
                    AiProviderEntity(
                        name = name.ifBlank { "Custom OpenAI Provider" },
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        verifiedModelsCsv = verifiedModels.joinToString(","),
                        selectedModel = firstModel,
                        isActive = verifiedModels.isNotEmpty(),
                        lastVerifiedAt = System.currentTimeMillis()
                    )
                )
                if (verifiedModels.isNotEmpty()) {
                    soundEngine.triggerTouchHarmonic(frequencyHz = 639f, valence = 0.9f, durationMs = 450)
                }
            } finally {
                _isVerifyingProvider.value = false
            }
        }
    }

    fun activateProviderModel(providerId: Long, modelId: String) {
        soundEngine.triggerTouchHarmonic(frequencyHz = 528f, valence = 0.8f, durationMs = 300)
        viewModelScope.launch {
            repository.activateProviderModel(providerId, modelId)
        }
    }

    fun useBuiltInEngine() {
        viewModelScope.launch {
            repository.deactivateAllProviders()
        }
    }

    fun deleteProvider(id: Long) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = repository.exportBackupToUri(uri)
            val strings = uiState.value.strings
            _statusBannerMessage.value = if (ok) strings.backupSuccessToast else strings.backupErrorToast
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = repository.importBackupFromUri(uri)
            val strings = uiState.value.strings
            _statusBannerMessage.value = if (ok) strings.restoreSuccessToast else strings.backupErrorToast
        }
    }

    fun dismissStatusBanner() {
        _statusBannerMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        soundEngine.stopAmbientDrone()
    }

    private data class ArchetypeSpec(
        val archetypeKey: String,
        val mass: Float,
        val charge: Float,
        val frequencyHz: Float,
        val spin: Float,
        val colorHex: Long,
        val note: String
    )

    private fun resolveArchetypeSpec(name: String, keyHint: String): ArchetypeSpec {
        val lower = name.lowercase()
        return when {
            keyHint == "FEAR" || lower.contains("medo") || lower.contains("fear") || lower.contains("dúvida") || lower.contains("ansiedade") ->
                ArchetypeSpec("FEAR", 1.40f, -0.70f, 310f, -1.2f, 0xFF7A6F9B, "Sinal de cautela e proteção que pede segurança antes do próximo passo.")
            keyHint == "DESIRE" || lower.contains("desejo") || lower.contains("desire") || lower.contains("paixão") || lower.contains("vontade") ->
                ArchetypeSpec("DESIRE", 1.65f, 0.85f, 432f, 1.2f, 0xFFD97757, "Energia de atração, entusiasmo e vontade de criar ou viver algo novo.")
            keyHint == "TIME" || lower.contains("tempo") || lower.contains("time") || lower.contains("futuro") || lower.contains("passado") ->
                ArchetypeSpec("TIME", 1.85f, 0.20f, 528f, 0.6f, 0xFFD4AF59, "A relação com o ritmo das coisas, a memória e a paciência com os processos.")
            keyHint == "IDENTITY" || lower.contains("identidade") || lower.contains("identity") || lower.contains("eu") ->
                ArchetypeSpec("IDENTITY", 1.50f, 0.40f, 396f, 0.9f, 0xFF5A8F99, "Aquilo que define seus valores, seus limites e sua história atual.")
            keyHint == "JUSTICE" || lower.contains("justiça") || lower.contains("justice") || lower.contains("equilíbrio") ->
                ArchetypeSpec("JUSTICE", 1.65f, 0.70f, 639f, 0.3f, 0xFF7D9471, "Busca por harmonia, reciprocidade e escolhas justas no seu caminho.")
            keyHint == "DOMINATION" || lower.contains("dominação") || lower.contains("controle") || lower.contains("cobrança") ->
                ArchetypeSpec("DOMINATION", 1.80f, -0.60f, 220f, -1.1f, 0xFFB84A39, "Tentativa de controlar tudo pela força ou excesso de autocobrança.")
            keyHint == "POWER" || lower.contains("poder") || lower.contains("força") || lower.contains("coragem") ->
                ArchetypeSpec("POWER", 1.75f, 0.65f, 480f, 1.3f, 0xFFC98A4B, "Capacidade real de agir, realizar e transformar ideias em realidade.")
            keyHint == "ANGER" || lower.contains("raiva") || lower.contains("irritação") || lower.contains("fúria") ->
                ArchetypeSpec("ANGER", 1.55f, -0.50f, 580f, 1.6f, 0xFFD14949, "Energia intensa que surge quando um limite importante foi ultrapassado.")
            keyHint == "EMPTINESS" || lower.contains("silêncio") || lower.contains("calma") || lower.contains("paz") || lower.contains("vazio") ->
                ArchetypeSpec("EMPTINESS", 0.70f, 0.95f, 741f, 0.1f, 0xFF8AA37B, "Espaço aberto de calma e alívio que permite enxergar tudo com clareza.")
            else -> {
                val hash = abs(name.hashCode())
                val palette = listOf(0xFFD97757, 0xFFD4AF59, 0xFF5A8F99, 0xFF7D9471, 0xFF7A6F9B, 0xFFC98A4B)
                ArchetypeSpec(
                    archetypeKey = "CUSTOM",
                    mass = 1.1f + (hash % 100) / 100f,
                    charge = ((hash % 160) - 60) / 100f,
                    frequencyHz = 280f + (hash % 420).toFloat(),
                    spin = ((hash % 200) - 100) / 100f,
                    colorHex = palette[hash % palette.size],
                    note = "Conceito ou sensação livre que faz parte do seu momento atual."
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
