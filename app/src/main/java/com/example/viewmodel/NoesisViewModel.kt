package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.MysticSoundEngine
import com.example.audio.SphereAcousticNode
import com.example.data.AiProviderEntity
import com.example.data.AiProviderKeyEntity
import com.example.data.ChatMessageEntity
import com.example.data.ArchitecturalZoneEntity
import com.example.data.ConceptParticleEntity
import com.example.data.JournalEntryEntity
import com.example.data.NoesisDatabase
import com.example.data.NoesisRepository
import com.example.data.SimulationLogEntity
import com.example.data.UserPreferencesState
import com.example.i18n.AppLanguage
import com.example.i18n.AppLocalizer
import com.example.i18n.StringsCatalog
import com.example.i18n.zoneDescription
import com.example.i18n.zoneDomain
import com.example.i18n.zoneTitle
import com.example.network.GroundingAndAiService
import com.example.network.GroundingBundle
import com.example.network.ProviderVerificationReport
import com.example.network.SphereSpatialDescriptor
import com.example.network.ZoneSpatialDescriptor
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.formatConceptTitleCase
import com.example.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

enum class NoesisTab {
    FIELD,
    ARCHITECTURE,
    ORACLE,
    SETTINGS
}

data class DiagnosedZoneState(
    val zone: ArchitecturalZoneEntity,
    val diagnosedClarity: Float,   // 0.15f .. 0.98f (Computed from Tab 1 spheres)
    val diagnosedPressure: Float,  // 0.08f .. 0.95f (Computed from Tab 1 spheres)
    val influencingConcepts: List<String>,
    val empiricalInterpretation: String
)

data class NoesisUiState(
    val currentTab: NoesisTab = NoesisTab.FIELD,
    val showHelpModal: Boolean = false,
    val openedJournalEntry: JournalEntryEntity? = null,
    val preferences: UserPreferencesState = UserPreferencesState(),
    val strings: StringsCatalog = AppLocalizer.getCatalog(AppLanguage.SYSTEM),
    val particles: List<ConceptParticleEntity> = emptyList(),
    val selectedParticleId: Long? = null,
    val diagnosedZones: List<DiagnosedZoneState> = emptyList(),
    val selectedZoneId: String? = "zone_time",
    val logs: List<SimulationLogEntity> = emptyList(),
    val currentSessionReading: SimulationLogEntity? = null,
    val journalEntries: List<JournalEntryEntity> = emptyList(),
    val providers: List<AiProviderEntity> = emptyList(),
    val currentDraftPrompt: String = "",
    val currentThreadId: Long? = null,
    val isSimulating: Boolean = false,
    val simulationProgressText: String = "",
    val latestGrounding: GroundingBundle? = null,
    val isVerifyingProvider: Boolean = false,
    val lastVerificationReport: ProviderVerificationReport? = null,
    val statusBannerMessage: String? = null,
    val phaseCoherence: Float = 0.55f,
    val entropyIndex: Float = 0.34f,
    val meanFrequencyHz: Float = 320f,
    val meanHeaviness: Float = 0.40f
)

class NoesisViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NoesisDatabase.getInstance(application)
    private val repository = NoesisRepository(application, database.noesisDao())
    private val networkService = GroundingAndAiService(application.applicationContext)
    val soundEngine = MysticSoundEngine(application.applicationContext)

    private val _currentTab = MutableStateFlow(NoesisTab.FIELD)
    private val _showHelpModal = MutableStateFlow(false)
    private val _openedJournalEntry = MutableStateFlow<JournalEntryEntity?>(null)
    private val _selectedParticleId = MutableStateFlow<Long?>(null)
    private val _selectedZoneId = MutableStateFlow<String?>("zone_time")
    private val _currentDraftPrompt = MutableStateFlow("")
    private val _isSimulating = MutableStateFlow(false)
    private val _simulationProgressText = MutableStateFlow("")
    private val _latestGrounding = MutableStateFlow<GroundingBundle?>(null)
    private val _isVerifyingProvider = MutableStateFlow(false)
    private val _lastVerificationReport = MutableStateFlow<ProviderVerificationReport?>(null)
    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    private val _currentSessionReading = MutableStateFlow<SimulationLogEntity?>(null)
    private val _currentThreadId = MutableStateFlow<Long?>(null)

    private var lastTouchTimestamp = 0L
    private val recentTouchIntervalsMs = ArrayDeque<Long>()

    private val coreDataFlow = combine(
        repository.preferencesFlow,
        repository.particlesFlow,
        repository.zonesFlow,
        repository.logsFlow,
        combine(repository.providersFlow, repository.journalEntriesFlow) { p, j -> p to j }
    ) { prefs, particles, zones, logs, provAndJournal ->
        Quint(prefs, particles, zones, logs, provAndJournal)
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

    private val extraUiFlow = combine(
        _simulationProgressText,
        _openedJournalEntry,
        _currentDraftPrompt,
        _currentSessionReading,
        _currentThreadId
    ) { prog, journal, draft, sessionReading, threadId ->
        Quint(prog, journal, draft, sessionReading, threadId)
    }

    val uiState: StateFlow<NoesisUiState> = combine(coreDataFlow, transientFlow, extraUiFlow) { core, trans, extraUi ->
        val prefs = core.first
        val rawParticles = core.second
        // Enforce Title Case on all particles
        val particles = rawParticles.map { it.copy(name = formatConceptTitleCase(it.name)) }
        val baseZones = core.third
        val logs = core.fourth
        val providers = core.fifth.first
        val journalEntries = core.fifth.second

        val strings = AppLocalizer.getCatalog(prefs.language)
        // Keep non-Compose consumers (media notification, data layer) in sync.
        AppLocalizer.setActive(strings)

        // Convert particles to acoustic nodes and update modular synthesizer in real time
        val acousticNodes = particles.map { p ->
            SphereAcousticNode(
                id = p.id,
                name = p.name,
                importanceMass = p.mass,
                weightHeaviness = p.charge.coerceIn(0f, 1f),
                posX = p.posX,
                posY = p.posY
            )
        }
        // Audio engine sync runs off the main thread (was janking typing/scroll).
        withContext(Dispatchers.Default) {
            soundEngine.updateModularFieldState(
                nodes = acousticNodes,
                arousal = prefs.bioArousal
            )
        }

        val meanFreq = if (acousticNodes.isNotEmpty()) {
            acousticNodes.map { it.computeBaseFrequencyHz() }.average().toFloat()
        } else 320f

        val avgHeaviness = if (particles.isNotEmpty()) {
            particles.map { it.charge.coerceIn(0f, 1f) }.average().toFloat()
        } else 0.35f

        // Real field metrics: harmony from spatial dispersion + sensation spread
        // + weight, intensity from accumulated weight + pace + dispersion.
        // Empty field converges to a neutral middle instead of fake highs.
        val dispersionNorm = if (particles.size >= 2) {
            var sum = 0f
            var pairs = 0
            for (i in particles.indices) {
                for (j in i + 1 until particles.size) {
                    sum += hypot(
                        particles[j].posX - particles[i].posX,
                        particles[j].posY - particles[i].posY
                    )
                    pairs++
                }
            }
            (sum / pairs / 1.16f).coerceIn(0f, 1f)
        } else 0.45f
        val heavSpread = if (particles.isNotEmpty()) {
            (particles.map { abs(it.charge.coerceIn(0f, 1f) - avgHeaviness) }.average().toFloat() * 2f)
                .coerceIn(0f, 1f)
        } else 0.3f

        val entropy = (avgHeaviness * 0.55f + prefs.bioArousal * 0.30f + dispersionNorm * 0.15f).coerceIn(0.08f, 0.96f)
        val coherence = (0.72f - dispersionNorm * 0.38f - heavSpread * 0.25f - avgHeaviness * 0.15f + prefs.bioFocus * 0.10f).coerceIn(0.12f, 0.97f)

        // Diagnose Tab 2 Architectural Spaces automatically from Tab 1 spheres (Item 8)
        val diagnosedZones = diagnoseArchitecturalSpacesFromField(
            baseZones, particles, strings.languageCode, strings,
            arousal = prefs.bioArousal, valence = prefs.bioValence, focus = prefs.bioFocus
        )

        NoesisUiState(
            currentTab = trans.first,
            showHelpModal = trans.fifth.first,
            openedJournalEntry = extraUi.second,
            preferences = prefs,
            strings = strings,
            particles = particles,
            selectedParticleId = trans.second ?: particles.firstOrNull()?.id,
            diagnosedZones = diagnosedZones,
            selectedZoneId = trans.third ?: diagnosedZones.firstOrNull()?.zone?.zoneId,
            logs = logs,
            currentSessionReading = extraUi.fourth,
            journalEntries = journalEntries,
            providers = providers,
            currentDraftPrompt = extraUi.third,
            currentThreadId = extraUi.fifth,
            isSimulating = trans.fourth,
            simulationProgressText = extraUi.first,
            latestGrounding = _latestGrounding.value,
            isVerifyingProvider = trans.fifth.second,
            lastVerificationReport = trans.fifth.third,
            statusBannerMessage = trans.fifth.fourth,
            phaseCoherence = coherence,
            entropyIndex = entropy,
            meanFrequencyHz = meanFreq,
            meanHeaviness = avgHeaviness
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NoesisUiState()
    )

    init {
        viewModelScope.launch {
            repository.seedInitialDataIfEmpty()
            soundEngine.startAmbientSynthesizerIfNeeded()
        }
    }

    /**
     * Automatically calculates the Clarity/Openness and Pressure/Rigidity of each mental space
     * in Tab 2 based on the positions, importance (mass), and sensation heaviness (charge)
     * of the spheres in Tab 1, plus generates a contextual empirical interpretation (Item 8).
     */
    private fun diagnoseArchitecturalSpacesFromField(
        zones: List<ArchitecturalZoneEntity>,
        particles: List<ConceptParticleEntity>,
        langCode: String,
        strings: StringsCatalog,
        arousal: Float,
        valence: Float,
        focus: Float
    ): List<DiagnosedZoneState> {
        if (particles.isEmpty()) {
            return zones.map { z ->
                DiagnosedZoneState(
                    zone = z,
                    diagnosedClarity = z.luminosity,
                    diagnosedPressure = z.tensionIndex,
                    influencingConcepts = emptyList(),
                    empiricalInterpretation = strings.zoneDescription(z.zoneId, z.architecturalDescription)
                )
            }
        }

        return zones.map { zone ->
            // Calculate spatial & semantic affinity between each Tab 1 sphere and this mental room
            val rankedParticles = particles.map { p ->
                val dist = hypot(p.posX - zone.mapX, p.posY - zone.mapY).coerceAtLeast(0.05f)
                val spatialAffinity = (1.1f - dist).coerceIn(0.15f, 1.0f)
                val archetypeBoost = if (matchesZoneArchetype(zone.zoneId, p.archetypeKey, p.name)) 1.55f else 1.0f
                val weightInfluence = spatialAffinity * archetypeBoost * (p.mass / 1.5f)
                Triple(p, dist, weightInfluence)
            }.sortedByDescending { it.third }

            val topInfluencers = rankedParticles.take(2).map { it.first }
            val topNames = topInfluencers.map { formatConceptTitleCase(it.name) }

            val weightedHeaviness = if (topInfluencers.isNotEmpty()) {
                topInfluencers.map { it.charge.coerceIn(0f, 1f) }.average().toFloat()
            } else 0.4f

            val weightedImportance = if (topInfluencers.isNotEmpty()) {
                topInfluencers.map { ((it.mass - 0.4f) / 2.1f).coerceIn(0f, 1f) }.average().toFloat()
            } else 0.5f

            // Pressure rises with nearby heaviness, importance AND bodily pace;
            // clarity rises with lightness + emotional openness, falls with pressure.
            // Typical mixed states land mid-scale; extremes reach both ends.
            val diagnosedPressure = (weightedHeaviness * 0.55f + weightedImportance * 0.20f + arousal * 0.25f).coerceIn(0.10f, 0.95f)
            val diagnosedClarity = (0.62f - weightedHeaviness * 0.45f - diagnosedPressure * 0.25f + valence * 0.30f + focus * 0.08f).coerceIn(0.12f, 0.96f)

            val empiricalText = buildEmpiricalRoomDiagnosis(
                zone = zone,
                clarityPct = (diagnosedClarity * 100).toInt(),
                pressurePct = (diagnosedPressure * 100).toInt(),
                influencers = topNames,
                langCode = langCode,
                strings = strings
            )

            DiagnosedZoneState(
                zone = zone.copy(luminosity = diagnosedClarity, tensionIndex = diagnosedPressure),
                diagnosedClarity = diagnosedClarity,
                diagnosedPressure = diagnosedPressure,
                influencingConcepts = topNames,
                empiricalInterpretation = empiricalText
            )
        }
    }

    private fun matchesZoneArchetype(zoneId: String, archetypeKey: String, name: String): Boolean {
        val lower = name.lowercase()
        return when (zoneId) {
            "zone_fear" -> archetypeKey == "FEAR" || lower.contains("medo") || lower.contains("dúvida")
            "zone_desire" -> archetypeKey == "DESIRE" || lower.contains("desejo") || lower.contains("vontade")
            "zone_time" -> archetypeKey == "TIME" || lower.contains("tempo") || lower.contains("saudade")
            "zone_identity" -> archetypeKey == "IDENTITY" || lower.contains("identidade") || lower.contains("eu")
            "zone_justice" -> archetypeKey == "JUSTICE" || lower.contains("justiça") || lower.contains("equilíbrio")
            "zone_power" -> archetypeKey == "POWER" || archetypeKey == "DOMINATION" || archetypeKey == "ANGER" || lower.contains("foco") || lower.contains("força")
            "zone_emptiness" -> archetypeKey == "EMPTINESS" || lower.contains("liberdade") || lower.contains("silêncio")
            else -> false
        }
    }

    private fun buildEmpiricalRoomDiagnosis(
        zone: ArchitecturalZoneEntity,
        clarityPct: Int,
        pressurePct: Int,
        influencers: List<String>,
        langCode: String,
        strings: StringsCatalog
    ): String {
        val lang = langCode.lowercase()
        val andWord = when {
            lang == "en" -> " and "
            lang == "es" -> " y "
            lang == "fr" -> " et "
            else -> " e "
        }
        val fallbackSpheres = when {
            lang == "en" -> "your current spheres"
            lang == "es" -> "tus esferas actuales"
            lang == "fr" -> "vos sphères actuelles"
            else -> "suas esferas atuais"
        }
        val conceptsStr = if (influencers.isNotEmpty()) influencers.joinToString(andWord) else fallbackSpheres
        val isClear = clarityPct >= 60
        val isPressured = pressurePct >= 55
        val zoneTitle = strings.zoneTitle(zone.zoneId, zone.customTitle)
        val zoneDomain = strings.zoneDomain(zone.zoneId, zone.conceptDomain)

        return when (lang) {
            "en" -> {
                val reading = when {
                    isClear && !isPressured -> "an open sanctuary ($clarityPct% clarity, only $pressurePct% pressure): **$conceptsStr** flow freely here, and this room returns energy instead of consuming it. Let this area set the pace for the rest of the day."
                    isClear && isPressured -> "a tense lucidity ($clarityPct% clarity under $pressurePct% pressure): you see **$conceptsStr** clearly, but carrying them costs effort. Clarity without rest becomes vigilance — schedule deliberate decompression."
                    !isClear && !isPressured -> "a soft fog ($clarityPct% clarity, $pressurePct% pressure): **$conceptsStr** rest here unresolved but unhurried. Not everything needs deciphering today; some rooms heal by being left alone."
                    else -> "a storm cell ($clarityPct% clarity, $pressurePct% pressure): **$conceptsStr** churn here. Reduce one demand, name one need out loud, and let the pressure drop before deciding anything."
                }
                "Empirically, the position and weight of **$conceptsStr** shape **$zoneTitle** into $reading"
            }
            "es" -> {
                val reading = when {
                    isClear && !isPressured -> "un santuario abierto ($clarityPct% de claridad y solo $pressurePct% de presión): **$conceptsStr** circulan libres aquí, y este ambiente devuelve energía en lugar de consumirla. Deja que este lugar marque el ritmo del resto del día."
                    isClear && isPressured -> "una lucidez tensa ($clarityPct% de claridad bajo $pressurePct% de presión): ves **$conceptsStr** con nitidez, pero sostenerlos cuesta esfuerzo. Claridad sin descanso se vuelve vigilancia: programa una descompresión deliberada."
                    !isClear && !isPressured -> "una niebla amable ($clarityPct% de claridad, $pressurePct% de presión): **$conceptsStr** descansan aquí sin resolverse y sin prisa. No todo necesita descifrarse hoy; algunas salas sanan si las dejamos en paz."
                    else -> "una zona de tormenta ($clarityPct% de claridad, $pressurePct% de presión): **$conceptsStr** se agitan aquí. Reduce una exigencia, nombra en voz alta una necesidad y deja que la presión baje antes de decidir nada."
                }
                "Por la posición y el peso de **$conceptsStr** en la Pestaña 1, el ambiente **$zoneTitle** ($zoneDomain) se configura hoy como $reading"
            }
            "fr" -> {
                val reading = when {
                    isClear && !isPressured -> "un sanctuaire ouvert ($clarityPct% de clarté, seulement $pressurePct% de pression) : **$conceptsStr** circulent librement ici, et cette pièce rend de l'énergie au lieu d'en consommer. Laissez cet espace donner le rythme du reste de la journée."
                    isClear && isPressured -> "une lucidité tendue ($clarityPct% de clarté sous $pressurePct% de pression) : vous voyez **$conceptsStr** avec netteté, mais les porter demande un effort. La clarté sans repos devient de la vigilance — planifiez une décompression délibérée."
                    !isClear && !isPressured -> "une brume douce ($clarityPct% de clarté, $pressurePct% de pression) : **$conceptsStr** reposent ici sans être résolus et sans hâte. Tout n'a pas besoin d'être déchiffré aujourd'hui ; certaines pièces guérissent quand on les laisse tranquilles."
                    else -> "une zone de tempête ($clarityPct% de clarté, $pressurePct% de pression) : **$conceptsStr** s'agitent ici. Réduisez une exigence, nommez à voix haute un besoin et laissez la pression retomber avant de décider quoi que ce soit."
                }
                "Par la position et le poids de **$conceptsStr** dans l'Onglet 1, l'ambiance **$zoneTitle** ($zoneDomain) se présente aujourd'hui comme $reading"
            }
            else -> {
                val reading = when {
                    isClear && !isPressured -> "um santuário aberto ($clarityPct% de abertura, só $pressurePct% de pressão): **$conceptsStr** circulam livres por aqui, e este ambiente devolve energia em vez de consumi-la. Deixe este cômodo ditar o ritmo do resto do dia."
                    isClear && isPressured -> "uma lucidez tensa ($clarityPct% de clareza sob $pressurePct% de pressão): você enxerga **$conceptsStr** com nitidez, mas sustentá-los custa esforço. Clareza sem descanso vira vigilância — programe descompressão deliberada."
                    !isClear && !isPressured -> "uma névoa mansa ($clarityPct% de clareza, $pressurePct% de pressão): **$conceptsStr** repousam aqui sem resolução e sem pressa. Nem tudo precisa ser decifrado hoje; alguns cômodos se curam sendo deixados em paz."
                    else -> "uma zona de tempestade ($clarityPct% de clareza, $pressurePct% de pressão): **$conceptsStr** se agitam aqui. Reduza uma exigência, nomeie uma necessidade em voz alta e deixe a pressão baixar antes de decidir qualquer coisa."
                }
                "Pela posição e pelo peso de **$conceptsStr** na Aba 1, o ambiente **$zoneTitle** (${zoneDomain.lowercase()}) configura-se hoje como $reading"
            }
        }
    }

    fun toggleHelpModal(show: Boolean) {
        _showHelpModal.value = show
    }

    fun openJournalEntry(entry: JournalEntryEntity?) {
        _openedJournalEntry.value = entry
    }

    fun updateDraftPrompt(text: String) {
        if (_currentDraftPrompt.value != text) _currentDraftPrompt.value = text
    }

    fun selectTab(tab: NoesisTab) {
        _currentTab.value = tab
    }

    fun selectParticle(particle: ConceptParticleEntity) {
        _selectedParticleId.value = particle.id
        recordImplicitKineticTouch(dragSpeedPx = 10f)
        soundEngine.triggerSphereTouchChord(
            importanceMass = particle.mass,
            weightHeaviness = particle.charge
        )
    }

    fun commitParticlePosition(particle: ConceptParticleEntity, newX: Float, newY: Float, dragSpeed: Float) {
        recordImplicitKineticTouch(dragSpeedPx = dragSpeed)
        viewModelScope.launch {
            repository.updateParticle(
                particle.copy(
                    name = formatConceptTitleCase(particle.name),
                    posX = newX.coerceIn(0.08f, 0.92f),
                    posY = newY.coerceIn(0.10f, 0.90f)
                )
            )
        }
    }

    /**
     * Updates Importance (mass: 0.4..2.5 -> Hue Blue to Red) and Sensation Heaviness
     * (charge: 0.0..1.0 -> Light to Heavy Saturation), automatically recalculating pitch & sound!
     */
    fun updateParticleImportanceAndHeaviness(
        particle: ConceptParticleEntity,
        importanceMass: Float,
        heavinessCharge: Float
    ) {
        val clampedMass = importanceMass.coerceIn(0.4f, 2.5f)
        val clampedCharge = heavinessCharge.coerceIn(0.0f, 1.0f)
        val normImp = ((clampedMass - 0.4f) / 2.1f).coerceIn(0f, 1f)
        val autoFreqHz = (510f - (normImp * 200f) - (clampedCharge * 165f)).coerceIn(115f, 528f)

        soundEngine.triggerSphereTouchChord(
            importanceMass = clampedMass,
            weightHeaviness = clampedCharge,
            durationMs = 240
        )

        viewModelScope.launch {
            repository.updateParticle(
                particle.copy(
                    name = formatConceptTitleCase(particle.name),
                    mass = clampedMass,
                    charge = clampedCharge,
                    frequencyHz = autoFreqHz
                )
            )
        }
    }

    fun injectConceptParticle(rawName: String, archetypeKey: String = "CUSTOM") {
        val cleanName = formatConceptTitleCase(rawName)
        if (cleanName.isBlank()) return

        val preset = resolveArchetypeSpec(cleanName, archetypeKey, uiState.value.strings)
        soundEngine.triggerSphereTouchChord(
            importanceMass = preset.mass,
            weightHeaviness = preset.charge,
            durationMs = 420
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
        viewModelScope.launch {
            repository.deleteParticle(id)
        }
    }

    fun resetFieldParticles() {
        viewModelScope.launch {
            repository.resetParticlesToDefault()
        }
    }

    fun selectArchitecturalZone(zoneId: String) {
        _selectedZoneId.value = zoneId
        val diagnosed = uiState.value.diagnosedZones.find { it.zone.zoneId == zoneId }
        if (diagnosed != null) {
            soundEngine.triggerSphereTouchChord(
                importanceMass = 0.6f + diagnosed.diagnosedClarity * 1.4f,
                weightHeaviness = diagnosed.diagnosedPressure,
                durationMs = 380
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

        val conceptNames = state.particles.map { formatConceptTitleCase(it.name) }
        val promptCandidate = customPrompt?.trim()?.takeIf { it.isNotBlank() }
            ?: state.currentDraftPrompt.trim().takeIf { it.isNotBlank() }

        val promptToUse = promptCandidate ?: when (state.strings.languageCode) {
            "en" -> "What does the combination of ${conceptNames.joinToString(", ")} reveal about my current state?"
            "es" -> "¿Qué revela la combinación de ${conceptNames.joinToString(", ")} sobre mi momento actual?"
            "fr" -> "Que révèle la combinaison de ${conceptNames.joinToString(", ")} sur mon état actuel ?"
            else -> "O que a combinação entre ${conceptNames.joinToString(", ")} revela sobre o meu momento atual?"
        }

        _currentTab.value = NoesisTab.ORACLE
        _isSimulating.value = true
        _currentSessionReading.value = null
        _simulationProgressText.value = state.strings.synthesizingMessage
        AppLog.i("sim", "start concepts=${conceptNames.size} promptChars=${promptToUse.length}")

        viewModelScope.launch {
            try {
                val particlesSummary = state.particles.joinToString("; ") {
                    val tempDesc = when {
                        it.mass > 1.8f -> "Cor Quente/Alta Importância"
                        it.mass < 1.0f -> "Cor Fria/Suave Importância"
                        else -> "Cor Intermediária/Média Importância"
                    }
                    val weightDesc = if (it.charge >= 0.55f) "Sensação Pesada (${(it.charge * 100).toInt()}%)" else "Sensação Leve (${(it.charge * 100).toInt()}%)"
                    "${formatConceptTitleCase(it.name)} ($tempDesc, $weightDesc)"
                }

                val spatialSpheres = state.particles.map {
                    SphereSpatialDescriptor(
                        name = formatConceptTitleCase(it.name),
                        importanceMass = it.mass,
                        weightHeaviness = it.charge.coerceIn(0f, 1f),
                        posX = it.posX,
                        posY = it.posY
                    )
                }
                // Mental spaces as anchors: the reading must know whether each
                // sphere sits inside, beside or between these states.
                val zoneDescriptors = state.diagnosedZones.map { dz ->
                    ZoneSpatialDescriptor(
                        zoneId = dz.zone.zoneId,
                        title = state.strings.zoneTitle(dz.zone.zoneId, dz.zone.customTitle),
                        mapX = dz.zone.mapX,
                        mapY = dz.zone.mapY
                    )
                }

                val engine = repository.resolveActiveEngine()
                val activeProvider = engine?.first
                val activeKey = engine?.second
                val effectiveLang = AppLanguage.resolveEffectiveLanguage(state.preferences.language)

                val result = networkService.orchestrateConsciousSimulation(
                    userPrompt = promptToUse,
                    particlesSummary = particlesSummary,
                    conceptNames = conceptNames,
                    spatialSpheres = spatialSpheres,
                    zones = zoneDescriptors,
                    arousal = state.preferences.bioArousal,
                    valence = state.preferences.bioValence,
                    focus = state.preferences.bioFocus,
                    languageInstruction = state.strings.languageInstructionForAI,
                    languageCode = effectiveLang.code,
                    activeProviderName = activeProvider?.name,
                    activeBaseUrl = engine?.third,
                    activeApiKey = activeKey?.apiKey,
                    activeModelId = activeProvider?.selectedModel,
                    onStageProgress = { stageMsg ->
                        _simulationProgressText.value = stageMsg
                    }
                )

                _latestGrounding.value = result.grounding

                val sessionLogEntity = SimulationLogEntity(
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

                _currentSessionReading.value = sessionLogEntity
                repository.addSimulationLog(sessionLogEntity)
                AppLog.i("sim", "done via '${result.providerLabel}' narrativeChars=${result.narrativeMarkdown.length}")
                _currentThreadId.value = autoSaveThreadFromReading(
                    prompt = promptToUse,
                    narrative = result.narrativeMarkdown,
                    somatic = result.somaticShiftSuggestion
                )
            } catch (e: Exception) {
                AppLog.e("sim", "simulation failed: ${e.message}")
                throw e
            } finally {
                _isSimulating.value = false
            }
        }
    }

    /**
     * Saves an immutable snapshot of the current gravitational map, architectural diagnosis,
     * user prompt notes, and latest reflection into the "Diário de Bordo" (Item 16).
     */
    fun saveCurrentStateToJournal() {
        viewModelScope.launch {
            persistJournalSnapshot(uiState.value)
            _statusBannerMessage.value = uiState.value.strings.journalSavedToast
        }
    }

    /**
     * Item 7: inicia uma nova sessão limpa. O estado atual já é salvo
     * automaticamente a cada resposta; aqui garante o snapshot e reseta o campo.
     */
    fun startNewSession() {
        viewModelScope.launch {
            val state = uiState.value
            if (state.particles.isNotEmpty()) {
                persistJournalSnapshot(state)
            }
            repository.resetParticlesToDefault()
            _currentDraftPrompt.value = ""
            _currentSessionReading.value = null
            _currentThreadId.value = null
            _currentTab.value = NoesisTab.FIELD
            _statusBannerMessage.value = state.strings.newSessionStartedToast
        }
    }

    private suspend fun persistJournalSnapshot(state: NoesisUiState): Long {
        val particlesJson = JSONArray().apply {
            state.particles.forEach { p ->
                put(JSONObject().apply {
                    put("name", formatConceptTitleCase(p.name))
                    put("mass", p.mass.toDouble())
                    put("charge", p.charge.toDouble())
                    put("frequencyHz", 320.0)
                    put("spin", 0.5)
                    put("posX", p.posX.toDouble())
                    put("posY", p.posY.toDouble())
                    put("colorHex", 0xFFD97757)
                    put("philosophicalNote", "")
                    put("createdAt", System.currentTimeMillis())
                    put("archetypeKey", "CUSTOM")
                })
            }
        }.toString()

        val zonesJson = JSONArray().apply {
            state.diagnosedZones.forEach { dz ->
                put(JSONObject().apply {
                    put("customTitle", dz.zone.customTitle)
                    put("conceptDomain", dz.zone.conceptDomain)
                    put("clarity", dz.diagnosedClarity.toDouble())
                    put("pressure", dz.diagnosedPressure.toDouble())
                    put("empiricalInterpretation", dz.empiricalInterpretation)
                })
            }
        }.toString()

        val conceptTitleSummary = state.particles.take(4).joinToString(" · ") { formatConceptTitleCase(it.name) }
            .ifBlank { state.strings.uiMentalStateLabel }
        val latestLog = state.currentSessionReading
        val userNotes = state.currentDraftPrompt.takeIf { it.isNotBlank() }
            ?: latestLog?.userPrompt
            ?: ""
        val narrative = latestLog?.narrativeResponse ?: state.strings.uiSnapshotSavedNote
        val somatic = latestLog?.somaticSuggestion ?: ""

        return repository.addJournalEntry(
            JournalEntryEntity(
                timestamp = System.currentTimeMillis(),
                titleSummary = conceptTitleSummary,
                particlesSnapshotJson = particlesJson,
                zonesSnapshotJson = zonesJson,
                userNotesPrompt = userNotes,
                narrativeText = narrative,
                somaticExerciseText = somatic,
                bioArousal = state.preferences.bioArousal,
                bioValence = state.preferences.bioValence,
                bioFocus = state.preferences.bioFocus
            )
        )
    }

    fun chatMessagesFlow(entryId: Long?): Flow<List<ChatMessageEntity>> =
        if (entryId == null) flowOf(emptyList()) else repository.observeChatMessages(entryId)

    private suspend fun autoSaveThreadFromReading(
        prompt: String,
        narrative: String,
        somatic: String
    ): Long {
        val newId = persistJournalSnapshot(uiState.value)
        repository.addChatMessage(
            ChatMessageEntity(
                entryId = newId,
                role = "assistant",
                text = narrative,
                somaticText = somatic
            )
        )
        return newId
    }

    /**
     * Fase 3: continua a conversa da thread (gera resposta adaptada + nova leva
     * de exercícios) e atualiza o card com a última resposta.
     */
    fun sendThreadReply(entryId: Long, text: String) {
        val clean = text.trim()
        if (clean.isBlank() || uiState.value.isSimulating) return
        _isSimulating.value = true
        _simulationProgressText.value = uiState.value.strings.synthesizingMessage
        viewModelScope.launch {
            try {
                repository.addChatMessage(
                    ChatMessageEntity(entryId = entryId, role = "user", text = clean)
                )
                val state = uiState.value
                val history = repository.observeChatMessages(entryId)
                    .let { flow -> flow.first() }
                val recap = history.joinToString("\n") { m ->
                    if (m.role == "user") {
                        "${state.strings.uiUserPrefix}: ${m.text}"
                    } else {
                        "${state.strings.uiAssistantPrefix}: ${m.text.take(500)}"
                    }
                }
                val conceptNames = state.particles.map { formatConceptTitleCase(it.name) }
                val particlesSummary = state.particles.joinToString("; ") {
                    "${formatConceptTitleCase(it.name)}"
                }
                val spatialSpheres = state.particles.map {
                    SphereSpatialDescriptor(
                        name = formatConceptTitleCase(it.name),
                        importanceMass = it.mass,
                        weightHeaviness = it.charge.coerceIn(0f, 1f),
                        posX = it.posX,
                        posY = it.posY
                    )
                }
                val zoneDescriptors = state.diagnosedZones.map { dz ->
                    ZoneSpatialDescriptor(
                        zoneId = dz.zone.zoneId,
                        title = state.strings.zoneTitle(dz.zone.zoneId, dz.zone.customTitle),
                        mapX = dz.zone.mapX,
                        mapY = dz.zone.mapY
                    )
                }
                val engine = repository.resolveActiveEngine()
                val effectiveLang = AppLanguage.resolveEffectiveLanguage(state.preferences.language)
                val result = networkService.orchestrateFollowUp(
                    threadRecap = recap,
                    newUserText = clean,
                    particlesSummary = particlesSummary.ifBlank { conceptNames.joinToString(", ") },
                    conceptNames = conceptNames,
                    spatialSpheres = spatialSpheres,
                    zones = zoneDescriptors,
                    arousal = state.preferences.bioArousal,
                    valence = state.preferences.bioValence,
                    focus = state.preferences.bioFocus,
                    languageInstruction = state.strings.languageInstructionForAI,
                    languageCode = effectiveLang.code,
                    activeProviderName = engine?.first?.name,
                    activeBaseUrl = engine?.third,
                    activeApiKey = engine?.second?.apiKey,
                    activeModelId = engine?.first?.selectedModel,
                    onStageProgress = { stageMsg -> _simulationProgressText.value = stageMsg }
                )
                repository.addChatMessage(
                    ChatMessageEntity(
                        entryId = entryId,
                        role = "assistant",
                        text = result.narrativeMarkdown,
                        somaticText = result.somaticShiftSuggestion
                    )
                )
                val prev = state.currentSessionReading
                if (prev != null) {
                    _currentSessionReading.value = prev.copy(
                        narrativeResponse = result.narrativeMarkdown,
                        somaticSuggestion = result.somaticShiftSuggestion,
                        providerUsed = result.providerLabel
                    )
                }
                AppLog.i("sim", "thread reply done via '${result.providerLabel}'")
            } catch (e: Exception) {
                AppLog.e("sim", "thread reply failed: ${e.message}")
                throw e
            } finally {
                _isSimulating.value = false
            }
        }
    }

    fun deleteJournalEntry(entryId: Long) {
        viewModelScope.launch {
            repository.deleteJournalEntry(entryId)
            if (_openedJournalEntry.value?.id == entryId) {
                _openedJournalEntry.value = null
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { repository.setLanguage(language) }
    }

    fun providerKeysFlow(providerId: Long) = repository.observeKeysFlow(providerId)

    /**
     * Fase 2: verifica um slot de chave específico (usa o accountId p/ Cloudflare),
     * persiste chave+conta e atualiza modelos verificados do provedor.
     */
    fun verifyKeySlot(
        providerId: Long,
        keyId: Long,
        apiKeyText: String,
        accountIdText: String
    ) {
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
                val provider = repository.daoProviderById(providerId)
                if (provider == null) return@launch
                val resolvedUrl = repository.resolveBaseUrl(provider.baseUrl, accountIdText)
                val report = networkService.verifyProviderAndPingModels(
                    rawBaseUrl = resolvedUrl,
                    apiKey = apiKeyText.trim(),
                    pingWord = pingWord,
                    langCode = uiState.value.strings.languageCode
                )
                _lastVerificationReport.value = report

                repository.updateKeySlot(
                    AiProviderKeyEntity(
                        id = keyId,
                        providerId = providerId,
                        label = "",
                        apiKey = apiKeyText.trim(),
                        accountId = accountIdText.trim(),
                        isActive = true,
                        sortOrder = 0
                    ).let { incoming ->
                        val current = repository.getProviderKeys(providerId).firstOrNull { it.id == keyId }
                        incoming.copy(
                            label = current?.label ?: uiState.value.strings.uiKeyLabel,
                            sortOrder = current?.sortOrder ?: 0
                        )
                    }
                )
                repository.setActiveKeySlot(keyId, providerId)

                val verifiedModels = report.verifiedResponsiveModels
                val firstModel = verifiedModels.firstOrNull() ?: provider.selectedModel
                repository.updateProviderVerification(
                    provider.copy(
                        verifiedModelsCsv = verifiedModels.joinToString(","),
                        selectedModel = firstModel,
                        isActive = verifiedModels.isNotEmpty(),
                        lastVerifiedAt = System.currentTimeMillis()
                    )
                )
            } finally {
                _isVerifyingProvider.value = false
            }
        }
    }

    fun ensureCustomProvider(name: String, baseUrl: String) {
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            val existing = repository.findProviderByUrl(baseUrl)
            if (existing == null) {
                repository.createCustomProvider(name, baseUrl)
            }
        }
    }

    fun updateCustomProviderDetails(providerId: Long, name: String, baseUrl: String) {
        viewModelScope.launch {
            val provider = repository.daoProviderById(providerId) ?: return@launch
            repository.updateCustomProvider(provider.copy(name = name.trim().ifBlank { provider.name }, baseUrl = baseUrl.trim()))
        }
    }

    fun addKeySlot(providerId: Long) {
        viewModelScope.launch { repository.addKeySlot(providerId) }
    }

    fun deleteKeySlot(keyId: Long, providerId: Long) {
        viewModelScope.launch { repository.deleteKeySlot(keyId, providerId) }
    }

    fun setActiveKeySlot(keyId: Long, providerId: Long) {
        viewModelScope.launch { repository.setActiveKeySlot(keyId, providerId) }
    }

    fun activateProviderModel(providerId: Long, modelId: String) {
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
        soundEngine.releaseEngine()
    }

    private data class ArchetypeSpec(
        val archetypeKey: String,
        val mass: Float,
        val charge: Float, // 0.0 (Leve) .. 1.0 (Pesada)
        val frequencyHz: Float,
        val spin: Float,
        val colorHex: Long,
        val note: String
    )

    private fun resolveArchetypeSpec(name: String, keyHint: String, strings: StringsCatalog): ArchetypeSpec {
        val lower = name.lowercase()
        return when {
            keyHint == "FEAR" || lower.contains("medo") || lower.contains("fear") || lower.contains("dúvida") || lower.contains("ansiedade") ->
                ArchetypeSpec("FEAR", 1.35f, 0.78f, 210f, -1.2f, 0xFF7A6F9B, strings.archetypeNoteFear)
            keyHint == "DESIRE" || lower.contains("desejo") || lower.contains("desire") || lower.contains("paixão") || lower.contains("vontade") ->
                ArchetypeSpec("DESIRE", 2.05f, 0.42f, 285f, 1.2f, 0xFFD97757, strings.archetypeNoteDesire)
            keyHint == "TIME" || lower.contains("tempo") || lower.contains("time") || lower.contains("saudade") || lower.contains("futuro") ->
                ArchetypeSpec("TIME", 1.65f, 0.38f, 340f, 0.6f, 0xFFD4AF59, strings.archetypeNoteTime)
            keyHint == "IDENTITY" || lower.contains("identidade") || lower.contains("identity") || lower.contains("eu") ->
                ArchetypeSpec("IDENTITY", 1.50f, 0.35f, 360f, 0.9f, 0xFF5A8F99, strings.archetypeNoteIdentity)
            keyHint == "JUSTICE" || lower.contains("justiça") || lower.contains("justice") || lower.contains("equilíbrio") ->
                ArchetypeSpec("JUSTICE", 1.45f, 0.25f, 396f, 0.3f, 0xFF7D9471, strings.archetypeNoteJustice)
            keyHint == "DOMINATION" || lower.contains("dominação") || lower.contains("controle") || lower.contains("cobrança") ->
                ArchetypeSpec("DOMINATION", 2.10f, 0.85f, 165f, -1.1f, 0xFFB84A39, strings.archetypeNoteDomination)
            keyHint == "POWER" || lower.contains("poder") || lower.contains("força") || lower.contains("foco") ->
                ArchetypeSpec("POWER", 1.85f, 0.50f, 290f, 1.3f, 0xFFC98A4B, strings.archetypeNotePower)
            keyHint == "ANGER" || lower.contains("raiva") || lower.contains("irritação") || lower.contains("fúria") ->
                ArchetypeSpec("ANGER", 2.25f, 0.88f, 150f, 1.6f, 0xFFD14949, strings.archetypeNoteAnger)
            keyHint == "EMPTINESS" || lower.contains("silêncio") || lower.contains("calma") || lower.contains("liberdade") ->
                ArchetypeSpec("EMPTINESS", 0.75f, 0.15f, 460f, 0.1f, 0xFF8AA37B, strings.archetypeNoteEmptiness)
            else -> {
                val hash = abs(name.hashCode())
                val mass = (0.65f + (hash % 165) / 100f).coerceIn(0.4f, 2.5f)
                val charge = ((hash % 75) + 15) / 100f // 0.15f .. 0.90f
                val normImp = ((mass - 0.4f) / 2.1f).coerceIn(0f, 1f)
                val freq = (510f - (normImp * 200f) - (charge * 165f)).coerceIn(115f, 528f)
                ArchetypeSpec(
                    archetypeKey = "CUSTOM",
                    mass = mass,
                    charge = charge,
                    frequencyHz = freq,
                    spin = ((hash % 200) - 100) / 100f,
                    colorHex = 0xFFD97757,
                    note = strings.archetypeNoteCustom
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
