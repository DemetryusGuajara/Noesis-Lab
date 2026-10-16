package com.example.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import com.example.i18n.AppLocalizer
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.formatConceptTitleCase
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
            bioArousal = prefs[Keys.BIO_AROUSAL] ?: 0.45f,
            bioValence = prefs[Keys.BIO_VALENCE] ?: 0.65f,
            bioFocus = prefs[Keys.BIO_FOCUS] ?: 0.75f
        )
    }

    val particlesFlow: Flow<List<ConceptParticleEntity>> = dao.observeParticles()
    val zonesFlow: Flow<List<ArchitecturalZoneEntity>> = dao.observeZones()
    val logsFlow: Flow<List<SimulationLogEntity>> = dao.observeSimulationLogs()
    val providersFlow: Flow<List<AiProviderEntity>> = dao.observeProviders()
    val journalEntriesFlow: Flow<List<JournalEntryEntity>> = dao.observeJournalEntries()

    fun observeKeysFlow(providerId: Long): Flow<List<AiProviderKeyEntity>> =
        dao.observeProviderKeys(providerId)

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun updateBiofeedback(arousal: Float, valence: Float, focus: Float) {
        context.dataStore.edit {
            it[Keys.BIO_AROUSAL] = arousal.coerceIn(0f, 1f)
            it[Keys.BIO_VALENCE] = valence.coerceIn(0f, 1f)
            it[Keys.BIO_FOCUS] = focus.coerceIn(0f, 1f)
        }
    }

    suspend fun seedInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        ensureHardcodedProviders()
        if (dao.getAllParticlesOnce().isEmpty()) {
            dao.insertParticles(defaultParticles())
        }
        if (dao.getAllZonesOnce().isEmpty()) {
            dao.insertZones(defaultZones())
        }
        if (dao.getAllLogsOnce().isEmpty()) {            dao.insertSimulationLog(
                SimulationLogEntity(
                    userPrompt = "Como as minhas ideias e emoções atuais estão conversando entre si hoje?",
                    activeConceptsCsv = "Desejo, Medo, Tempo, Criatividade",
                    biofeedbackArousal = 0.45f,
                    biofeedbackValence = 0.65f,
                    biofeedbackFocus = 0.78f,
                    wikiSourcesJson = "[]",
                    academicSourcesJson = "[]",
                    narrativeResponse = "### O Desenho do Seu Estado Atual: Desejo, Medo, Tempo, Criatividade\nOlhando para o desenho das esferas na sua tela, você reuniu **Desejo**, **Medo**, **Tempo** e **Criatividade**.\n\n- **A Conversa Entre as Esferas:** Quando colocamos o **Medo** perto do **Desejo**, muitas vezes sentimos uma dúvida entre avançar e cautela. Mas repare que, no seu mapa, a **Criatividade** e o **Tempo** servem como uma ponte luminosa entre eles: você não precisa ter todas as certezas agora para dar o próximo passo.",
                    somaticSuggestion = "**Experimento de Fusão Cromática (Desejo + Criatividade):** Olhe para o degradê de cores que une **Desejo** e **Criatividade** no seu campo gravitacional. Inspire pelo nariz por 4 segundos sentindo a cor mais fria refrescar a mente, e solte o ar pela boca por 6 segundos deixando a cor mais quente relaxar os ombros.",
                    providerUsed = "Motor Interno + Pesquisa Web",
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

    suspend fun addJournalEntry(entry: JournalEntryEntity): Long = dao.insertJournalEntry(entry)
    suspend fun deleteJournalEntry(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteChatMessagesOf(id)
        dao.deleteJournalEntry(id)
    }

    // ---------- Fase 3: threads de chat ----------

    fun observeChatMessages(entryId: Long): Flow<List<ChatMessageEntity>> =
        dao.observeChatMessages(entryId)

    suspend fun addChatMessage(message: ChatMessageEntity): Long = dao.insertChatMessage(message)

    suspend fun saveVerifiedProvider(provider: AiProviderEntity): Long {
        if (provider.isActive) {
            dao.deactivateAllProviders()
        }
        return dao.insertProvider(provider)
    }

    // ---------- Fase 2: slots de chave + provedores hardcoded ----------

    /** Substitui {account_id} no endpoint (Cloudflare) pelo id do slot. */
    fun resolveBaseUrl(baseUrl: String, accountId: String): String {
        val clean = accountId.trim()
        return if (baseUrl.contains("{account_id}")) baseUrl.replace("{account_id}", clean) else baseUrl
    }

    suspend fun getProviderKeys(providerId: Long): List<AiProviderKeyEntity> =
        dao.getProviderKeysOnce(providerId)

    suspend fun addKeySlot(providerId: Long): Long = withContext(Dispatchers.IO) {
        val existing = dao.getProviderKeysOnce(providerId)
        val next = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = dao.insertProviderKey(
            AiProviderKeyEntity(
                providerId = providerId,
                label = "${AppLocalizer.active().uiKeyLabel} ${existing.size + 1}",
                apiKey = "",
                accountId = "",
                isActive = existing.none { it.isActive },
                sortOrder = next
            )
        )
        id
    }

    suspend fun updateKeySlot(key: AiProviderKeyEntity) = dao.updateProviderKey(key)

    suspend fun deleteKeySlot(keyId: Long, providerId: Long) = withContext(Dispatchers.IO) {
        dao.deleteProviderKey(keyId)
        val remaining = dao.getProviderKeysOnce(providerId)
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            val first = remaining.minByOrNull { it.sortOrder }!!
            dao.updateProviderKey(first.copy(isActive = true))
        }
    }

    suspend fun setActiveKeySlot(keyId: Long, providerId: Long) = withContext(Dispatchers.IO) {
        dao.deactivateProviderKeys(providerId)
        dao.getProviderKeysOnce(providerId).firstOrNull { it.id == keyId }?.let {
            dao.updateProviderKey(it.copy(isActive = true))
        }
    }

    /** Provedor ativo + slot de chave ativo + URL resolvida, ou null (motor interno). */
    suspend fun resolveActiveEngine(): Triple<AiProviderEntity, AiProviderKeyEntity, String>? =
        withContext(Dispatchers.IO) {
            val provider = dao.getAllProvidersOnce()
                .firstOrNull { it.isActive && it.selectedModel.isNotBlank() } ?: return@withContext null
            val key = dao.getProviderKeysOnce(provider.id)
                .firstOrNull { it.isActive && it.apiKey.isNotBlank() } ?: return@withContext null
            Triple(provider, key, resolveBaseUrl(provider.baseUrl, key.accountId))
        }

    /** Garante os 7 provedores hardcoded (nome/endpoint imutáveis); nunca duplica. */
    suspend fun ensureHardcodedProviders() = withContext(Dispatchers.IO) {
        val existing = dao.getAllProvidersOnce()
        hardcodedProviders().forEachIndexed { index, def ->
            val already = existing.firstOrNull {
                it.baseUrl.equals(def.baseUrl, ignoreCase = true) || it.name.equals(def.name, ignoreCase = true)
            }
            if (already == null) {
                val id = dao.insertProvider(
                    AiProviderEntity(
                        name = def.name,
                        baseUrl = def.baseUrl,
                        apiKey = "",
                        verifiedModelsCsv = "",
                        selectedModel = "",
                        isActive = false,
                        isCustom = false,
                        sortOrder = index
                    )
                )
                dao.insertProviderKey(
                    AiProviderKeyEntity(
                        providerId = id,
                        label = "${AppLocalizer.active().uiKeyLabel} 1",
                        apiKey = "",
                        accountId = "",
                        isActive = true,
                        sortOrder = 0
                    )
                )
            }
        }
    }

    suspend fun createCustomProvider(name: String, baseUrl: String): Long = withContext(Dispatchers.IO) {
        val count = dao.getAllProvidersOnce().size
        val id = dao.insertProvider(
            AiProviderEntity(
                name = name.trim().ifBlank { "Custom" },
                baseUrl = baseUrl.trim(),
                apiKey = "",
                verifiedModelsCsv = "",
                selectedModel = "",
                isActive = false,
                isCustom = true,
                sortOrder = 100 + count
            )
        )
        dao.insertProviderKey(
            AiProviderKeyEntity(
                providerId = id, label = "${AppLocalizer.active().uiKeyLabel} 1", apiKey = "",
                accountId = "", isActive = true, sortOrder = 0
            )
        )
        id
    }

    suspend fun updateCustomProvider(provider: AiProviderEntity) {
        if (provider.isCustom) dao.updateProvider(provider)
    }

    suspend fun activateProviderModel(providerId: Long, model: String) {
        dao.deactivateAllProviders()
        dao.activateProviderModel(providerId, model)
    }

    suspend fun deactivateAllProviders() {
        dao.deactivateAllProviders()
    }

    suspend fun deleteProvider(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteProviderKeysOf(id)
        dao.deleteProvider(id)
    }

    suspend fun daoProviderById(id: Long): AiProviderEntity? = withContext(Dispatchers.IO) {
        dao.getAllProvidersOnce().firstOrNull { it.id == id }
    }

    suspend fun findProviderByUrl(baseUrl: String): AiProviderEntity? = withContext(Dispatchers.IO) {
        val clean = baseUrl.trim()
        dao.getAllProvidersOnce().firstOrNull { it.baseUrl.equals(clean, ignoreCase = true) }
    }

    suspend fun updateProviderVerification(provider: AiProviderEntity) = withContext(Dispatchers.IO) {
        if (provider.isActive) dao.deactivateAllProviders()
        dao.updateProvider(provider)
    }

    suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = preferencesFlow.first()
            val particles = dao.getAllParticlesOnce()
            val zones = dao.getAllZonesOnce()
            val logs = dao.getAllLogsOnce()
            val providers = dao.getAllProvidersOnce()
            val journalEntries = dao.getAllJournalEntriesOnce()

            val root = JSONObject().apply {
                put("schemaVersion", 3)
                put("exportedAt", System.currentTimeMillis())
                put("app", "Noesis Lab")
                put("preferences", JSONObject().apply {
                    put("themeMode", prefs.themeMode.name)
                    put("language", prefs.language.code)
                    put("bioArousal", prefs.bioArousal.toDouble())
                    put("bioValence", prefs.bioValence.toDouble())
                    put("bioFocus", prefs.bioFocus.toDouble())
                })
                put("particles", JSONArray().apply {
                    particles.forEach { p ->
                        put(JSONObject().apply {
                            put("name", formatConceptTitleCase(p.name))
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
                put("journalEntries", JSONArray().apply {
                    journalEntries.forEach { j ->
                        put(JSONObject().apply {
                            put("timestamp", j.timestamp)
                            put("titleSummary", j.titleSummary)
                            put("particlesSnapshotJson", j.particlesSnapshotJson)
                            put("zonesSnapshotJson", j.zonesSnapshotJson)
                            put("userNotesPrompt", j.userNotesPrompt)
                            put("narrativeText", j.narrativeText)
                            put("somaticExerciseText", j.somaticExerciseText)
                            put("bioArousal", j.bioArousal.toDouble())
                            put("bioValence", j.bioValence.toDouble())
                            put("bioFocus", j.bioFocus.toDouble())
                        })
                    }
                })
                val allChats = dao.getAllChatMessagesOnce()
                put("chatMessages", JSONArray().apply {
                    allChats.forEach { c ->
                        put(JSONObject().apply {
                            put("entryTitle", journalEntries.firstOrNull { it.id == c.entryId }?.titleSummary ?: "")
                            put("entryTimestamp", journalEntries.firstOrNull { it.id == c.entryId }?.timestamp ?: 0L)
                            put("role", c.role)
                            put("text", c.text)
                            put("somaticText", c.somaticText)
                            put("timestamp", c.timestamp)
                        })
                    }
                })
                put("providers", JSONArray().apply {
                    providers.forEach { pr ->
                        val keys = dao.getProviderKeysOnce(pr.id)
                        put(JSONObject().apply {
                            put("name", pr.name)
                            put("baseUrl", pr.baseUrl)
                            put("apiKey", pr.apiKey)
                            put("verifiedModelsCsv", pr.verifiedModelsCsv)
                            put("selectedModel", pr.selectedModel)
                            put("isActive", pr.isActive)
                            put("isCustom", pr.isCustom)
                            put("sortOrder", pr.sortOrder)
                            put("lastVerifiedAt", pr.lastVerifiedAt)
                            put("keys", JSONArray().apply {
                                keys.forEach { k ->
                                    put(JSONObject().apply {
                                        put("label", k.label)
                                        put("apiKey", k.apiKey)
                                        put("accountId", k.accountId)
                                        put("isActive", k.isActive)
                                        put("sortOrder", k.sortOrder)
                                    })
                                }
                            })
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
                            name = formatConceptTitleCase(o.optString("name", "Conceito")),
                            archetypeKey = o.optString("archetypeKey", "CUSTOM"),
                            mass = o.optDouble("mass", 1.2).toFloat().coerceIn(0.4f, 2.5f),
                            charge = o.optDouble("charge", 0.35).toFloat().coerceIn(0f, 1f),
                            frequencyHz = o.optDouble("frequencyHz", 320.0).toFloat(),
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

            root.optJSONArray("journalEntries")?.let { arr ->
                val list = mutableListOf<JournalEntryEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        JournalEntryEntity(
                            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                            titleSummary = o.optString("titleSummary", AppLocalizer.active().uiJournalEntryFallbackTitle),
                            particlesSnapshotJson = o.optString("particlesSnapshotJson", "[]"),
                            zonesSnapshotJson = o.optString("zonesSnapshotJson", "[]"),
                            userNotesPrompt = o.optString("userNotesPrompt", ""),
                            narrativeText = o.optString("narrativeText", ""),
                            somaticExerciseText = o.optString("somaticExerciseText", ""),
                            bioArousal = o.optDouble("bioArousal", 0.45).toFloat(),
                            bioValence = o.optDouble("bioValence", 0.65).toFloat(),
                            bioFocus = o.optDouble("bioFocus", 0.75).toFloat()
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.clearAllJournalEntries()
                    dao.clearAllChatMessages()
                    val idMap = mutableMapOf<Pair<String, Long>, Long>()
                    list.forEach { entry ->
                        val newId = dao.insertJournalEntry(entry)
                        idMap[Pair(entry.titleSummary, entry.timestamp)] = newId
                    }
                    root.optJSONArray("chatMessages")?.let { chatArr ->
                        for (i in 0 until chatArr.length()) {
                            val c = chatArr.getJSONObject(i)
                            val targetId = idMap[Pair(c.optString("entryTitle", ""), c.optLong("entryTimestamp", 0L))]
                            if (targetId != null) {
                                dao.insertChatMessage(
                                    ChatMessageEntity(
                                        entryId = targetId,
                                        role = c.optString("role", "user"),
                                        text = c.optString("text", ""),
                                        somaticText = c.optString("somaticText", ""),
                                        timestamp = c.optLong("timestamp", System.currentTimeMillis())
                                    )
                                )
                            }
                        }
                    }
                }
            }

            root.optJSONArray("providers")?.let { arr ->
                dao.clearAllProviders()
                dao.clearAllProviderKeys()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val newId = dao.insertProvider(
                        AiProviderEntity(
                            name = o.optString("name", "Custom"),
                            baseUrl = o.optString("baseUrl", ""),
                            apiKey = o.optString("apiKey", ""),
                            verifiedModelsCsv = o.optString("verifiedModelsCsv", ""),
                            selectedModel = o.optString("selectedModel", ""),
                            isActive = o.optBoolean("isActive", false),
                            isCustom = o.optBoolean("isCustom", true),
                            sortOrder = o.optInt("sortOrder", 100 + i),
                            lastVerifiedAt = o.optLong("lastVerifiedAt", 0L)
                        )
                    )
                    val keysArr = o.optJSONArray("keys")
                    if (keysArr != null && keysArr.length() > 0) {
                        for (j in 0 until keysArr.length()) {
                            val k = keysArr.getJSONObject(j)
                            dao.insertProviderKey(
                                AiProviderKeyEntity(
                                    providerId = newId,
                                    label = k.optString("label", "Chave ${j + 1}"),
                                    apiKey = k.optString("apiKey", ""),
                                    accountId = k.optString("accountId", ""),
                                    isActive = k.optBoolean("isActive", j == 0),
                                    sortOrder = k.optInt("sortOrder", j)
                                )
                            )
                        }
                    } else if (o.optString("apiKey", "").isNotBlank()) {
                        dao.insertProviderKey(
                            AiProviderKeyEntity(
                                providerId = newId, label = "${AppLocalizer.active().uiKeyLabel} 1",
                                apiKey = o.optString("apiKey", ""),
                                accountId = "", isActive = true, sortOrder = 0
                            )
                        )
                    }
                }
                ensureHardcodedProviders()
            }

            true
        }.getOrDefault(false)
    }

    companion object {
        data class HardcodedProviderDef(val name: String, val baseUrl: String)

        /** Catálogo imutável Fase 2 (nome/endpoint travados; chaves por slot). */
        fun hardcodedProviders(): List<HardcodedProviderDef> = listOf(
            HardcodedProviderDef("Google AI Studio", "https://generativelanguage.googleapis.com/v1beta/openai"),
            HardcodedProviderDef("OpenRouter", "https://openrouter.ai/api/v1"),
            HardcodedProviderDef("Mistral AI Studio", "https://api.mistral.ai/v1"),
            HardcodedProviderDef("Cerebras", "https://api.cerebras.ai/v1"),
            HardcodedProviderDef("Groq", "https://api.groq.com/openai/v1"),
            HardcodedProviderDef("Nvidia NIM", "https://integrate.api.nvidia.com/v1"),
            HardcodedProviderDef("Cloudflare Workers AI", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1")
        )

        fun defaultParticles(): List<ConceptParticleEntity> = listOf(
            ConceptParticleEntity(
                name = "Desejo",
                archetypeKey = "DESIRE",
                mass = 2.05f,    // Warm Red-Orange Hue
                charge = 0.42f,  // Medium-light saturation
                frequencyHz = 285f,
                spin = 1.2f,
                posX = 0.28f,
                posY = 0.35f,
                colorHex = 0xFFD97757,
                philosophicalNote = AppLocalizer.active().archetypeNoteDesire
            ),
            ConceptParticleEntity(
                name = "Medo",
                archetypeKey = "FEAR",
                mass = 1.15f,    // Teal-Blue Hue
                charge = 0.78f,  // Higher saturation & heavier weight
                frequencyHz = 210f,
                spin = -1.2f,
                posX = 0.72f,
                posY = 0.33f,
                colorHex = 0xFF7A6F9B,
                philosophicalNote = AppLocalizer.active().archetypeNoteFear
            ),
            ConceptParticleEntity(
                name = "Tempo",
                archetypeKey = "TIME",
                mass = 1.65f,    // Warm Gold-Yellow Hue
                charge = 0.35f,  // Light-balanced saturation
                frequencyHz = 340f,
                spin = 0.6f,
                posX = 0.50f,
                posY = 0.64f,
                colorHex = 0xFFD4AF59,
                philosophicalNote = AppLocalizer.active().archetypeNoteTime
            ),
            ConceptParticleEntity(
                name = "Criatividade",
                archetypeKey = "CUSTOM",
                mass = 0.85f,    // Cool Sky-Cyan Hue
                charge = 0.22f,  // Light airy saturation
                frequencyHz = 432f,
                spin = 0.9f,
                posX = 0.26f,
                posY = 0.70f,
                colorHex = 0xFF5A8F99,
                philosophicalNote = AppLocalizer.active().archetypeNoteCreativity
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
                luminosity = 0.85f,
                tensionIndex = 0.20f,
                resonantHz = 528f,
                mapX = 0.50f,
                mapY = 0.16f,
                connectedZonesCsv = "zone_time,zone_identity,zone_justice",
                architecturalDescription = "Um terraço aberto e silencioso na sua mente. Representa sua reserva de calma, pausa e respiro diante dos estímulos do dia."
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
                architecturalDescription = "A sala onde ficam a sua história, seus gostos e a forma como você se percebe hoje."
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
                architecturalDescription = "O espaço onde você pesa decisões e busca harmonia entre o que entrega ao mundo e o que preserva para si."
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
                architecturalDescription = "O corredor central que liga lembranças antigas aos planos futuros, medindo o ritmo e a urgência do seu momento."
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
                architecturalDescription = "Um ambiente quente e cheio de vida onde nascem seus projetos, sonhos e aquilo que desperta seu interesse."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_power",
                titleKey = "archRoomPower",
                customTitle = "Oficina da Ação",
                conceptDomain = "Força Pessoal, Coragem & Realização",
                geometryType = "MONOLITH",
                elevationLevel = -0.35f,
                luminosity = 0.58f,
                tensionIndex = 0.65f,
                resonantHz = 256f,
                mapX = 0.78f,
                mapY = 0.70f,
                connectedZonesCsv = "zone_justice,zone_time,zone_desire,zone_fear",
                architecturalDescription = "Onde a sua energia mental se transforma em decisão, foco e atitude prática."
            ),
            ArchitecturalZoneEntity(
                zoneId = "zone_fear",
                titleKey = "archRoomFear",
                customTitle = "Refúgio da Cautela",
                conceptDomain = "Medos, Dúvidas & Autoproteção",
                geometryType = "LABYRINTH",
                elevationLevel = -0.75f,
                luminosity = 0.48f,
                tensionIndex = 0.70f,
                resonantHz = 196f,
                mapX = 0.50f,
                mapY = 0.85f,
                connectedZonesCsv = "zone_identity,zone_time,zone_power",
                architecturalDescription = "O espaço de vigilância onde ficam armazenados os alertas, receios e mecanismos de proteção da mente."
            )
        )
    }
}
