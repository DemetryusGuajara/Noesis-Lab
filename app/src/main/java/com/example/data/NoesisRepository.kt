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
    val binauralEnabled: Boolean = false,
    val bioArousal: Float = 0.45f,
    val bioValence: Float = 0.65f,
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
        val BINAURAL_ENABLED = booleanPreferencesKey("binaural_enabled")
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
            binauralEnabled = prefs[Keys.BINAURAL_ENABLED] ?: false,
            bioArousal = prefs[Keys.BIO_AROUSAL] ?: 0.45f,
            bioValence = prefs[Keys.BIO_VALENCE] ?: 0.65f,
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

    suspend fun setBinauralEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BINAURAL_ENABLED] = enabled }
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
                    userPrompt = "Como as minhas ideias e emoções atuais estão conversando entre si hoje?",
                    activeConceptsCsv = "Desejo, Medo, Tempo, Criatividade",
                    biofeedbackArousal = 0.45f,
                    biofeedbackValence = 0.65f,
                    biofeedbackFocus = 0.78f,
                    wikiSourcesJson = "[]",
                    academicSourcesJson = "[]",
                    narrativeResponse = "Olhando para o desenho das esferas na sua tela, você reuniu **Desejo**, **Medo**, **Tempo** e **Criatividade**.\n\nQuando colocamos o **Medo** perto do **Desejo**, muitas vezes sentimos uma trava — como se quiséssemos avançar e frear ao mesmo tempo. Mas repare que, no seu mapa, a **Criatividade** e o **Tempo** servem como uma ponte entre eles: você não precisa ter todas as certezas agora para dar o próximo passo.",
                    somaticSuggestion = "Respire fundo pelo nariz e solte o ar devagar pela boca. Olhe para as cores suaves do aplicativo e imagine que qualquer pressa ou cobrança vai diminuindo de tamanho, abrindo espaço para a sua curiosidade.",
                    providerUsed = "Motor Criativo Integrado",
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

    suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = preferencesFlow.first()
            val particles = dao.getAllParticlesOnce()
            val zones = dao.getAllZonesOnce()
            val logs = dao.getAllLogsOnce()
            val providers = dao.getAllProvidersOnce()

            val root = JSONObject().apply {
                put("schemaVersion", 2)
                put("exportedAt", System.currentTimeMillis())
                put("app", "Noesis Lab")
                put("preferences", JSONObject().apply {
                    put("themeMode", prefs.themeMode.name)
                    put("language", prefs.language.code)
                    put("soundEnabled", prefs.soundEnabled)
                    put("binauralEnabled", prefs.binauralEnabled)
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
                setBinauralEnabled(prefObj.optBoolean("binauralEnabled", false))
                updateBiofeedback(
                    arousal = prefObj.optDouble("bioArousal", 0.45).toFloat(),
                    valence = prefObj.optDouble("bioValence", 0.65).toFloat(),
                    focus = prefObj.optDouble("bioFocus", 0.75).toFloat()
                )
            }

            root.optJSONArray("particles")?.let { arr ->
                val list = mutableListOf<ConceptParticleEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ConceptParticleEntity(
                            name = o.optString("name", "Conceito"),
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
                            providerUsed = o.optString("providerUsed", "Noesis"),
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
                mass = 1.60f,
                charge = 0.85f,
                frequencyHz = 432f,
                spin = 1.2f,
                posX = 0.28f,
                posY = 0.35f,
                colorHex = 0xFFD97757,
                philosophicalNote = "Aquilo que te atrai, inspira e coloca sua energia em movimento hoje."
            ),
            ConceptParticleEntity(
                name = "Medo",
                archetypeKey = "FEAR",
                mass = 1.35f,
                charge = -0.65f,
                frequencyHz = 310f,
                spin = -1.2f,
                posX = 0.72f,
                posY = 0.33f,
                colorHex = 0xFF7A6F9B,
                philosophicalNote = "Um sinal de cautela ou proteção tentando evitar riscos antes de agir."
            ),
            ConceptParticleEntity(
                name = "Tempo",
                archetypeKey = "TIME",
                mass = 1.85f,
                charge = 0.20f,
                frequencyHz = 528f,
                spin = 0.6f,
                posX = 0.50f,
                posY = 0.64f,
                colorHex = 0xFFD4AF59,
                philosophicalNote = "O ritmo entre as memórias do que passou e a expectativa do que vem pela frente."
            ),
            ConceptParticleEntity(
                name = "Criatividade",
                archetypeKey = "CUSTOM",
                mass = 1.50f,
                charge = 0.75f,
                frequencyHz = 639f,
                spin = 0.9f,
                posX = 0.26f,
                posY = 0.70f,
                colorHex = 0xFF5A8F99,
                philosophicalNote = "A capacidade de misturar referências, estética, cores e ideias novas."
            )
        )

        fun defaultZones(): List<ArchitecturalZoneEntity> = listOf(
            ArchitecturalZoneEntity(
                zoneId = "zone_emptiness",
                titleKey = "archRoomEmptiness",
                customTitle = "Jardim do Silêncio",
                conceptDomain = "Pausa, Desapego & Mente Clara",
                geometryType = "VOID_GARDEN",
                elevationLevel = 0.90f,
                luminosity = 0.90f,
                tensionIndex = 0.15f,
                resonantHz = 528f,
                mapX = 0.50f,
                mapY = 0.16f,
                connectedZonesCsv = "zone_time,zone_identity,zone_justice",
                architecturalDescription = "Um terraço aberto e silencioso na sua mente. É o lugar para onde você vai quando precisa soltar o excesso de pensamentos e respirar sem cobrança."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_identity",
                titleKey = "archRoomIdentity",
                customTitle = "Espelho de Quem Sou",
                conceptDomain = "Autoimagem, Valores & Essência",
                geometryType = "TOWER",
                elevationLevel = 0.55f,
                luminosity = 0.75f,
                tensionIndex = 0.40f,
                resonantHz = 396f,
                mapX = 0.20f,
                mapY = 0.36f,
                connectedZonesCsv = "zone_emptiness,zone_desire,zone_fear",
                architecturalDescription = "A sala onde ficam a sua história, os seus gostos e a forma como você se vê. Aumentar a clareza aqui ajuda a ser mais gentil consigo mesmo."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_justice",
                titleKey = "archRoomJustice",
                customTitle = "Praça do Equilíbrio",
                conceptDomain = "Harmonia, Relações & Escolhas Justas",
                geometryType = "LATTICE",
                elevationLevel = 0.50f,
                luminosity = 0.80f,
                tensionIndex = 0.30f,
                resonantHz = 639f,
                mapX = 0.80f,
                mapY = 0.36f,
                connectedZonesCsv = "zone_emptiness,zone_power,zone_time",
                architecturalDescription = "O espaço onde você pesa decisões e busca equilíbrio entre o que entrega aos outros e o que guarda para si."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_time",
                titleKey = "archRoomTime",
                customTitle = "Varanda do Tempo",
                conceptDomain = "Memórias, Planos & Momento Presente",
                geometryType = "SPIRAL",
                elevationLevel = 0.15f,
                luminosity = 0.68f,
                tensionIndex = 0.45f,
                resonantHz = 432f,
                mapX = 0.50f,
                mapY = 0.50f,
                connectedZonesCsv = "zone_emptiness,zone_desire,zone_fear,zone_justice,zone_power",
                architecturalDescription = "O corredor central que liga lembranças antigas aos planos futuros. Diminuir a pressão aqui reduz a pressa e a ansiedade pelo amanhã."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_desire",
                titleKey = "archRoomDesire",
                customTitle = "Sala dos Desejos",
                conceptDomain = "Vontades, Paixões & Curiosidade",
                geometryType = "ATRIUM",
                elevationLevel = -0.15f,
                luminosity = 0.78f,
                tensionIndex = 0.55f,
                resonantHz = 480f,
                mapX = 0.22f,
                mapY = 0.70f,
                connectedZonesCsv = "zone_identity,zone_time,zone_power",
                architecturalDescription = "Um ambiente quente e cheio de vida onde nascem seus projetos, sonhos e tudo o que faz seus olhos brilharem."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_power",
                titleKey = "archRoomPower",
                customTitle = "Oficina da Ação",
                conceptDomain = "Força Pessoal, Coragem & Limites",
                geometryType = "MONOLITH",
                elevationLevel = -0.35f,
                luminosity = 0.58f,
                tensionIndex = 0.72f,
                resonantHz = 256f,
                mapX = 0.78f,
                mapY = 0.70f,
                connectedZonesCsv = "zone_justice,zone_time,zone_desire,zone_fear",
                architecturalDescription = "Onde a sua energia vira atitude prática. Ajustar este espaço ajuda a transformar irritação ou controle excessivo em força construtiva."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_fear",
                titleKey = "archRoomFear",
                customTitle = "Refúgio da Cautela",
                conceptDomain = "Medos, Dúvidas & Autoproteção",
                geometryType = "LABYRINTH",
                elevationLevel = -0.75f,
                luminosity = 0.42f,
                tensionIndex = 0.80f,
                resonantHz = 196f,
                mapX = 0.50f,
                mapY = 0.85f,
                connectedZonesCsv = "zone_identity,zone_time,zone_power",
                architecturalDescription = "A sala subterrânea onde ficam guardadas as inseguranças e alertas. Quando você traz luz e reduz a pressão aqui, o medo deixa de assustar e vira apenas prudência."
            )
        )
    }
}
