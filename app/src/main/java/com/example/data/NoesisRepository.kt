package com.example.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private val Context.dataStore by preferencesDataStore(name = "noesis_settings")

data class UserPreferencesState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val soundEnabled: Boolean = true,
    val bioArousal: Float = 0.45f,
    val bioValence: Float = 0.60f,
    val bioFocus: Float = 0.75f
)

class NoesisRepository(
    private val context: Context,
    private val dao: NoesisDao
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val BIO_AROUSAL = floatPreferencesKey("bio_arousal")
        val BIO_VALENCE = floatPreferencesKey("bio_valence")
        val BIO_FOCUS = floatPreferencesKey("bio_focus")
    }

    val preferencesFlow: Flow<UserPreferencesState> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        val langStr = prefs[Keys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.code
        UserPreferencesState(
            themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK),
            language = AppLanguage.fromCode(langStr),
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            bioArousal = prefs[Keys.BIO_AROUSAL] ?: 0.45f,
            bioValence = prefs[Keys.BIO_VALENCE] ?: 0.60f,
            bioFocus = prefs[Keys.BIO_FOCUS] ?: 0.75f
        )
    }

    val particlesFlow: Flow<List<ConceptParticleEntity>> = dao.observeParticles()
    val zonesFlow: Flow<List<ArchitecturalZoneEntity>> = dao.observeZones()
    val logsFlow: Flow<List<SimulationLogEntity>> = dao.observeSimulationLogs()
    val providersFlow: Flow<List<AiProviderEntity>> = dao.observeProviders()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun updateBiofeedback(arousal: Float, valence: Float, focus: Float) {
        context.dataStore.edit {
            it[Keys.BIO_AROUSAL] = arousal.coerceIn(0f, 1f)
            it[Keys.BIO_VALENCE] = valence.coerceIn(0f, 1f)
            it[Keys.BIO_FOCUS] = focus.coerceIn(0f, 1f)
        }
    }

    suspend fun seedInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.getAllParticlesOnce().isEmpty()) {
            dao.insertParticles(defaultParticles())
        }
        if (dao.getAllZonesOnce().isEmpty()) {
            dao.insertZones(defaultZones())
        }
        if (dao.getAllLogsOnce().isEmpty()) {
            dao.insertSimulationLog(
                SimulationLogEntity(
                    userPrompt = "Inauguração do Campo Perceptivo: Como o Desejo, o Medo e o Tempo dobram o espaço da consciência?",
                    activeConceptsCsv = "Desejo, Medo, Tempo, Identidade",
                    biofeedbackArousal = 0.48f,
                    biofeedbackValence = 0.65f,
                    biofeedbackFocus = 0.82f,
                    wikiSourcesJson = """[{"title":"Fenomenologia da Percepção (Merleau-Ponty)","snippet":"O corpo próprio não é apenas um objeto no mundo, mas o meio pelo qual há um mundo percebido; o espaço mental curva-se segundo a intencionalidade motora e afetiva."}]""",
                    academicSourcesJson = """[{"title":"Rizoma, Atratores Estranhos e Dinâmica Não-Linear na Cognição","authors":"G. Deleuze & F. Varela","year":"1991"}]""",
                    narrativeResponse = "Observe o campo diante de você: o **Medo** não é um bloco sólido, mas uma partícula de alta frequência (310 Hz) que contrai a métrica ao seu redor, criando uma micro-gravidade defensiva. Já o **Desejo** opera como um atrator de Deleuze — não uma falta, mas uma força produtiva que lança filamentos rizomáticos em direção ao **Tempo**.\n\nNa perspectiva de **Nagarjuna (Śūnyatā)**, nenhuma dessas partículas possui existência intrínseca (*svabhāva*): o Medo só vibra porque a **Identidade** tenta preservar uma fronteira estática contra o fluxo temporal.",
                    somaticSuggestion = "Respire lentamente sentindo a distância entre seus olhos e a tela. Imagine que a tensão do Medo se dissolve de um ponto rígido na garganta para uma onda circular larga e morna no peito. Sinta o peso gravitacional dos pensamentos diminuir em 30%.",
                    providerUsed = "Noesis Core Engine",
                    languageCode = "pt"
                )
            )
        }
    }

    suspend fun addParticle(particle: ConceptParticleEntity) = dao.insertParticle(particle)
    suspend fun updateParticle(particle: ConceptParticleEntity) = dao.updateParticle(particle)
    suspend fun deleteParticle(id: Long) = dao.deleteParticle(id)
    suspend fun resetParticlesToDefault() {
        dao.clearAllParticles()
        dao.insertParticles(defaultParticles())
    }

    suspend fun updateZone(zone: ArchitecturalZoneEntity) = dao.upsertZone(zone)
    suspend fun addSimulationLog(log: SimulationLogEntity) = dao.insertSimulationLog(log)

    suspend fun saveVerifiedProvider(provider: AiProviderEntity): Long {
        if (provider.isActive) {
            dao.deactivateAllProviders()
        }
        return dao.insertProvider(provider)
    }

    suspend fun activateProviderModel(providerId: Long, model: String) {
        dao.deactivateAllProviders()
        dao.activateProviderModel(providerId, model)
    }

    suspend fun deactivateAllProviders() {
        dao.deactivateAllProviders()
    }

    suspend fun deleteProvider(id: Long) = dao.deleteProvider(id)

    // Portable JSON Backup Export & Import via Android SAF (Storage Access Framework)
    suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = preferencesFlow.first()
            val particles = dao.getAllParticlesOnce()
            val zones = dao.getAllZonesOnce()
            val logs = dao.getAllLogsOnce()
            val providers = dao.getAllProvidersOnce()

            val root = JSONObject().apply {
                put("schemaVersion", 1)
                put("exportedAt", System.currentTimeMillis())
                put("app", "Noesis Lab")
                put("preferences", JSONObject().apply {
                    put("themeMode", prefs.themeMode.name)
                    put("language", prefs.language.code)
                    put("soundEnabled", prefs.soundEnabled)
                    put("bioArousal", prefs.bioArousal.toDouble())
                    put("bioValence", prefs.bioValence.toDouble())
                    put("bioFocus", prefs.bioFocus.toDouble())
                })
                put("particles", JSONArray().apply {
                    particles.forEach { p ->
                        put(JSONObject().apply {
                            put("name", p.name)
                            put("archetypeKey", p.archetypeKey)
                            put("mass", p.mass.toDouble())
                            put("charge", p.charge.toDouble())
                            put("frequencyHz", p.frequencyHz.toDouble())
                            put("spin", p.spin.toDouble())
                            put("posX", p.posX.toDouble())
                            put("posY", p.posY.toDouble())
                            put("colorHex", p.colorHex)
                            put("philosophicalNote", p.philosophicalNote)
                            put("createdAt", p.createdAt)
                        })
                    }
                })
                put("zones", JSONArray().apply {
                    zones.forEach { z ->
                        put(JSONObject().apply {
                            put("zoneId", z.zoneId)
                            put("titleKey", z.titleKey)
                            put("customTitle", z.customTitle)
                            put("conceptDomain", z.conceptDomain)
                            put("geometryType", z.geometryType)
                            put("elevationLevel", z.elevationLevel.toDouble())
                            put("luminosity", z.luminosity.toDouble())
                            put("tensionIndex", z.tensionIndex.toDouble())
                            put("resonantHz", z.resonantHz.toDouble())
                            put("mapX", z.mapX.toDouble())
                            put("mapY", z.mapY.toDouble())
                            put("connectedZonesCsv", z.connectedZonesCsv)
                            put("architecturalDescription", z.architecturalDescription)
                        })
                    }
                })
                put("logs", JSONArray().apply {
                    logs.forEach { l ->
                        put(JSONObject().apply {
                            put("userPrompt", l.userPrompt)
                            put("activeConceptsCsv", l.activeConceptsCsv)
                            put("biofeedbackArousal", l.biofeedbackArousal.toDouble())
                            put("biofeedbackValence", l.biofeedbackValence.toDouble())
                            put("biofeedbackFocus", l.biofeedbackFocus.toDouble())
                            put("wikiSourcesJson", l.wikiSourcesJson)
                            put("academicSourcesJson", l.academicSourcesJson)
                            put("narrativeResponse", l.narrativeResponse)
                            put("somaticSuggestion", l.somaticSuggestion)
                            put("providerUsed", l.providerUsed)
                            put("languageCode", l.languageCode)
                            put("timestamp", l.timestamp)
                        })
                    }
                })
                put("providers", JSONArray().apply {
                    providers.forEach { pr ->
                        put(JSONObject().apply {
                            put("name", pr.name)
                            put("baseUrl", pr.baseUrl)
                            put("apiKey", pr.apiKey)
                            put("verifiedModelsCsv", pr.verifiedModelsCsv)
                            put("selectedModel", pr.selectedModel)
                            put("isActive", pr.isActive)
                            put("lastVerifiedAt", pr.lastVerifiedAt)
                        })
                    }
                })
            }

            context.contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                    writer.write(root.toString(2))
                }
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    suspend fun importBackupFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val jsonText = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } ?: return@withContext false

            val root = JSONObject(jsonText)

            root.optJSONObject("preferences")?.let { prefObj ->
                setThemeMode(
                    runCatching { ThemeMode.valueOf(prefObj.optString("themeMode", "DARK")) }
                        .getOrDefault(ThemeMode.DARK)
                )
                setLanguage(AppLanguage.fromCode(prefObj.optString("language", "system")))
                setSoundEnabled(prefObj.optBoolean("soundEnabled", true))
                updateBiofeedback(
                    arousal = prefObj.optDouble("bioArousal", 0.45).toFloat(),
                    valence = prefObj.optDouble("bioValence", 0.60).toFloat(),
                    focus = prefObj.optDouble("bioFocus", 0.75).toFloat()
                )
            }

            root.optJSONArray("particles")?.let { arr ->
                val list = mutableListOf<ConceptParticleEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ConceptParticleEntity(
                            name = o.optString("name", "Concept"),
                            archetypeKey = o.optString("archetypeKey", "CUSTOM"),
                            mass = o.optDouble("mass", 1.0).toFloat(),
                            charge = o.optDouble("charge", 0.0).toFloat(),
                            frequencyHz = o.optDouble("frequencyHz", 432.0).toFloat(),
                            spin = o.optDouble("spin", 0.5).toFloat(),
                            posX = o.optDouble("posX", 0.5).toFloat(),
                            posY = o.optDouble("posY", 0.5).toFloat(),
                            colorHex = o.optLong("colorHex", 0xFFD97757),
                            philosophicalNote = o.optString("philosophicalNote", ""),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.clearAllParticles()
                    dao.insertParticles(list)
                }
            }

            root.optJSONArray("zones")?.let { arr ->
                val list = mutableListOf<ArchitecturalZoneEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ArchitecturalZoneEntity(
                            zoneId = o.optString("zoneId", "zone_$i"),
                            titleKey = o.optString("titleKey", ""),
                            customTitle = o.optString("customTitle", ""),
                            conceptDomain = o.optString("conceptDomain", ""),
                            geometryType = o.optString("geometryType", "ATRIUM"),
                            elevationLevel = o.optDouble("elevationLevel", 0.0).toFloat(),
                            luminosity = o.optDouble("luminosity", 0.5).toFloat(),
                            tensionIndex = o.optDouble("tensionIndex", 0.5).toFloat(),
                            resonantHz = o.optDouble("resonantHz", 432.0).toFloat(),
                            mapX = o.optDouble("mapX", 0.5).toFloat(),
                            mapY = o.optDouble("mapY", 0.5).toFloat(),
                            connectedZonesCsv = o.optString("connectedZonesCsv", ""),
                            architecturalDescription = o.optString("architecturalDescription", "")
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.clearAllZones()
                    dao.insertZones(list)
                }
            }

            root.optJSONArray("logs")?.let { arr ->
                val list = mutableListOf<SimulationLogEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        SimulationLogEntity(
                            userPrompt = o.optString("userPrompt", ""),
                            activeConceptsCsv = o.optString("activeConceptsCsv", ""),
                            biofeedbackArousal = o.optDouble("biofeedbackArousal", 0.5).toFloat(),
                            biofeedbackValence = o.optDouble("biofeedbackValence", 0.5).toFloat(),
                            biofeedbackFocus = o.optDouble("biofeedbackFocus", 0.5).toFloat(),
                            wikiSourcesJson = o.optString("wikiSourcesJson", "[]"),
                            academicSourcesJson = o.optString("academicSourcesJson", "[]"),
                            narrativeResponse = o.optString("narrativeResponse", ""),
                            somaticSuggestion = o.optString("somaticSuggestion", ""),
                            providerUsed = o.optString("providerUsed", "Noesis Core"),
                            languageCode = o.optString("languageCode", "pt"),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.clearAllLogs()
                    dao.insertSimulationLogs(list)
                }
            }

            root.optJSONArray("providers")?.let { arr ->
                val list = mutableListOf<AiProviderEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        AiProviderEntity(
                            name = o.optString("name", "Custom"),
                            baseUrl = o.optString("baseUrl", ""),
                            apiKey = o.optString("apiKey", ""),
                            verifiedModelsCsv = o.optString("verifiedModelsCsv", ""),
                            selectedModel = o.optString("selectedModel", ""),
                            isActive = o.optBoolean("isActive", false),
                            lastVerifiedAt = o.optLong("lastVerifiedAt", 0L)
                        )
                    )
                }
                dao.clearAllProviders()
                if (list.isNotEmpty()) {
                    dao.insertProviders(list)
                }
            }

            true
        }.getOrDefault(false)
    }

    companion object {
        fun defaultParticles(): List<ConceptParticleEntity> = listOf(
            ConceptParticleEntity(
                name = "Desejo",
                archetypeKey = "DESIRE",
                mass = 1.65f,
                charge = 0.85f,
                frequencyHz = 432f,
                spin = 1.2f,
                posX = 0.30f,
                posY = 0.36f,
                colorHex = 0xFFD97757,
                philosophicalNote = "Deleuze & Guattari: O desejo não é falta, mas máquina desejante e produção de fluxos no campo imanente."
            ),
            ConceptParticleEntity(
                name = "Medo",
                archetypeKey = "FEAR",
                mass = 1.40f,
                charge = -0.80f,
                frequencyHz = 310f,
                spin = -1.4f,
                posX = 0.72f,
                posY = 0.32f,
                colorHex = 0xFF7A6F9B,
                philosophicalNote = "Neurociência Afetiva: Contração amigdaliana do horizonte preditivo (Friston) para minimizar surpresa."
            ),
            ConceptParticleEntity(
                name = "Tempo",
                archetypeKey = "TIME",
                mass = 2.10f,
                charge = 0.15f,
                frequencyHz = 528f,
                spin = 0.6f,
                posX = 0.50f,
                posY = 0.62f,
                colorHex = 0xFFD4AF59,
                philosophicalNote = "Merleau-Ponty & Rovelli: O tempo não é uma linha externa, mas uma rede de relações térmicas e retenções/protensões vividas."
            ),
            ConceptParticleEntity(
                name = "Identidade",
                archetypeKey = "IDENTITY",
                mass = 1.50f,
                charge = 0.30f,
                frequencyHz = 396f,
                spin = 0.9f,
                posX = 0.25f,
                posY = 0.70f,
                colorHex = 0xFF5A8F99,
                philosophicalNote = "Nagarjuna & Metzinger: O 'Eu' como modelo fenomênico transparente (Ego Tunnel), vazio de substância isolada."
            ),
            ConceptParticleEntity(
                name = "Justiça",
                archetypeKey = "JUSTICE",
                mass = 1.75f,
                charge = 0.70f,
                frequencyHz = 639f,
                spin = 0.2f,
                posX = 0.76f,
                posY = 0.68f,
                colorHex = 0xFF7D9471,
                philosophicalNote = "Simetria relacional e equilíbrio homeostático coletivo no tecido intersubjetivo."
            )
        )

        fun defaultZones(): List<ArchitecturalZoneEntity> = listOf(
            ArchitecturalZoneEntity(
                zoneId = "zone_emptiness",
                titleKey = "archRoomEmptiness",
                customTitle = "Jardim de Śūnyatā",
                conceptDomain = "Vacuidade Relacional & Originação Dependente (Nagarjuna)",
                geometryType = "VOID_GARDEN",
                elevationLevel = 0.90f,
                luminosity = 0.92f,
                tensionIndex = 0.12f,
                resonantHz = 528f,
                mapX = 0.50f,
                mapY = 0.18f,
                connectedZonesCsv = "zone_time,zone_identity,zone_justice",
                architecturalDescription = "Um observatório suspenso de arcos abertos sem paredes fixas. Aqui toda estrutura revela sua interdependência: quando você altera uma coluna, todas as outras câmaras ajustam sua curvatura."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_identity",
                titleKey = "archRoomIdentity",
                customTitle = "Torre Autopoiética",
                conceptDomain = "Identidade, Eu Fenomênico & Fronteira Markov (Varela / Friston)",
                geometryType = "TOWER",
                elevationLevel = 0.55f,
                luminosity = 0.74f,
                tensionIndex = 0.42f,
                resonantHz = 396f,
                mapX = 0.22f,
                mapY = 0.36f,
                connectedZonesCsv = "zone_emptiness,zone_desire,zone_fear",
                architecturalDescription = "Uma espiral translúcida de espelhos semipermeáveis (Cobertor de Markov) que filtra o que é 'interno' e 'externo', reconstruindo continuamente a narrativa de quem observa."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_justice",
                titleKey = "archRoomJustice",
                customTitle = "Pórtico de Simetria",
                conceptDomain = "Justiça, Reciprocidade & Harmonia Geométrica",
                geometryType = "LATTICE",
                elevationLevel = 0.50f,
                luminosity = 0.80f,
                tensionIndex = 0.30f,
                resonantHz = 639f,
                mapX = 0.78f,
                mapY = 0.36f,
                connectedZonesCsv = "zone_emptiness,zone_power,zone_time",
                architecturalDescription = "Colunatas geodésicas em balanço perfeito. Cada viga sustenta o peso exato da viga oposta; qualquer assimetria de poder faz o piso inclinar-se visivelmente."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_time",
                titleKey = "archRoomTime",
                customTitle = "Galeria Cronos-Aiôn",
                conceptDomain = "Tempo Profundo, Memória & Horizonte Perceptivo (Merleau-Ponty)",
                geometryType = "SPIRAL",
                elevationLevel = 0.15f,
                luminosity = 0.65f,
                tensionIndex = 0.48f,
                resonantHz = 432f,
                mapX = 0.50f,
                mapY = 0.50f,
                connectedZonesCsv = "zone_emptiness,zone_desire,zone_fear,zone_justice,zone_power",
                architecturalDescription = "O eixo central do laboratório mental. Passarelas helicoidais onde o passado (retenção) e o futuro (protensão) se cruzam no instante presente espesso."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_desire",
                titleKey = "archRoomDesire",
                customTitle = "Átrio Gravitacional",
                conceptDomain = "Desejo Produtivo, Eros & Atratores Rizomáticos (Deleuze)",
                geometryType = "ATRIUM",
                elevationLevel = -0.15f,
                luminosity = 0.78f,
                tensionIndex = 0.64f,
                resonantHz = 480f,
                mapX = 0.24f,
                mapY = 0.70f,
                connectedZonesCsv = "zone_identity,zone_time,zone_power",
                architecturalDescription = "Uma abóbada orgânica em terracota pulsante. Pontes suspensas se projetam espontaneamente em direção aos objetos de atenção, gerando calor cinético."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_power",
                titleKey = "archRoomPower",
                customTitle = "Forja de Conatus",
                conceptDomain = "Poder, Dominação, Raiva & Potência de Agir (Spinoza / Foucault)",
                geometryType = "MONOLITH",
                elevationLevel = -0.35f,
                luminosity = 0.52f,
                tensionIndex = 0.82f,
                resonantHz = 256f,
                mapX = 0.76f,
                mapY = 0.70f,
                connectedZonesCsv = "zone_justice,zone_time,zone_desire,zone_fear",
                architecturalDescription = "Estruturas megalíticas de basalto e bronze onde vetores de força colidem. Aqui a dominação (potestas rígida) pode ser transmutada em potência criativa (potentia)."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_fear",
                titleKey = "archRoomFear",
                customTitle = "Cripta de Contração",
                conceptDomain = "Medo, Angústia & Vigilância Preditiva",
                geometryType = "LABYRINTH",
                elevationLevel = -0.75f,
                luminosity = 0.34f,
                tensionIndex = 0.88f,
                resonantHz = 196f,
                mapX = 0.50f,
                mapY = 0.84f,
                connectedZonesCsv = "zone_identity,zone_time,zone_power",
                architecturalDescription = "Corredores estreitos de acústica reverberante nas fundações da mente. Quando iluminada pela observação consciente, suas paredes se afastam e se tornam translúcidas."
            )
        )
    }
}
