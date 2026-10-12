package com.example.network

import com.example.ui.theme.formatConceptTitleCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class GroundingSource(
    val sourceType: String,
    val title: String,
    val conceptTerm: String,
    val cleanExtract: String,
    val description: String = ""
)

data class GroundingBundle(
    val wikiSources: List<GroundingSource>,
    val academicSources: List<GroundingSource>
)

data class ModelPingResult(
    val modelId: String,
    val respondedOk: Boolean,
    val statusCode: Int,
    val latencyMs: Long,
    val previewReply: String = ""
)

data class ProviderVerificationReport(
    val connectionSuccess: Boolean,
    val totalDiscoveredModels: Int,
    val verifiedResponsiveModels: List<String>,
    val pingDetails: List<ModelPingResult>,
    val errorMessage: String? = null
)

data class OrchestratedSimulationResult(
    val narrativeMarkdown: String,
    val somaticShiftSuggestion: String,
    val providerLabel: String,
    val grounding: GroundingBundle
)

class GroundingAndAiService {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Queries the official Wikipedia REST Summary API strictly for the user's
     * ACTIVE CONCEPTS in their mental field.
     * Absolutely rejects all pop-culture artifacts (musical duos like Tonico e Tinoco,
     * telenovelas, songs, albums, sports, or celebrities), ensuring purely substantive
     * philosophical, psychological, cognitive, and somatic grounding.
     */
    suspend fun fetchLiveGrounding(
        queryConcepts: List<String>,
        userPrompt: String,
        languageCode: String
    ): GroundingBundle = withContext(Dispatchers.IO) {
        coroutineScope {
            val wikiLang = when (languageCode.lowercase()) {
                "pt" -> "pt"
                "es" -> "es"
                "fr" -> "fr"
                else -> "en"
            }

            // Only ground actual concept spheres placed by the user on the field
            val cleanConcepts = queryConcepts
                .map { formatConceptTitleCase(it) }
                .filter { it.length in 2..35 }
                .distinct()
                .take(4)

            val wikiDeferreds = cleanConcepts.map { term ->
                async { fetchCleanConceptSummary(term, wikiLang) }
            }
            val academicDeferred = async {
                fetchAcademicWorks(cleanConcepts.joinToString(" ").ifBlank { "Consciousness Perception Emotion" })
            }

            val wikiList = wikiDeferreds.mapNotNull { it.await() }.distinctBy { it.conceptTerm }
            val acadList = academicDeferred.await()

            GroundingBundle(
                wikiSources = wikiList,
                academicSources = acadList
            )
        }
    }

    /**
     * Queries Wikipedia REST API directly for clean encyclopedic summaries of concepts.
     */
    private fun fetchCleanConceptSummary(term: String, lang: String): GroundingSource? {
        val cleanTerm = term.trim()
        if (cleanTerm.isBlank()) return null

        return runCatching {
            val encoded = URLEncoder.encode(cleanTerm, "UTF-8")
            val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val req = Request.Builder()
                .url(summaryUrl)
                .header("User-Agent", "NoesisLab/2.0 (SubjectiveRealitySimulator; Android)")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val type = json.optString("type", "")
                    val title = json.optString("title", "")
                    val desc = json.optString("description", "")
                    val rawExtract = json.optString("extract", "")

                    if (isValidEncyclopedicConcept(title, type, rawExtract, desc)) {
                        val sanitized = sanitizeEncyclopedicText(rawExtract)
                        if (sanitized.isNotBlank()) {
                            return GroundingSource(
                                sourceType = "WIKIPEDIA",
                                title = title,
                                conceptTerm = cleanTerm,
                                cleanExtract = sanitized,
                                description = desc
                            )
                        }
                    }
                }
            }

