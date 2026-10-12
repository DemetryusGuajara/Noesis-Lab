package com.example.network

import com.example.ai.LocalLlmEngine
import com.example.ui.theme.formatConceptTitleCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlin.math.hypot

data class SphereSpatialDescriptor(
    val name: String,
    val importanceMass: Float, // 0.4f .. 2.5f
    val weightHeaviness: Float, // 0.0f .. 1.0f
    val posX: Float,
    val posY: Float
)

data class SpatialTopologyAnalysis(
    val dominantSphere: SphereSpatialDescriptor?,
    val closestAffinityPair: Pair<SphereSpatialDescriptor, SphereSpatialDescriptor>?,
    val closestDistance: Float,
    val furthestPolarityPair: Pair<SphereSpatialDescriptor, SphereSpatialDescriptor>?,
    val furthestDistance: Float,
    val heavySpheres: List<SphereSpatialDescriptor>,
    val lightSpheres: List<SphereSpatialDescriptor>
)

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

class GroundingAndAiService(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(14, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    // Local LLM Engine (Qwen 2.5 1.5B via MediaPipe) - lazy init
    private var localLlmEngine: LocalLlmEngine? = null
    private fun getLocalLlm(): LocalLlmEngine {
        if (localLlmEngine == null) {
            localLlmEngine = LocalLlmEngine(context)
        }
        return localLlmEngine!!
    }

    /**
     * Computes the exact spatial and relational geometry of the user's spheres on the canvas:
     * - Affinity Pair (closest spheres = fusion, alliance, mutual reinforcement)
     * - Polarity Pair (furthest spheres = tension, avoidance, protective boundary)
     * - Dominant Anchor (highest importance & centrality)
     */
    fun analyzeSpatialTopology(spheres: List<SphereSpatialDescriptor>): SpatialTopologyAnalysis {
        if (spheres.isEmpty()) {
            return SpatialTopologyAnalysis(null, null, 0f, null, 0f, emptyList(), emptyList())
        }

        val dominant = spheres.maxByOrNull { s ->
            val centerBonus = (1.2f - hypot(s.posX, s.posY)).coerceAtLeast(0.2f)
            s.importanceMass * 0.75f + centerBonus * 0.25f
        }

        var minD = Float.MAX_VALUE
        var maxD = -1f
        var closestPair: Pair<SphereSpatialDescriptor, SphereSpatialDescriptor>? = null
        var furthestPair: Pair<SphereSpatialDescriptor, SphereSpatialDescriptor>? = null

        if (spheres.size >= 2) {
            for (i in spheres.indices) {
                for (j in i + 1 until spheres.size) {
                    val s1 = spheres[i]
                    val s2 = spheres[j]
                    val d = hypot(s2.posX - s1.posX, s2.posY - s1.posY)
                    if (d < minD) {
                        minD = d
                        closestPair = s1 to s2
                    }
                    if (d > maxD) {
                        maxD = d
                        furthestPair = s1 to s2
                    }
                }
            }
        }

        val heavy = spheres.filter { it.weightHeaviness >= 0.52f }
        val light = spheres.filter { it.weightHeaviness < 0.52f }

        return SpatialTopologyAnalysis(
            dominantSphere = dominant,
            closestAffinityPair = closestPair,
            closestDistance = if (minD == Float.MAX_VALUE) 0f else minD,
            furthestPolarityPair = furthestPair,
            furthestDistance = if (maxD < 0f) 0f else maxD,
            heavySpheres = heavy,
            lightSpheres = light
        )
    }

    /**
     * Deep Multi-Source Conceptual & Academic Research:
     * Fetches full multi-paragraph encyclopedic extracts via MediaWiki Extracts API + REST Summary API,
     * alongside Crossref academic literature on Philosophy of Mind and Affective Neuroscience.
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

            val cleanConcepts = queryConcepts
                .map { formatConceptTitleCase(it) }
                .filter { it.length in 2..35 }
                .distinct()
                .take(5)

            val wikiDeferreds = cleanConcepts.map { term ->
                async { fetchDeepConceptExtract(term, wikiLang) }
            }
            val academicDeferred = async {
                fetchAcademicWorks(
                    cleanConcepts.joinToString(" ").ifBlank { "Phenomenology Affective Neuroscience Consciousness" }
                )
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
     * Queries both the Wikipedia Action API (full multi-paragraph intro extract) and
     * the REST Summary API, with domain disambiguation fallbacks (`(filosofia)`, `(psicologia)`).
     */
    private fun fetchDeepConceptExtract(term: String, lang: String): GroundingSource? {
        val cleanTerm = term.trim()
        if (cleanTerm.isBlank()) return null

        // 1. Try domain-disambiguated philosophical/psychological articles first or exact title
        val candidates = when (lang) {
            "pt" -> listOf(cleanTerm, "${cleanTerm}_(filosofia)", "${cleanTerm}_(psicologia)", "${cleanTerm}_(emoção)")
            "es" -> listOf(cleanTerm, "${cleanTerm}_(filosofía)", "${cleanTerm}_(psicología)")
            "fr" -> listOf(cleanTerm, "${cleanTerm}_(philosophie)", "${cleanTerm}_(psychologie)")
            else -> listOf(cleanTerm, "${cleanTerm}_(philosophy)", "${cleanTerm}_(psychology)")
        }

        for (candidate in candidates) {
            val result = runCatching {
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                // First check REST summary for metadata & type verification
                val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
                val req = Request.Builder()
                    .url(summaryUrl)
                    .header("User-Agent", "NoesisLab/2.4 (PhenomenologicalSimulator; Android)")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val type = json.optString("type", "")
                    val title = json.optString("title", "")
                    val desc = json.optString("description", "")
                    val summaryExtract = json.optString("extract", "")

                    if (!isValidEncyclopedicConcept(title, type, summaryExtract, desc)) {
                        return@use null
                    }

                    // Now fetch the deeper multi-paragraph intro extract from Action API
                    val deepExtract = fetchFullIntroParagraphs(title, lang) ?: summaryExtract
                    val sanitized = sanitizeEncyclopedicText(deepExtract).take(850)

                    if (sanitized.length >= 40) {
                        GroundingSource(
                            sourceType = "WIKIPEDIA",
                            title = title,
                            conceptTerm = cleanTerm,
                            cleanExtract = sanitized,
                            description = desc
                        )
                    } else null
                }
            }.getOrNull()

            if (result != null) return result
        }
        return null
    }

    private fun fetchFullIntroParagraphs(exactTitle: String, lang: String): String? {
        return runCatching {
            val encoded = URLEncoder.encode(exactTitle, "UTF-8")
            val apiUrl = "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=$encoded&format=json"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "NoesisLab/2.4 (Android)")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = JSONObject(resp.body?.string() ?: return@use null)
                val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return@use null
                val keys = pages.keys()
                if (keys.hasNext()) {
                    val pageObj = pages.optJSONObject(keys.next())
                    pageObj?.optString("extract")?.takeIf { it.length > 60 }
                } else null
            }
        }.getOrNull()
    }

    private fun isValidEncyclopedicConcept(
        title: String,
        type: String,
        extract: String,
        description: String
    ): Boolean {
        if (type == "disambiguation") return false
        val lower = "$title $description $extract".lowercase()

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
            val url = "https://api.crossref.org/works?query=$encoded&rows=3&select=title,abstract"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/2.4")
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
                        .take(280)
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
     * Master Phenomenological System Prompt:
     * Articulates Spinoza, Merleau-Ponty, Deleuze & Guattari, Francisco Varela, and Affective Neuroscience,
     * reading the spatial topology (proximity = affinity; distance = polarity) while keeping language
     * deeply insightful, clear, warm, and accessible.
     */
    fun buildMasterPhenomenologicalSystemPrompt(
        languageInstruction: String,
        particlesSummary: String,
        topologyDescription: String,
        cleanKnowledgeSummary: String,
        arousal: Float,
        valence: Float,
        focus: Float
    ): String = """
        Você é o Noesis Lab, um filósofo da mente, fenomenólogo e guia perceptivo.
        $languageInstruction

        SUA MISSÃO E TOM DE VOZ:
        - Produza uma leitura PROFUNDA, RICA EM INSIGHTS e REVELADORA, mas com linguagem LÚCIDA, CALOROSA e ACESSÍVEL (nunca use jargão acadêmico hermético ou frases rasas de autoajuda).
        - Esta sessão é ÚNICA e INDEPENDENTE. Analise exclusivamente o estado presente desenhado pelo usuário.

        FUNDAMENTAÇÃO TEÓRICA QUE GUIA SEU OLHAR:
        1. Baruch de Espinosa (Ética dos Afetos): Veja cada emoção ou conceito não como defeito moral, mas como uma variação da *potência de agir* (conatus). Mostre como transformar paixões passivas (que paralisam) em afetos ativos (compreensão que liberta).
        2. Maurice Merleau-Ponty (Fenomenologia da Percepção): A mente não está separada do corpo; o que o usuário posicionou na tela reflete como o corpo dele está habitando o espaço e o tempo hoje.
        3. Gilles Deleuze & Félix Guattari (Rizoma e Intensidades): Aproxime conceitos aparentemente distantes (ex: um sentimento e uma ideia abstrata) para revelar *linhas de fuga* criativas onde o usuário via apenas bloqueio.
        4. Francisco Varela & Neurociência Afetiva Preditiva: Explique como o cérebro antecipa cenários e como a posição espacial das esferas revela acordos internos de proteção ou expansão.

        LEITURA DA GEOMETRIA ESPACIAL DAS ESFERAS:
        - Esferas do campo: $particlesSummary
        - Topologia espacial calculada: $topologyDescription
        - Estado corporal (Biofeedback): Ritmo Interno=${(arousal * 100).toInt()}%, Abertura Afetiva=${(valence * 100).toInt()}%, Profundidade de Foco=${(focus * 100).toInt()}%.
        - Base conceitual pesquisada:
        $cleanKnowledgeSummary

        ESTRUTURA OBRIGATÓRIA DA RESPOSTA (em Markdown):
        1. Comece com um título `#` poético e revelador que sintetize a constelação atual.
        2. Na seção `### 1. A Geometria Invisível do Seu Momento`, interprete a proximidade (afinidade/aliança) e a distância (fronteira/tensão) entre as esferas que o usuário posicionou.
        3. Na seção `### 2. O Insight Central: Além do Dilema`, dialogue profundamente com o que o usuário escreveu (ou com a combinação das esferas), trazendo uma virada de chave filosófica e psicológica que desmonte culpas ou falsas dicotomias.
        4. Na seção `### 3. O Que Cada Esfera Revela Hoje`, dedique um tópico `- **Nome da Esfera:**` para cada esfera presente no campo, articulando sua importância (cor), sua sensação (leve ou pesada) e seu sentido profundo.
        5. Ao final, insira o separador exato `---SOMATIC---` e escreva um exercício prático de percepção corporal em 3 passos concretos (visão periférica, respiração vagal e soltura muscular) conectado às esferas do usuário.
    """.trimIndent()

    /**
     * Multi-Tier Conscious Simulation Orchestration:
     * - Stage 1: Spatial Topology & Relational Geometry Analysis.
     * - Stage 2: Deep Multi-Paragraph Encyclopedic & Academic Grounding.
     * - Stage 3: BYOK Provider (if active) OR Deep Multi-Sphere Dialectical Synthesis Engine.
     */
    suspend fun orchestrateConsciousSimulation(
        userPrompt: String,
        particlesSummary: String,
        conceptNames: List<String>,
        spatialSpheres: List<SphereSpatialDescriptor> = emptyList(),
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
        val topology = analyzeSpatialTopology(spatialSpheres)

        onStageProgress(
            when (languageCode.lowercase()) {
                "en" -> "1/3 · Mapping spatial affinities and distances between ${titleCasedConcepts.take(3).joinToString(", ")}..."
                "es" -> "1/3 · Mapeando afinidades espaciales y distancias entre ${titleCasedConcepts.take(3).joinToString(", ")}..."
                "fr" -> "1/3 · Cartographie des affinités spatiales entre ${titleCasedConcepts.take(3).joinToString(", ")}..."
                else -> "1/3 · Mapeando geometria espacial, afinidades e distâncias entre ${titleCasedConcepts.take(3).joinToString(", ")}..."
            }
        )
        delay(450)

        onStageProgress(
            when (languageCode.lowercase()) {
                "en" -> "2/3 · Deepening encyclopedic and philosophical research on active concepts..."
                "es" -> "2/3 · Profundizando investigación enciclopédica y filosófica sobre los conceptos..."
                "fr" -> "2/3 · Approfondissement de la recherche encyclopédique et philosophique..."
                else -> "2/3 · Aprofundando pesquisa enciclopédica e filosófica sobre cada conceito..."
            }
        )
        val grounding = fetchLiveGrounding(
            queryConcepts = titleCasedConcepts,
            userPrompt = userPrompt,
            languageCode = languageCode
        )

        val topologySummary = buildTopologyDescriptionString(topology)
        val cleanKnowledgeSummary = grounding.wikiSources.joinToString("\n") {
            "- ${it.conceptTerm}: ${it.cleanExtract.take(380)}"
        }

        val masterSystemPrompt = buildMasterPhenomenologicalSystemPrompt(
            languageInstruction = languageInstruction,
            particlesSummary = particlesSummary,
            topologyDescription = topologySummary,
            cleanKnowledgeSummary = cleanKnowledgeSummary,
            arousal = arousal,
            valence = valence,
            focus = focus
        )

        val userMessage = """
            Esferas ativas no mapa: $particlesSummary
            Relações espaciais: $topologySummary
            Relato / Contexto do usuário nesta sessão: ${userPrompt.ifBlank { "Leitura direta da constelação atual das esferas." }}
        """.trimIndent()

        // Tier 1: User's BYOK Provider (if configured)
        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            onStageProgress("3/3 · Consultando ${activeProviderName ?: "BYOK"} ($activeModelId)...")
            val byokText = callOpenAiCompatibleEndpoint(
                endpointUrl = "${normalizeOpenAiBaseUrl(activeBaseUrl)}/chat/completions",
                apiKey = activeApiKey,
                modelId = activeModelId,
                systemPrompt = masterSystemPrompt,
                userMessage = userMessage
            )

            if (byokText != null) {
                val parts = byokText.split("---SOMATIC---")
                val mainNarrative = parts[0].trim()
                val somatic = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].trim()
                } else {
                    buildDeepSomaticPractice(
                        languageCode = languageCode,
                        arousal = arousal,
                        valence = valence,
                        focus = focus,
                        concepts = titleCasedConcepts,
                        topology = topology,
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

        // Tier 2: Local LLM (Qwen 2.5 1.5B via MediaPipe) - runs 100% offline
        val localLlm = getLocalLlm()
        onStageProgress(
            when (languageCode.lowercase()) {
                "en" -> "3/3 · Generating deep reflection via local neural engine..."
                "es" -> "3/3 · Generando reflexión profunda con motor neural local..."
                "fr" -> "3/3 · Génération de réflexion profonde via moteur neural local..."
                else -> "3/3 · Gerando reflexão profunda via motor neural local..."
            }
        )

        val initResult = localLlm.initialize()
        if (initResult.isSuccess) {
            val localText = localLlm.generate(
                systemPrompt = masterSystemPrompt,
                userMessage = userMessage
            ).getOrNull()

            if (localText != null && localText.length > 30) {
                val parts = localText.split("---SOMATIC---")
                val mainNarrative = parts[0].trim()
                val somatic = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].trim()
                } else {
                    buildDeepSomaticPractice(
                        languageCode = languageCode,
                        arousal = arousal,
                        valence = valence,
                        focus = focus,
                        concepts = titleCasedConcepts,
                        topology = topology,
                        userPrompt = userPrompt
                    )
                }
                return@withContext OrchestratedSimulationResult(
                    narrativeMarkdown = mainNarrative,
                    somaticShiftSuggestion = somatic,
                    providerLabel = "Motor Neural Local (Qwen 2.5 1.5B)",
                    grounding = grounding
                )
            }
        } else {
            // Log the error but continue to fallback
            Log.w("GroundingAndAiService", "Local LLM init failed: ${initResult.exceptionOrNull()?.message}")
        }

        // Tier 3: Autonomous On-Device Dialectical Phenomenological Engine
        onStageProgress(
            when (languageCode.lowercase()) {
                "en" -> "3/3 · Synthesizing deep phenomenological insights and somatic reconfiguration..."
                "es" -> "3/3 · Sintetizando insights fenomenológicos profundos y práctica somática..."
                "fr" -> "3/3 · Synthèse des insights phénoménologiques profonds et pratique somatique..."
                else -> "3/3 · Cruzando geometria das esferas, filosofia e neurociência para sintetizar sua leitura..."
            }
        )
        delay(550)

        val synthesized = synthesizeDeepMultiSphereNarrative(
            userPrompt = userPrompt,
            spatialSpheres = spatialSpheres,
            topology = topology,
            conceptNames = titleCasedConcepts,
            arousal = arousal,
            valence = valence,
            focus = focus,
            grounding = grounding,
            languageCode = languageCode
        )
        val somatic = buildDeepSomaticPractice(
            languageCode = languageCode,
            arousal = arousal,
            valence = valence,
            focus = focus,
            concepts = titleCasedConcepts,
            topology = topology,
            userPrompt = userPrompt
        )

        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Motor Dialético Profundo + Pesquisa Enciclopédica",
            grounding = grounding
        )
    }

    private fun buildTopologyDescriptionString(topology: SpatialTopologyAnalysis): String {
        val parts = mutableListOf<String>()
        topology.dominantSphere?.let {
            parts.add("Âncora central de maior gravidade: ${it.name} (Importância=${String.format("%.1f", it.importanceMass)}, Peso=${(it.weightHeaviness * 100).toInt()}%)")
        }
        topology.closestAffinityPair?.let { (a, b) ->
            parts.add("Par de maior proximidade/afinidade: ${a.name} e ${b.name} (distância=${String.format("%.2f", topology.closestDistance)})")
        }
        topology.furthestPolarityPair?.let { (a, b) ->
            if (a.name != b.name && topology.closestAffinityPair != topology.furthestPolarityPair) {
                parts.add("Par de maior distância/polaridade: ${a.name} e ${b.name} (distância=${String.format("%.2f", topology.furthestDistance)})")
            }
        }
        return parts.joinToString(" | ").ifBlank { "Constelação em equilíbrio distribuído" }
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
                put("temperature", 0.74)
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
     * Deep Multi-Sphere Spatial & Dialectical Phenomenological Engine:
     * Analyzes ALL spheres on the user's canvas, their exact proximity (affinity) and distance (polarity),
     * their individual importance (Hue) and weight (Sensation), the multi-paragraph encyclopedic grounding,
     * and the user's personal text to craft a rich, non-repetitive, revelatory essay.
     */
    private fun synthesizeDeepMultiSphereNarrative(
        userPrompt: String,
        spatialSpheres: List<SphereSpatialDescriptor>,
        topology: SpatialTopologyAnalysis,
        conceptNames: List<String>,
        arousal: Float,
        valence: Float,
        focus: Float,
        grounding: GroundingBundle,
        languageCode: String
    ): String {
        val conceptsList = if (conceptNames.isNotEmpty()) conceptNames else listOf("Desejo", "Medo", "Tempo")
        val primaryConcept = topology.dominantSphere?.name ?: conceptsList.firstOrNull() ?: "Desejo"
        val secondaryConcept = conceptsList.firstOrNull { it != primaryConcept } ?: "Tempo"
        val userContext = userPrompt.trim()

        val cleanDefinitionsMap = grounding.wikiSources.associate { it.conceptTerm.lowercase() to it.cleanExtract }
        val affinityPair = topology.closestAffinityPair
        val polarityPair = topology.furthestPolarityPair

        if (languageCode.lowercase() != "pt") {
            return buildString {
                appendLine("# The Living Constellation of $primaryConcept and $secondaryConcept")
                appendLine("Your mental space right now is not a static list of feelings—it is a dynamic field of forces where each sphere bends the meaning of the others.")
                appendLine()
                appendLine("### 1. The Hidden Geometry of Your Moment")
                if (affinityPair != null) {
                    appendLine("By placing **${affinityPair.first.name}** and **${affinityPair.second.name}** in close proximity, your intuition is signaling an active alliance between them. In Maurice Merleau-Ponty's phenomenology of perception, what we keep close in our field of attention forms our *perceptual anchor*—the lens through which your body is currently reading the world.")
                }
                if (polarityPair != null && polarityPair != affinityPair) {
                    appendLine()
                    appendLine("Meanwhile, the distance separating **${polarityPair.first.name}** and **${polarityPair.second.name}** reveals a protective boundary. Your psyche is keeping these two poles apart so that one does not overwhelm or silence the other.")
                }
                appendLine()
                appendLine("### 2. The Central Insight: Beyond the Dilemma")
                if (userContext.isNotBlank()) {
                    appendLine("Reflecting on what you shared—*\"$userContext\"*—notice how Baruch Spinoza's ethics of affects illuminates this state: tension arises not because one force is 'wrong', but because two legitimate movements of your *conatus* (your vital drive to expand and preserve yourself) are negotiating their rhythm.")
                }
                appendLine()
                appendLine("### 3. What Each Sphere Reveals Today")
                val spheresToRead = if (spatialSpheres.isNotEmpty()) spatialSpheres else conceptsList.map {
                    SphereSpatialDescriptor(it, 1.3f, 0.45f, 0f, 0f)
                }
                spheresToRead.forEach { sp ->
                    val def = cleanDefinitionsMap[sp.name.lowercase()]
                    val insight = distillDeepSphereInsight(sp, def, languageCode)
                    appendLine("- **${sp.name}:** $insight")
                }
            }.trim()
        }

        // Rich, Deep, Accessible Portuguese Phenomenological Essay
        return buildString {
            appendLine("# Cartografia Viva: O Campo Entre $primaryConcept e $secondaryConcept")
            appendLine("O que você acabou de desenhar na tela não é uma lista estática de palavras, mas uma **geometria viva de forças**. Na filosofia da mente e na neurociência afetiva, um estado interior nunca é feito de um único sentimento isolado: ele nasce exatamente da distância, da afinidade e do peso relativo entre tudo o que ocupa a sua atenção agora.")
            appendLine()

            // Section 1: Spatial Geometry (Proximity = Affinity, Distance = Polarity)
            appendLine("### 1. A Geometria Invisível do Seu Momento")
            if (affinityPair != null) {
                val a = affinityPair.first
                val b = affinityPair.second
                val affinityNature = if (topology.closestDistance < 0.38f) {
                    "uma fusão quase inseparável"
                } else {
                    "uma vizinhança de forte afinidade"
                }
                appendLine("Ao posicionar **${a.name}** bem próximo de **${b.name}** ($affinityNature), você revelou onde mora o núcleo de gravidade da sua mente hoje. Para a fenomenologia de Maurice Merleau-Ponty, aquilo que aproximamos no nosso espaço perceptivo funciona como um *acorde de fundo*: **${a.name}** está emprestando sua cor e seu tom para a forma como você experimenta **${b.name}**. Eles não estão competindo; estão alimentando um ao outro.")
            } else {
                appendLine("Com **$primaryConcept** ocupando o centro gravitacional do seu mapa, sua atenção escolheu este ponto como bússola para organizar as demais sensações do dia.")
            }

            if (polarityPair != null && polarityPair != affinityPair && spatialSpheres.size >= 3) {
                val p1 = polarityPair.first
                val p2 = polarityPair.second
                appendLine()
                appendLine("Por outro lado, repare na distância que você deixou entre **${p1.name}** e **${p2.name}**. Quando mantemos duas esferas afastadas nas bordas do campo, nossa psique cria uma *fronteira de proteção*: é o modo inteligente que a sua mente encontrou para preservar **${p1.name}** sem que a pressão de **${p2.name}** invada ou desorganize tudo de uma vez.")
            }

            appendLine()

            // Section 2: Central Philosophical & Psychological Insight addressing User Context
            appendLine("### 2. O Insight Central: Além do Falso Dilema")
            if (userContext.isNotBlank()) {
                appendLine("Olhando diretamente para o contexto que você trouxe — *\"$userContext\"*:")
                appendLine()
                val deepDilemmaAnalysis = analyzeDeepDialecticalDilemma(
                    userText = userContext,
                    dominant = primaryConcept,
                    secondary = secondaryConcept,
                    heavyCount = topology.heavySpheres.size,
                    arousal = arousal,
                    valence = valence
                )
                appendLine(deepDilemmaAnalysis)
            } else {
                appendLine("Na tradição de Baruch de Espinosa, cada afeto que sentimos é uma variação da nossa *potência de agir* (*conatus*). Quando olhamos para as forças de **$primaryConcept** e **$secondaryConcept** juntas, o segredo não é tentar apagar a esfera mais densa para ficar apenas com a mais leve. Como lembram Gilles Deleuze e Francisco Varela, a vitalidade da mente nasce justamente de criar uma *linha de passagem* entre as duas: usar a lucidez de uma para dar contorno e direção à energia da outra.")
            }

            appendLine()

            // Section 3: Individualized Reading of Every Active Sphere on the Canvas
            appendLine("### 3. O Que Cada Esfera Pede de Você Hoje")
            val spheresToRead = if (spatialSpheres.isNotEmpty()) {
                spatialSpheres
            } else {
                conceptsList.map { SphereSpatialDescriptor(it, 1.3f, 0.45f, 0f, 0f) }
            }

            spheresToRead.forEach { sp ->
                val rawDef = cleanDefinitionsMap[sp.name.lowercase()]
                val sphereInsight = distillDeepSphereInsight(sp, rawDef, "pt")
                appendLine("- **${sp.name}:** $sphereInsight")
            }

            // Section 4: Academic / Conceptual Cross-Pollination Note (if academic sources found)
            if (grounding.academicSources.isNotEmpty()) {
                val acadTitle = grounding.academicSources.first().title
                appendLine()
                appendLine("### 4. Ressonância Conceitual & Síntese")
                appendLine("Com seu ritmo interno em **${(arousal * 100).toInt()}%**, abertura emocional em **${(valence * 100).toInt()}%** e foco em **${(focus * 100).toInt()}%**, a literatura contemporânea (em diálogo com estudos como *\"$acadTitle\"*) aponta que a percepção muda no instante em que deixamos de ser reféns de um estado e passamos a ser seus observadores criativos. O simples gesto de mapear essas esferas já reorganiza a forma como o seu sistema nervoso processa este momento.")
            }
        }.trim()
    }

    private fun distillDeepSphereInsight(
        sphere: SphereSpatialDescriptor,
        cleanExtract: String?,
        lang: String
    ): String {
        val lower = sphere.name.lowercase()
        val isHeavy = sphere.weightHeaviness >= 0.55f
        val isHighImportance = sphere.importanceMass >= 1.55f

        val weightQualifierPt = when {
            isHighImportance && isHeavy -> "Configurada com alta importância e sensação densa (${(sphere.weightHeaviness * 100).toInt()}% de peso), esta esfera atua hoje como uma âncora grave que exige respeito ao seu ritmo."
            isHighImportance && !isHeavy -> "Com alta importância mas sensação leve (${(sphere.weightHeaviness * 100).toInt()}% de peso), ela brilha como uma fonte de inspiração aberta, sem cobrança sufocante."
            !isHighImportance && isHeavy -> "Mesmo em tamanho discreto, carrega uma densidade latente (${(sphere.weightHeaviness * 100).toInt()}% de peso)—um lembrete sutil no fundo da mente que pede acolhimento."
            else -> "Flutuando com suavidade e leveza (${(sphere.weightHeaviness * 100).toInt()}% de peso), oferece respiro e espaço livre ao redor das outras ideias."
        }

        if (lang != "pt") {
            val snippet = cleanExtract?.split(Regex("[.!?]"))?.firstOrNull { it.trim().length in 35..200 }?.trim()
            return if (!snippet.isNullOrBlank()) {
                "$snippet. In your constellation today (Weight ${(sphere.weightHeaviness * 100).toInt()}%), it shapes the tone of your inner space."
            } else {
                "Acts as a vital lens in your mental constellation (Weight ${(sphere.weightHeaviness * 100).toInt()}%), balancing impulse and reflection."
            }
        }

        val coreMeaning = when {
            lower.contains("desejo") || lower.contains("vontade") || lower.contains("paixão") ->
                "Para Espinosa, o Desejo é a própria essência humana em movimento—não a carência do que falta, mas a força criadora que imagina novos futuros possíveis."
            lower.contains("medo") || lower.contains("receio") || lower.contains("cautela") ->
                "Na neurociência afetiva, o Medo não é um inimigo nem sinal de fraqueza: é uma sentinela antiga que só desperta diante daquilo que é precioso e tem valor real para a sua vida."
            lower.contains("tempo") || lower.contains("futuro") || lower.contains("passado") || lower.contains("espera") ->
                "Henri Bergson diferenciava o tempo do relógio (mecânico e apressado) da *duração interior* (o tempo vivo da maturação). Nem tudo o que é fértil floresce na velocidade da ansiedade."
            lower.contains("identidade") || lower.contains("eu") ->
                "A Identidade não é uma estátua pronta, mas uma narrativa em constante autopoiese (como ensinava Francisco Varela). Mudar de posição ou acolher novas dúvidas não ameaça quem você é; amplia sua inteireza."
            lower.contains("criatividade") || lower.contains("arte") || lower.contains("invenção") ->
                "Em Deleuze, criar é traçar uma *linha de fuga*—encontrar uma saída inédita onde o hábito só enxergava repetição. Ela transforma a tensão das outras esferas em matéria-prima expressiva."
            lower.contains("silêncio") || lower.contains("calma") || lower.contains("vazio") || lower.contains("paz") ->
                "O Silêncio é o intervalo acústico e mental indispensável para que os pensamentos decantem. Sem espaço vazio entre as notas, não existe música—apenas ruído."
            lower.contains("cansaço") || lower.contains("sono") || lower.contains("exaustão") || lower.contains("repouso") ->
                "O Cansaço é a sabedoria biológica do corpo interrompendo o excesso de exigência cognitiva. Honrar o descanso não é abandonar seus projetos, é devolver solo fértil para eles."
            lower.contains("justiça") || lower.contains("equilíbrio") || lower.contains("ética") ->
                "Representa a justa medida e a ecologia interior: o compromisso de não ser injusto consigo mesmo enquanto tenta dar conta das demandas do mundo."
            lower.contains("foco") || lower.contains("força") || lower.contains("clareza") ->
                "A atenção seletiva que recolhe a energia dispersa e a conduz para um único ponto de presença lúcida."
            else -> {
                val cleanSentences = cleanExtract
                    ?.split(Regex("(?<=[.!?])\\s+"))
                    ?.filter { it.trim().length in 40..220 }
                    ?.take(2)
                    ?.joinToString(" ")
                    ?.trim()

                if (!cleanSentences.isNullOrBlank()) {
                    "$cleanSentences Na sua constelação subjetiva, este conceito funciona como uma metáfora viva que organiza suas percepções de hoje."
                } else {
                    "Atua como um território singular na sua paisagem subjetiva, abrindo novas conexões entre o que você sente e o que você pensa."
                }
            }
        }

        return "$coreMeaning $weightQualifierPt"
    }

    private fun analyzeDeepDialecticalDilemma(
        userText: String,
        dominant: String,
        secondary: String,
        heavyCount: Int,
        arousal: Float,
        valence: Float
    ): String {
        val lower = userText.lowercase()
        val somaticContext = if (heavyCount >= 2 || arousal > 0.65f) {
            "Repare que você configurou esferas com peso acentuado e ritmo interno elevado (${(arousal * 100).toInt()}%), o que indica que essa questão não está apenas nas ideias, mas vibrando fisicamente no seu corpo."
        } else {
            "Seu estado corporal mostra espaço de sobriedade e contemplação (${(valence * 100).toInt()}% de abertura), o que permite observar essa questão sem ser engolido pela pressa."
        }

        return when {
            lower.contains("dividido") || lower.contains("dúvida") || lower.contains("escolh") || lower.contains("entre") ->
                "Quando nos sentimos divididos, costumamos achar que uma parte de nós está errada e precisa vencer a outra. Mas na filosofia de Espinosa e na psicologia profunda, a ambivalência raramente é fraqueza: ela acontece quando **duas verdades legítimas** estão pedindo passagem ao mesmo tempo. **$dominant** aponta para aquilo que quer crescer, enquanto **$secondary** protege o que você não quer perder. $somaticContext Em vez de exigir uma resposta definitiva sob pressão hoje, permita um pequeno experimento prático que honre os dois lados sem que um precise aniquilar o outro."

            lower.contains("projeto") || lower.contains("trabalho") || lower.contains("criar") || lower.contains("começar") || lower.contains("novo") ->
                "Todo gesto de criação ou mudança real esbarra na fronteira entre a expansão (**$dominant**) e a conservação (**$secondary**). Na neurociência do processamento preditivo, o cérebro humano prefere o conhecido—mesmo quando insatisfatório—porque o conhecido exige menos gasto energético. Por isso, o frio na barriga ou a hesitação antes de avançar não são sinais de que você não é capaz; são apenas o seu sistema nervoso recalibrando o mapa diante do inédito. $somaticContext Reduza a escala do próximo passo até que ele pareça um teste curioso, e não um salto no escuro."

            lower.contains("ansios") || lower.contains("medo") || lower.contains("preocup") || lower.contains("tenso") || lower.contains("cansad") ->
                "A tensão acumulada surge quando a mente tenta resolver no plano abstrato do futuro algo que o corpo está sentindo no presente. Quando **$dominant** e **$secondary** se cruzam sob pressão, o pensamento tende a girar em círculos procurando garantias absolutas antes de descansar. $somaticContext A virada de chave aqui é inverter a ordem: primeiro oferecer segurança sensorial ao corpo (soltando a vigilância muscular), para só depois deixar que a relação entre **$dominant** e **$secondary** se decante naturalmente."

            else ->
                "Aquilo que você expressou revela como **$dominant** e **$secondary** estão tecendo um diálogo silencioso nos bastidores da sua atenção. Muitas vezes, tratamos nossos estados internos como problemas a serem resolvidos rapidamente, quando na verdade eles são **paisagens a serem compreendidas**. $somaticContext Ao externalizar essas esferas na tela e dar a cada uma sua cor, seu peso e seu lugar exato no espaço, você já deixou de estar misturado à névoa do sentimento e assumiu o lugar de quem observa com lucidez e gentileza."
        }
    }

    /**
     * Concrete, 3-step neuro-sensory somatic practice tailored to the user's topology & biofeedback.
     */
    private fun buildDeepSomaticPractice(
        languageCode: String,
        arousal: Float,
        valence: Float,
        focus: Float,
        concepts: List<String>,
        topology: SpatialTopologyAnalysis,
        userPrompt: String
    ): String {
        val primary = topology.dominantSphere?.name ?: concepts.firstOrNull() ?: "Harmonia"
        val secondary = topology.closestAffinityPair?.second?.name
            ?: concepts.getOrNull(1)
            ?: "Presença"

        if (languageCode.lowercase() != "pt") {
            return buildString {
                appendLine("**3-Step Embodied Reconfiguration ($primary · $secondary):**")
                appendLine("- **1. Optic Nerve Decompression (Peripheral Gaze):** Soften your eyes on the screen and simultaneously notice the left and right edges of your room without turning your head. Expanding peripheral vision switches off the amygdala's tunnel-vision alert.")
                appendLine("- **2. Vagal Resonance Breath (4s / 6s):** Inhale gently through your nose for 4 seconds while welcoming **$primary**; exhale slowly through your mouth for 6 seconds while letting the weight of **$secondary** settle into the floor.")
                appendLine("- **3. Proprioceptive Release:** Drop your shoulders two centimeters, unclench your jaw, and listen for 20 seconds to the 432Hz pentatonic harmony playing in the background.")
            }.trim()
        }

        return buildString {
            appendLine("**Prática Somática em 3 Passos ($primary ↔ $secondary):**")
            appendLine("- **1. Descompressão do Nervo Óptico (Visão Periférica):** Olhando para o brilho suave das esferas de **$primary** e **$secondary**, amplie sua visão lateral para perceber os dois cantos da sala ao mesmo tempo, sem mover a cabeça. Na neurofisiologia, sair da visão em túnel e abrir a visão periférica desativa imediatamente o reflexo de alerta simpático.")
            appendLine("- **2. Respiração de Tônus Vagal (Ciclo 4s / 6s):** Inspire suavemente pelo nariz contando 4 segundos, sentindo espaço se abrir no tórax; solte o ar lentamente pela boca durante 6 segundos, como se deixasse o peso acumulado das esferas escorrer para o chão.")
            appendLine("- **3. Soltura Proprioceptiva e Escuta:** Destrave os dentes, deixe a língua repousar solta no céu da boca e solte o peso dos ombros. Feche os olhos por 20 segundos acompanhando apenas a respiração das notas pentatônicas em 432Hz que estão tocando agora.")
        }.trim()
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