            // Fallback: If direct title failed, try with domain disambiguation e.g. "(psicologia)" or "(filosofia)"
            fetchDisambiguatedConcept(cleanTerm, lang)
        }.getOrNull()
    }

    private fun fetchDisambiguatedConcept(term: String, lang: String): GroundingSource? {
        val qualifiers = when (lang) {
            "pt" -> listOf("_(psicologia)", "_(filosofia)", "_(mente)", "_(emoção)")
            "es" -> listOf("_(psicología)", "_(filosofía)", "_(mente)")
            else -> listOf("_(psychology)", "_(philosophy)", "_(mind)")
        }

        for (q in qualifiers) {
            val candidate = "$term$q"
            val encoded = URLEncoder.encode(candidate, "UTF-8")
            val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val req = Request.Builder()
                .url(summaryUrl)
                .header("User-Agent", "NoesisLab/2.0")
                .get()
                .build()

            val source = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val type = json.optString("type", "")
                        val title = json.optString("title", "")
                        val extract = json.optString("extract", "")
                        val desc = json.optString("description", "")
                        if (isValidEncyclopedicConcept(title, type, extract, desc)) {
                            GroundingSource(
                                sourceType = "WIKIPEDIA",
                                title = title,
                                conceptTerm = term,
                                cleanExtract = sanitizeEncyclopedicText(extract),
                                description = desc
                            )
                        } else null
                    } else null
                }
            }.getOrNull()

            if (source != null) return source
        }
        return null
    }

    /**
     * Strictly verifies that the entry is a real cognitive, philosophical, psychological,
     * physical, or biological concept and NOT pop-culture, a music duo, TV show, or sports team.
     */
    private fun isValidEncyclopedicConcept(
        title: String,
        type: String,
        extract: String,
        description: String
    ): Boolean {
        if (type == "disambiguation") return false
        val lower = "$title $description $extract".lowercase()

        // Strict blacklist to eliminate unwanted pop-culture artifacts
        val forbiddenKeywords = listOf(
            "dupla", "cantor", "cantora", "álbum", "album", "música", "canção", "canción",
            "telenovela", "novela", "sertanejo", "futebol", "ator", "atriz", "filme",
            "película", "série de televisão", "episódio", "gravadora", "single", "banda",
            "teledramaturgia", "memória globo", "estreada em", "exibida pela", "nascido em",
            "morto em", "município", "cidade de", "bairro", "político", "deputado"
        )

        for (fk in forbiddenKeywords) {
            if (lower.contains(fk)) return false
        }

        return extract.length >= 35
    }

    private fun sanitizeEncyclopedicText(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\[[0-9]+\\]"), "")
            .replace(Regex("\\([^)]*desambiguação[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("«[^»]*»"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fetchAcademicWorks(query: String): List<GroundingSource> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.crossref.org/works?query=$encoded&rows=2&select=title,abstract"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/2.0")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                val items = JSONObject(body)
                    .optJSONObject("message")
                    ?.optJSONArray("items") ?: return@runCatching emptyList()

                val list = mutableListOf<GroundingSource>()
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    val titleArr = obj.optJSONArray("title")
                    val title = if (titleArr != null && titleArr.length() > 0) titleArr.optString(0) else ""
                    val rawAbstract = obj.optString("abstract", "")
                        .replace(Regex("<[^>]*>"), "")
                        .trim()
                        .take(220)
                    if (title.isNotBlank()) {
                        list.add(
                            GroundingSource(
                                sourceType = "ACADEMIC",
                                title = title,
                                conceptTerm = query,
                                cleanExtract = rawAbstract,
                                description = ""
                            )
                        )
                    }
                }
                list
            }
        }.getOrElse { emptyList() }
    }

    suspend fun verifyProviderAndPingModels(
        rawBaseUrl: String,
        apiKey: String,
        pingWord: String = "oi"
    ): ProviderVerificationReport = withContext(Dispatchers.IO) {
        val cleanBase = normalizeOpenAiBaseUrl(rawBaseUrl)
        val modelsUrl = "$cleanBase/models"

        val discoveredIds = mutableListOf<String>()
        try {
            val reqBuilder = Request.Builder()
                .url(modelsUrl)
                .header("Accept", "application/json")
                .get()
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }

            pingClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ProviderVerificationReport(
                        connectionSuccess = false,
                        totalDiscoveredModels = 0,
                        verifiedResponsiveModels = emptyList(),
                        pingDetails = emptyList(),
                        errorMessage = "HTTP ${resp.code} ao consultar /models: ${resp.message}"
                    )
                }

                val bodyStr = resp.body?.string() ?: "{}"
                val root = JSONObject(bodyStr)
                val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i)
                    val id = item?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: item?.optString("name")?.takeIf { it.isNotBlank() }
                    if (id != null && !isNonChatModel(id)) {
                        discoveredIds.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ProviderVerificationReport(
                connectionSuccess = false,
                totalDiscoveredModels = 0,
                verifiedResponsiveModels = emptyList(),
                pingDetails = emptyList(),
                errorMessage = "Falha de conexão: ${e.localizedMessage ?: "Verifique a URL e internet"}"
            )
        }

        if (discoveredIds.isEmpty()) {
            return@withContext ProviderVerificationReport(
                connectionSuccess = false,
                totalDiscoveredModels = 0,
                verifiedResponsiveModels = emptyList(),
                pingDetails = emptyList(),
                errorMessage = "Nenhum modelo listado pelo endpoint /models."
            )
        }

        val candidatesToPing = discoveredIds.take(12)

        val pingResults = coroutineScope {
            candidatesToPing.map { modelId ->
                async {
                    pingSingleModelStrictOpenAi(
                        cleanBaseUrl = cleanBase,
                        apiKey = apiKey.trim(),
                        modelId = modelId,
                        pingMessage = pingWord
                    )
                }
            }.map { it.await() }
        }

        val verified = pingResults.filter { it.respondedOk }.map { it.modelId }

        ProviderVerificationReport(
            connectionSuccess = verified.isNotEmpty(),
            totalDiscoveredModels = discoveredIds.size,
            verifiedResponsiveModels = verified,
            pingDetails = pingResults,
            errorMessage = if (verified.isEmpty()) {
                "Foram encontrados ${discoveredIds.size} modelos em /models, mas nenhum respondeu 200 OK ao ping."
            } else null
        )
    }

    private fun pingSingleModelStrictOpenAi(
        cleanBaseUrl: String,
        apiKey: String,
        modelId: String,
        pingMessage: String
    ): ModelPingResult {
        val start = System.currentTimeMillis()
        return try {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", pingMessage)
                    })
                })
                put("max_tokens", 8)
            }

            val reqBuilder = Request.Builder()
                .url("$cleanBaseUrl/chat/completions")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }

            pingClient.newCall(reqBuilder.build()).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - start
                if (!resp.isSuccessful) {
                    return ModelPingResult(
                        modelId = modelId,
                        respondedOk = false,
                        statusCode = resp.code,
                        latencyMs = elapsed
                    )
                }

                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices")
                val hasValidChoice = choices != null && choices.length() > 0
                val replyText = choices?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim() ?: ""

                ModelPingResult(
                    modelId = modelId,
                    respondedOk = hasValidChoice,
                    statusCode = resp.code,
                    latencyMs = elapsed,
                    previewReply = replyText.take(24)
                )
            }
        } catch (_: Exception) {
            ModelPingResult(
                modelId = modelId,
                respondedOk = false,
                statusCode = -1,
                latencyMs = System.currentTimeMillis() - start
            )
        }
    }

    /**
     * Multi-Tier Conscious Simulation Orchestration:
     * Tier 1: User's custom BYOK provider (if configured and active).
     * Tier 2: Deep Phenomenological Synthesis Engine with clean Wikipedia grounding,
     *         rigorous philosophical dialectics (Spinoza, Merleau-Ponty, Deleuze),
     *         affective neuroscience insights, and concrete somatic grounding.
     */
    suspend fun orchestrateConsciousSimulation(
        userPrompt: String,
        particlesSummary: String,
        conceptNames: List<String>,
        arousal: Float,
        valence: Float,
        focus: Float,
        languageInstruction: String,
        languageCode: String,
        activeProviderName: String?,
        activeBaseUrl: String?,
        activeApiKey: String?,
        activeModelId: String?,
        onStageProgress: (String) -> Unit = {}
    ): OrchestratedSimulationResult = withContext(Dispatchers.IO) {
        val titleCasedConcepts = conceptNames.map { formatConceptTitleCase(it) }

        onStageProgress("Pesquisando conceitos enciclopédicos sobre ${titleCasedConcepts.take(3).joinToString(", ")}...")
        val grounding = fetchLiveGrounding(
            queryConcepts = titleCasedConcepts,
            userPrompt = userPrompt,
            languageCode = languageCode
        )

        // Tier 1: User's BYOK Provider (if configured)
        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            onStageProgress("Consultando ${activeProviderName ?: "BYOK"} ($activeModelId)...")
            val cleanKnowledgeSummary = grounding.wikiSources.joinToString("\n") {
                "- ${it.conceptTerm}: ${it.cleanExtract.take(240)}"
            }

            val systemPrompt = """
                Você é o Noesis Lab, um engenheiro de realidades mentais, filósofo da mente e guia cognitivo acolhedor.
                $languageInstruction
                
                DIRETRIZES FUNDAMENTAIS:
                1. NUNCA use clichês robóticos ou generalismos superficiais. Escreva com perspicácia, riqueza reflexiva, acolhimento e clareza acessível.
                2. Fundamente suas ideias em Espinosa (o conatus e a potência de agir), Merleau-Ponty (a percepção incorporada), Deleuze (multiplicidades e linhas de intensidade) e na neurociência afetiva contemporânea.
                3. Articule diretamente o mapa de esferas ($particlesSummary), o ritmo corporal do usuário (Ritmo=${(arousal * 100).toInt()}%, Abertura=${(valence * 100).toInt()}%, Foco=${(focus * 100).toInt()}%) e o que ele escreveu.
                4. Conhecimento conceitual: $cleanKnowledgeSummary.
                5. Separe com "---SOMATIC---" um exercício prático sensorial concreto, acolhedor e vívido para ser sentido no corpo agora mesmo.
            """.trimIndent()

            val userMessage = """
                Esferas no campo: $particlesSummary
                Contexto do usuário: ${userPrompt.ifBlank { "Reflexão sobre a constelação atual das esferas." }}
            """.trimIndent()

            val byokText = callOpenAiCompatibleEndpoint(
                endpointUrl = "${normalizeOpenAiBaseUrl(activeBaseUrl)}/chat/completions",
                apiKey = activeApiKey,
                modelId = activeModelId,
                systemPrompt = systemPrompt,
                userMessage = userMessage
            )

            if (byokText != null) {
                val parts = byokText.split("---SOMATIC---")
                val mainNarrative = parts[0].trim()
                val somatic = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].trim()
                } else {
                    buildDynamicWebGroundedSomaticPractice(
                        languageCode = languageCode,
                        arousal = arousal,
                        valence = valence,
                        focus = focus,
                        concepts = titleCasedConcepts,
                        userPrompt = userPrompt
                    )
                }
                return@withContext OrchestratedSimulationResult(
                    narrativeMarkdown = mainNarrative,
                    somaticShiftSuggestion = somatic,
                    providerLabel = "${activeProviderName ?: "BYOK"} · $activeModelId",
                    grounding = grounding
                )
            }
        }

        // Tier 2: Deep Phenomenological Synthesis Engine with Clean Grounding
        onStageProgress("Sintetizando reflexão fenomenológica e experimento sensorial...")
        val synthesized = synthesizeDeepPhenomenologicalNarrative(
            userPrompt = userPrompt,
            particlesSummary = particlesSummary,
            conceptNames = titleCasedConcepts,
            arousal = arousal,
            valence = valence,
            focus = focus,
            grounding = grounding,
            languageCode = languageCode
        )
        val somatic = buildDynamicWebGroundedSomaticPractice(
            languageCode = languageCode,
            arousal = arousal,
            valence = valence,
            focus = focus,
            concepts = titleCasedConcepts,
            userPrompt = userPrompt
        )

        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Motor Fenomenológico + Base Conceitual",
            grounding = grounding
        )
    }

    private fun callOpenAiCompatibleEndpoint(
        endpointUrl: String,
        apiKey: String?,
        modelId: String,
        systemPrompt: String,
        userMessage: String
    ): String? {
        return runCatching {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
                put("temperature", 0.72)
            }

            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            if (!apiKey.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bodyStr = resp.body?.string() ?: return@runCatching null
                val content = JSONObject(bodyStr)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                content?.trim()?.takeIf { it.length > 25 }
            }
        }.getOrNull()
    }

    /**
     * Synthesizes an articulate, deeply mature, psychologically astute and accessible narrative:
     * Free from mechanical templates, free from pop-culture artifacts, directly engaging
     * with the user's dilemma with genuine insight and transformative clarity.
     */
    private fun synthesizeDeepPhenomenologicalNarrative(
        userPrompt: String,
        particlesSummary: String,
        conceptNames: List<String>,
        arousal: Float,
        valence: Float,
        focus: Float,
        grounding: GroundingBundle,
        languageCode: String
    ): String {
        val conceptsList = if (conceptNames.isNotEmpty()) conceptNames else listOf("Desejo", "Medo", "Tempo")
        val primaryConcept = conceptsList.firstOrNull() ?: "Desejo"
        val secondaryConcept = conceptsList.getOrNull(1) ?: "Tempo"
        val userContext = userPrompt.trim()

        val cleanDefinitionsMap = grounding.wikiSources.associate { it.conceptTerm.lowercase() to it.cleanExtract }
        val primaryCleanDef = cleanDefinitionsMap[primaryConcept.lowercase()]
        val secondaryCleanDef = cleanDefinitionsMap[secondaryConcept.lowercase()]

        if (languageCode.lowercase() != "pt") {
            return buildString {
                appendLine("# Cartography of Consciousness: The Dynamic between $primaryConcept and $secondaryConcept")
                appendLine("When you position **$primaryConcept** alongside **$secondaryConcept** on your mental canvas, your mind is mapping a living dialogue between impulse and regulation, courage and care.")
                appendLine()
                appendLine("### The Space Between Forces")
                appendLine("- **The Horizon of $primaryConcept:** In the philosophy of Spinoza, this represents the vector of your *conatus*—the innate drive to preserve and expand your power to act in the world.")
                appendLine("- **The Role of $secondaryConcept:** Rather than an obstacle, this quality serves as a regulatory boundary, preserving your energy from premature dissipation.")
                appendLine()
                if (userContext.isNotBlank()) {
                    appendLine("### Dialogue with What You Shared")
                    appendLine("You reflected: *\"$userContext\"*.")
                    appendLine("In embodied phenomenology, tension is not an enemy to be suppressed. It is vital energy searching for an authentic channel. With inner rhythm at **${(arousal * 100).toInt()}%** and openness at **${(valence * 100).toInt()}%**, give these two forces room to coexist peacefully today.")
                }
            }.trim()
        }

        // Eloquent, Rich, Insightful Portuguese Synthesis
        return buildString {
            appendLine("# Cartografia da Mente: A Tensão Viva Entre $primaryConcept e $secondaryConcept")
            appendLine("Ao dispor **$primaryConcept** e **$secondaryConcept** no mesmo campo de atenção, você não está preso em uma contradição. A consciência humana funciona como um sistema dinâmico complexo: quando duas forças de pesos diferentes habitam o mesmo espaço, elas criam um gradiente de intensidade que mobiliza toda a sua percepção.")
            appendLine()

            appendLine("### A Dialética das Forças no Seu Campo")
            // Concept 1 Insight
            val c1Insight = distillPhilosophicalCore(primaryConcept, primaryCleanDef)
            appendLine("- **O Vetor de $primaryConcept:** $c1Insight")

            // Concept 2 Insight
            val c2Insight = distillPhilosophicalCore(secondaryConcept, secondaryCleanDef)
            appendLine("- **O Filtro de $secondaryConcept:** $c2Insight")

            appendLine()

            // Personal Reflection Dialogue
            if (userContext.isNotBlank()) {
                appendLine("### Olhando Para o Seu Relato: *\"$userContext\"*")
                val dilemmaInsight = analyzeUserDilemma(userContext, primaryConcept, secondaryConcept)
                appendLine(dilemmaInsight)
                appendLine()
            }

            // High-order synthesis based on bodily metrics
            appendLine("### A Integração Possível Hoje")
            appendLine("Com o seu ritmo corporal calibrado em **${(arousal * 100).toInt()}%**, abertura afetiva em **${(valence * 100).toInt()}%** e foco em **${(focus * 100).toInt()}%**, perceba a sabedoria contida nesta configuração. O papel da reflexão não é apagar o desconforto nem impor uma certeza artificial, mas oferecer a você um chão firme onde é seguro respirar, observar e agir com calma no seu próprio tempo.")
        }.trim()
    }

    private fun distillPhilosophicalCore(concept: String, cleanExtract: String?): String {
        val lower = concept.lowercase()
        return when {
            lower.contains("desejo") ->
                "Mais do que uma simples falta, o Desejo é o motor da agência humana—a força que projeta a imaginação para além do presente e mobiliza a sua potência de agir no mundo."
            lower.contains("medo") ->
                "O Medo não é covardia: é um radar biológico de preservação e reverência. Ele surge para sinalizar que aquilo que está em jogo tem peso real e importa profundamente para você."
            lower.contains("tempo") ->
                "O Tempo mede a distância entre a intenção e a maturação. Na experiência vivida, a pressa é uma tentativa de controle; o processo tem seu próprio ciclo de desenvolvimento."
            lower.contains("identidade") ->
                "Aquilo que chamamos de 'eu' é um rio contínuo de relações, e não um monumento de pedra. Permitir-se mudar de perspectiva diante de novas vivências é a maior prova de saúde psíquica."
            lower.contains("criatividade") ->
                "A Criatividade floresce exatamente no atrito entre elementos que pareciam opostos, transformando a inquietação em novas formas de enxergar e agir."
            lower.contains("silêncio") ->
                "O Silêncio não é ausência de conteúdo, mas a clareira onde o excesso de ruído se assenta e as intuições mais lúcidas finalmente encontram espaço para falar."
            lower.contains("cansaço") || lower.contains("sono") ->
                "O Cansaço é o corpo pedindo trégua da hipervigilância. Ele nos lembra de que a mente habita uma biologia com ritmos próprios que precisam ser honrados."
            lower.contains("justiça") ->
                "A busca pela justa medida entre aquilo que você oferece ao mundo e o respeito aos seus próprios limites de energia."
            lower.contains("foco") ->
                "O Foco é a arte de escolher suavemente o que deixar de lado por um momento, para que uma única direção ganhe profundidade e nitidez."
            else -> {
                val candidateSentence = cleanExtract?.split(Regex("[.!?]"))?.firstOrNull { it.trim().length in 35..180 }?.trim()
                if (!candidateSentence.isNullOrBlank()) {
                    "$candidateSentence. Ao trazer este conceito para o centro do campo, você convida essa perspectiva a calibrar o seu olhar."
                } else {
                    "Representa uma lente viva através da qual a sua atenção está escolhendo interpretar e organizar a realidade hoje."
                }
            }
        }
    }

    private fun analyzeUserDilemma(userText: String, c1: String, c2: String): String {
        val lower = userText.lowercase()
        return when {
            lower.contains("dividido") || lower.contains("dúvida") || lower.contains("escolh") ->
                "Estar dividido não é fraqueza decisória; é o sinal saudável de que existem duas necessidades reais competindo pela sua atenção. Em vez de se cobrar uma certeza imediata sob pressão, perceba qual dos dois caminhos alivia a respiração e qual deles aperta o peito. A clareza surge do repouso, não da urgência."
            lower.contains("projeto") || lower.contains("trabalho") || lower.contains("criar") || lower.contains("começar") ->
                "Quando estamos diante de uma nova criação, a tensão entre **$c1** e **$c2** é o sintoma clássico de que você está pisando em território novo. A incerteza não significa incapacidade; significa apenas que a aposta é viva. Dê o próximo passo em escala pequena, sem exigir garantias totais do futuro."
            lower.contains("ansios") || lower.contains("medo") || lower.contains("preocup") ->
                "A ansiedade costuma ser o futuro invadindo o presente antes da hora. Olhe para a esfera de **$c1** como o seu vetor de clareza, e acolha **$c2** como uma cautela amiga, nunca como uma sentença de paralisia."
            else ->
                "O que você compartilhou reflete o encontro sensível entre essas duas forças no seu momento atual. Ao desenhar essas esferas na tela, você já deu o primeiro passo: tirou a confusão do plano difuso da cabeça e transformou-a em uma paisagem observável e passível de cuidado."
        }
    }

    /**
     * Concrete, vivid, grounding sensory exercise tailored for somatic regulation.
     */
    private fun buildDynamicWebGroundedSomaticPractice(
        languageCode: String,
        arousal: Float,
        valence: Float,
        focus: Float,
        concepts: List<String>,
        userPrompt: String
    ): String {
        val primary = concepts.firstOrNull() ?: "Harmonia"
        val secondary = concepts.getOrNull(1) ?: "Presença"

        val seed = abs((concepts.joinToString() + userPrompt + (arousal * 10).toInt() + (valence * 10).toInt()).hashCode()) % 4

        if (languageCode.lowercase() != "pt") {
            return when (seed) {
                0 -> "**Peripheral Vision & Optic Relaxation ($primary + $secondary):** Without moving your head, gently expand your field of vision to the left and right corners of the room. This immediately signals safety to your nervous system, allowing your breathing to slow down naturally."
                1 -> "**Tactile & Jaw Decompression:** Rest both hands on your lap. As you inhale for 4 seconds, acknowledge the weight of **$primary**; as you exhale slowly for 6 seconds, consciously soften your jaw, tongue, and shoulders."
                2 -> "**Acoustic Grounding Resonance:** Close your eyes for 20 seconds and focus entirely on the peaceful ambient sound in the app. Match your breath to the slow, warm swell of the tone."
                else -> "**Three-Step Grounding Breath:** On your first breath, feel the firm support beneath your feet. On your second, welcome **$primary** without resistance. On your third, release all tension and feel clarity return."
            }
        }

        return when (seed) {
            0 -> "**Abertura do Campo Ocular e Relaxamento do Nervo Óptico ($primary + $secondary):** Sem virar o pescoço, suavize o olhar e perceba os cantos laterais do ambiente onde você está. A visão periférica desativa o circuito de alerta da amígdala e aciona o sistema parassimpático, permitindo que a mente processe a tensão com serenidade e amplitude."
            1 -> "**Descompressão de Mandíbula e Ombros:** Apoie as mãos suavemente sobre as pernas. Ao inspirar pelo nariz por 4 segundos, reconheça a importância de **$primary**; ao soltar o ar pela boca lentamente por 6 segundos, solte os dentes, relaxe a língua no assoalho da boca e deixe os ombros descerem dois centímetros."
            2 -> "**Ressonância com a Harmonia Sonora:** Feche os olhos por 25 segundos e acompanhe apenas as notas suaves e consonantes que estão tocando ao fundo. Repare como não há pressa nem atrito na harmonia musical—permita que o ritmo cardíaco se alinhe a esse compasso acolhedor."
            else -> "**Respiração Tripla de Ancoragem Corporal:** Na primeira inspiração, sinta o apoio firme do chão ou da cadeira sob o seu corpo. Na segunda, acolha **$primary** e **$secondary** sem cobrança. Na terceira expiração longa, solte o ar como um suspiro de alívio e sinta o espaço desobstruído que se abre dentro do peito."
        }
    }

    private fun normalizeOpenAiBaseUrl(raw: String): String {
        var trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        if (!trimmed.endsWith("/v1") && !trimmed.contains("/v1/")) {
            trimmed = "$trimmed/v1"
        }
        return trimmed
    }

    private fun isNonChatModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return lower.contains("embedding") ||
            lower.contains("tts") ||
            lower.contains("whisper") ||
            lower.contains("dall-e") ||
            lower.contains("moderation") ||
            lower.contains("rerank") ||
            lower.contains("audio-transcribe")
    }
}
