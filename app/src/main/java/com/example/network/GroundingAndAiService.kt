package com.example.network

import android.content.Context
import android.util.Log
import com.example.util.AppLog
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
import kotlin.random.Random

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

    // Local LLM removido (Tier 2 on-device morto por decisão de produto).

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

        fun attempt(name: String): GroundingSource? {
            return runCatching {
                val encoded = URLEncoder.encode(name, "UTF-8")
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
        }
        for (candidate in candidates) {
            val result = attempt(candidate)
            if (result != null) return result
        }
        // Busca aproximada (MediaWiki search): corrige typos e encontra o
        // verbete mais próximo — "Jangda" vira "Jangada".
        val suggested = searchClosestTitle(cleanTerm, lang)
        if (suggested != null && candidates.none { it.equals(suggested, ignoreCase = true) }) {
            return attempt(suggested)
        }
        return null
    }

    private fun searchClosestTitle(term: String, lang: String): String? {
        return runCatching {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=3&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/2.4 (Android)")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = JSONObject(resp.body?.string() ?: return@use null)
                    .optJSONObject("query")
                    ?.optJSONArray("search") ?: return@use null
                if (arr.length() == 0) return@use null
                arr.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
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

    /** Picks the string for the active app language (pt / en / es / fr). */
    private fun L(lang: String, pt: String, en: String, es: String, fr: String): String =
        when (lang.lowercase()) {
            "en" -> en
            "es" -> es
            "fr" -> fr
            else -> pt
        }

    suspend fun verifyProviderAndPingModels(
        rawBaseUrl: String,
        apiKey: String,
        pingWord: String = "oi",
        langCode: String = "pt"
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
                        errorMessage = L(
                            langCode,
                            "HTTP ${resp.code} ao consultar /models: ${resp.message}",
                            "HTTP ${resp.code} while requesting /models: ${resp.message}",
                            "HTTP ${resp.code} al consultar /models: ${resp.message}",
                            "HTTP ${resp.code} lors de la requête /models : ${resp.message}"
                        )
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
                errorMessage = L(
                    langCode,
                    "Falha de conexão: ${e.localizedMessage ?: "Verifique a URL e internet"}",
                    "Connection failure: ${e.localizedMessage ?: "Check the URL and your internet"}",
                    "Fallo de conexión: ${e.localizedMessage ?: "Revisa la URL y tu conexión"}",
                    "Échec de connexion : ${e.localizedMessage ?: "Vérifiez l'URL et votre connexion"}"
                )
            )
        }

        if (discoveredIds.isEmpty()) {
            return@withContext ProviderVerificationReport(
                connectionSuccess = false,
                totalDiscoveredModels = 0,
                verifiedResponsiveModels = emptyList(),
                pingDetails = emptyList(),
                errorMessage = L(
                    langCode,
                    "Nenhum modelo listado pelo endpoint /models.",
                    "No models listed by the /models endpoint.",
                    "Ningún modelo listado por el endpoint /models.",
                    "Aucun modèle listé par le point d'accès /models."
                )
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
                L(
                    langCode,
                    "Foram encontrados ${discoveredIds.size} modelos em /models, mas nenhum respondeu 200 OK ao ping.",
                    "${discoveredIds.size} models were found in /models, but none answered 200 OK to the ping.",
                    "Se encontraron ${discoveredIds.size} modelos en /models, pero ninguno respondió 200 OK al ping.",
                    "${discoveredIds.size} modèles ont été trouvés dans /models, mais aucun n'a répondu 200 OK au ping."
                )
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
        focus: Float,
        creativeDirective: String = ""
    ): String = """
        Você é o Noesis Lab, guia perceptivo e filósofo da mente.
        $languageInstruction

        MISSÃO: leitura profunda e reveladora, em linguagem lúcida, calorosa e acessível — sem jargão acadêmico e sem autoajuda rasa. Sessão única e independente: analise só o presente desenhado.
        REGRA DE OURO: NUNCA cite filósofos, teorias ou autores pelo nome na resposta. Incorpore estas lentes no modo de olhar, sem mencioná-las:
        - Afetos como variação da potência de agir; transformar o que paralisa em compreensão que liberta.
        - O corpo habita espaço e tempo: a posição das esferas mostra como o usuário está morando o momento.
        - Ligar pontos distantes revela saídas criativas onde se via só bloqueio.
        - O cérebro antecipa cenários; distância entre esferas = fronteira de proteção, proximidade = aliança.

        SLIDERS (module tom e conteúdo): Ritmo Interno=${(arousal * 100).toInt()}%: alto → frases curtas e práticas de descarga; baixo → cadência lenta e sustentação. Abertura Afetiva=${(valence * 100).toInt()}%: alta → ouse e convide; baixa → acolha em passos pequenos. Foco=${(focus * 100).toInt()}%: alto → aprofunde um fio; baixo → panorama leve. Esferas pesadas pedem respeito ao ritmo; leves pedem leveza.
        CADA LEITURA DEVE SER INÉDITA: varie título, metáfora central e ritmo das frases; nunca repita fórmulas de leituras anteriores.
        DIRETRIZ CRIATIVA DESTA LEITURA (obrigatória — define a voz deste texto; nunca a mencione explicitamente):
        $creativeDirective

        DADOS — Esferas: $particlesSummary
        Topologia: $topologyDescription
        Base conceitual (pode conter vários idiomas — inglês, francês, alemão etc.: leia tudo, mas responda sempre no idioma do app):
        $cleanKnowledgeSummary

        ESTRUTURA (Markdown): título `#` poético e inédito; `### 1.` geometria do momento citando nominalmente os pares; `### 2.` insight que desmonte o falso dilema do relato; `### 3.` um tópico por esfera (cor, peso, sentido), variando a frase a cada esfera; `---SOMATIC---` + exercício corporal INÉDITO de 3 passos (uma técnica validada + uma metáfora das esferas; proibido repetir sempre o mesmo trio).
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

        val seed = System.currentTimeMillis()
        val promptRng = Random(seed)
        val creativeDirective = if (languageCode.lowercase() == "pt") {
            creativeDirectivesPt[promptRng.nextInt(creativeDirectivesPt.size)]
        } else {
            creativeDirectivesEn[promptRng.nextInt(creativeDirectivesEn.size)]
        }

        val masterSystemPrompt = buildMasterPhenomenologicalSystemPrompt(
            languageInstruction = languageInstruction,
            particlesSummary = particlesSummary,
            topologyDescription = topologySummary,
            cleanKnowledgeSummary = cleanKnowledgeSummary,
            arousal = arousal,
            valence = valence,
            focus = focus,
            creativeDirective = creativeDirective
        )

        val userMessage = """
            Esferas ativas no mapa: $particlesSummary
            Relações espaciais: $topologySummary
            Relato / Contexto do usuário nesta sessão: ${userPrompt.ifBlank { "Leitura direta da constelação atual das esferas." }}
        """.trimIndent()

        // Tier 1: User's BYOK Provider (if configured). Quando há provedor
        // ativo, ele tem prioridade total: o Tier 2 nem é tentado.
        val hasActiveByok = !activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()
        if (hasActiveByok) {
            onStageProgress(
                L(
                    languageCode,
                    "3/3 · Consultando ${activeProviderName ?: "BYOK"} ($activeModelId)...",
                    "3/3 · Querying ${activeProviderName ?: "BYOK"} ($activeModelId)...",
                    "3/3 · Consultando ${activeProviderName ?: "BYOK"} ($activeModelId)...",
                    "3/3 · Interrogation de ${activeProviderName ?: "BYOK"} ($activeModelId)..."
                )
            )
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
                        userPrompt = userPrompt,
                        seed = System.currentTimeMillis()
                    )
                }
                AppLog.i("sim", "tier=BYOK ok label='${activeProviderName ?: "BYOK"}'")
                return@withContext OrchestratedSimulationResult(
                    narrativeMarkdown = mainNarrative,
                    somaticShiftSuggestion = somatic,
                    providerLabel = "${activeProviderName ?: "BYOK"} · $activeModelId",
                    grounding = grounding
                )
            }
        }

        // Tier 2: Motor Interno (Dialético + Pesquisa Enciclopédica)
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
            languageCode = languageCode,
            seed = seed
        )
        val somatic = buildDeepSomaticPractice(
            languageCode = languageCode,
            arousal = arousal,
            valence = valence,
            focus = focus,
            concepts = titleCasedConcepts,
            topology = topology,
            userPrompt = userPrompt,
            seed = seed
        )

        AppLog.i("sim", "tier=internal-fallback wiki=${grounding.wikiSources.size} acad=${grounding.academicSources.size}")
        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Motor Interno",
            grounding = grounding
        )
    }

    /**
     * Fase 3: continua uma leitura dentro da thread (chat). Cada resposta traz
     * texto adaptado ao relato + NOVA leva de exercícios (seed distinta).
     */
    suspend fun orchestrateFollowUp(
        threadRecap: String,
        newUserText: String,
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
        val seed = System.currentTimeMillis()

        onStageProgress(
            when (languageCode.lowercase()) {
                "en" -> "Listening and re-reading your field..."
                "es" -> "Escuchando y releyendo tu campo..."
                "fr" -> "Écoute et relecture de votre champ..."
                else -> "Ouvindo e relendo seu campo..."
            }
        )
        val grounding = fetchLiveGrounding(
            queryConcepts = titleCasedConcepts,
            userPrompt = newUserText,
            languageCode = languageCode
        )

        val topologySummary = buildTopologyDescriptionString(topology)
        val cleanKnowledgeSummary = grounding.wikiSources.joinToString("\n") {
            "- ${it.conceptTerm}: ${it.cleanExtract.take(380)}"
        }

        val threadSystemPrompt = buildMasterPhenomenologicalSystemPrompt(
            languageInstruction = languageInstruction,
            particlesSummary = particlesSummary,
            topologyDescription = topologySummary,
            cleanKnowledgeSummary = cleanKnowledgeSummary,
            arousal = arousal,
            valence = valence,
            focus = focus
        ) + "\n\nCONTINUIDADE DE CONVERSA (obrigatório): isto é uma resposta dentro de um diálogo. " +
            "Dialogue diretamente com o último relato do usuário, reconheça o que ele concordou ou discordou, " +
            "adapte a leitura ao cenário específico que ele descreve agora e NUNCA repita exercícios anteriores — " +
            "crie uma leva inédita de 3 passos após ---SOMATIC---."

        val threadMessage = """
            Contexto da conversa até aqui (resumo):
            ${threadRecap.take(900)}
            Novo relato do usuário nesta sessão: $newUserText
        """.trimIndent()

        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            val byokText = callOpenAiCompatibleEndpoint(
                endpointUrl = "${normalizeOpenAiBaseUrl(activeBaseUrl)}/chat/completions",
                apiKey = activeApiKey,
                modelId = activeModelId,
                systemPrompt = threadSystemPrompt,
                userMessage = threadMessage
            )
            if (byokText != null) {
                val parts = byokText.split("---SOMATIC---")
                return@withContext OrchestratedSimulationResult(
                    narrativeMarkdown = parts[0].trim(),
                    somaticShiftSuggestion = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].trim() else buildDeepSomaticPractice(
                        languageCode, arousal, valence, focus, titleCasedConcepts, topology, newUserText, seed + 7
                    ),
                    providerLabel = "${activeProviderName ?: "BYOK"} · $activeModelId",
                    grounding = grounding
                )
            }
        }

        delay(450)
        val userPrompt2 = "Leitura inicial e desdobramentos: ${threadRecap.take(500)}\n\nNovo relato: $newUserText"
        val synthesized = synthesizeDeepMultiSphereNarrative(
            userPrompt = userPrompt2,
            spatialSpheres = spatialSpheres,
            topology = topology,
            conceptNames = titleCasedConcepts,
            arousal = arousal,
            valence = valence,
            focus = focus,
            grounding = grounding,
            languageCode = languageCode,
            seed = seed
        )
        val somatic = buildDeepSomaticPractice(
            languageCode = languageCode,
            arousal = arousal,
            valence = valence,
            focus = focus,
            concepts = titleCasedConcepts,
            topology = topology,
            userPrompt = newUserText,
            seed = seed + 7
        )
        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Motor Interno",
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

    // ---------- Bancos de variedade do motor combinatório (Tier 2 interno) ----------
    // Cada leitura sorteia (seed = timestamp) aberturas, metáforas e módulos,
    // de modo que duas leituras nunca tenham a mesma forma — mesmo com as
    // mesmas esferas. Variedade combinatória real; criação livre exige LLM.

    private val openingTitlesPt = listOf(
        "O Campo Entre %1\$s e %2\$s",
        "Quando %1\$s Encontra %2\$s",
        "A Constelação de %1\$s",
        "O Peso e a Luz de %1\$s",
        "Geografia Íntima: %1\$s, %2\$s e o Espaço Entre Elas",
        "O Mapa Que %1\$s Desenhou Hoje",
        "Entre %1\$s e %2\$s: Leitura do Momento",
        "A Paisagem Interior de %1\$s"
    )

    private val openingLinesPt = listOf(
        "O que você desenhou na tela não é uma lista de palavras, mas uma **geometria viva de forças**: cada distância, cada aproximação e cada peso contam como o seu corpo está negociando o dia.",
        "Nenhum estado interior nasce de um sentimento isolado. O que você mapeou é um **campo de tensões e alianças** — e é exatamente na relação entre as esferas que mora o sentido de hoje.",
        "Imagine sua atenção como um céu noturno: cada esfera é uma estrela, mas o desenho que importa é a **constelação** — as linhas invisíveis que ligam %1\$s a %2\$s.",
        "A mente não entrega relatórios; ela entrega **paisagens**. O mapa que você compôs revela relevo, clima e correntes — vamos percorrê-lo juntos.",
        "Antes das palavras, o corpo já tinha votado: posição, tamanho e peso de cada esfera são o **boletim somático** do seu momento. Eis a leitura dele.",
        "Toda configuração espacial é uma frase que o corpo escreve. A sua diz algo sobre %1\$s que as palavras sozinhas não diriam — vamos traduzi-la."
    )

    private val anchorLensPt = listOf(
        "Nada aqui é defeito moral: cada força é potência pedindo direção — o trabalho é transformar o que paralisa em compreensão que liberta.",
        "Perceber é habitar: o mapa que você desenhou mostra o modo como o seu corpo está morando no espaço e no tempo hoje.",
        "Criar é ligar pontos distantes e descobrir saídas onde o hábito só via repetição: procure no mapa a conexão improvável.",
        "A mente se faz ao mapear-se: o simples gesto de externalizar essas esferas já reorganiza seu sistema nervoso.",
        "Há o tempo do relógio e há o tempo vivo da maturação: nem tudo o que é fértil floresce na velocidade da ansiedade."
    )

    private val creativeDirectivesPt = listOf(
        "Nesta leitura, o fio condutor é a água: correntes, marés, o que flutua e o que afunda.",
        "Nesta leitura, conte como quem narra um sonho ao acordar: imagens primeiro, explicações depois.",
        "Nesta leitura, o corpo vem antes das ideias: cada interpretação começa pela sensação física.",
        "Nesta leitura, cada esfera é um personagem em diálogo — deixe-os conversar entre si.",
        "Nesta leitura, o tempo é o protagonista: o passado puxando de um lado, o futuro chamando do outro.",
        "Nesta leitura, procure o avesso: o que cada força esconde quando ninguém está olhando."
    )

    private val creativeDirectivesEn = listOf(
        "In this reading, let water lead: currents, tides, what floats and what sinks.",
        "In this reading, narrate as if recounting a dream: images first, explanations later.",
        "In this reading, the body comes before ideas: every insight starts from physical sensation.",
        "In this reading, each sphere is a character in dialogue — let them talk to each other."
    )

    private val affinityFramesPt = listOf(
        "uma fusão quase inseparável",
        "uma vizinhança de forte afinidade",
        "um acoplamento em ressonância",
        "uma aliança em gestação"
    )

    private val closingLinesPt = listOf(
        "Leve este mapa com você hoje: volte a ele quando a névoa voltar — observar já é começar a transformar.",
        "Nenhuma constelação é sentença. Ela é fotografia do céu agora; amanhã as estrelas se movem — e você com elas.",
        "Guarde uma imagem desta leitura no corpo: ela será sua bússola quando as palavras faltarem.",
        "O trabalho de hoje está feito: você nomeou, posicionou e pesou. O resto é deixar o tempo fazer a digestão.",
        "Que este mapa sirva de espelho gentil — não para julgar o território, mas para caminhar nele com mais lucidez."
    )

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
        languageCode: String,
        seed: Long
    ): String {
        val conceptsList = if (conceptNames.isNotEmpty()) conceptNames else listOf("Desejo", "Medo", "Tempo")
        val primaryConcept = topology.dominantSphere?.name ?: conceptsList.firstOrNull() ?: "Desejo"
        val secondaryConcept = conceptsList.firstOrNull { it != primaryConcept } ?: "Tempo"
        val userContext = userPrompt.trim()

        val cleanDefinitionsMap = grounding.wikiSources.associate { it.conceptTerm.lowercase() to it.cleanExtract }
        val affinityPair = topology.closestAffinityPair
        val polarityPair = topology.furthestPolarityPair

        if (languageCode.lowercase() != "pt") {
            val lang = languageCode.lowercase()
            return buildString {
                appendLine(
                    L(
                        lang,
                        "# A Constelação Viva de $primaryConcept e $secondaryConcept",
                        "# The Living Constellation of $primaryConcept and $secondaryConcept",
                        "# La Constelación Viva de $primaryConcept y $secondaryConcept",
                        "# La Constellation Vivante de $primaryConcept et $secondaryConcept"
                    )
                )
                appendLine(
                    L(
                        lang,
                        "Seu espaço mental agora não é uma lista estática de sentimentos — é um campo dinâmico de forças onde cada esfera muda o significado das outras.",
                        "Your mental space right now is not a static list of feelings—it is a dynamic field of forces where each sphere bends the meaning of the others.",
                        "Tu espacio mental ahora mismo no es una lista estática de sentimientos: es un campo dinámico de fuerzas donde cada esfera cambia el significado de las demás.",
                        "Votre espace mental n'est pas une liste statique de sentiments : c'est un champ dynamique de forces où chaque sphère modifie le sens des autres."
                    )
                )
                appendLine()
                appendLine(
                    L(
                        lang,
                        "### 1. A Geometria Oculta do Seu Momento",
                        "### 1. The Hidden Geometry of Your Moment",
                        "### 1. La Geometría Oculta de Tu Momento",
                        "### 1. La Géométrie Cachée de Votre Moment"
                    )
                )
                if (affinityPair != null) {
                    val a = affinityPair.first.name
                    val b = affinityPair.second.name
                    appendLine(
                        L(
                            lang,
                            "Ao aproximar **$a** de **$b**, sua intuição sinaliza uma aliança ativa entre elas. O que mantemos perto no campo da atenção forma nossa *âncora perceptiva* — a lente com que o seu corpo lê o mundo agora.",
                            "By placing **$a** and **$b** in close proximity, your intuition is signaling an active alliance between them. What we keep close in our field of attention forms our *perceptual anchor* — the lens through which your body is currently reading the world.",
                            "Al colocar **$a** y **$b** muy cerca, tu intuición señala una alianza activa entre ellas. Lo que mantenemos cerca en nuestro campo de atención forma nuestra *ancla perceptiva*: la lente con la que tu cuerpo lee el mundo ahora.",
                            "En plaçant **$a** et **$b** tout près l'une de l'autre, votre intuition signale une alliance active entre elles. Ce que nous gardons proche dans notre champ d'attention forme notre *ancre perceptive* : la lentille avec laquelle votre corps lit le monde en ce moment."
                        )
                    )
                }
                if (polarityPair != null && polarityPair != affinityPair) {
                    val p1 = polarityPair.first.name
                    val p2 = polarityPair.second.name
                    appendLine()
                    appendLine(
                        L(
                            lang,
                            "Enquanto isso, a distância entre **$p1** e **$p2** revela uma fronteira de proteção. Sua psique mantém esses dois polos afastados para que um não sobreponha nem silencie o outro.",
                            "Meanwhile, the distance separating **$p1** and **$p2** reveals a protective boundary. Your psyche is keeping these two poles apart so that one does not overwhelm or silence the other.",
                            "Mientras tanto, la distancia que separa **$p1** de **$p2** revela una frontera de protección. Tu psique mantiene estos dos polos alejados para que uno no abrume ni silencie al otro.",
                            "Pendant ce temps, la distance qui sépare **$p1** de **$p2** révèle une frontière de protection. Votre psyché maintient ces deux pôles éloignés pour que l'un n'écrase ni ne fasse taire l'autre."
                        )
                    )
                }
                appendLine()
                appendLine(
                    L(
                        lang,
                        "### 2. A Ideia Central: Além do Dilema",
                        "### 2. The Central Insight: Beyond the Dilemma",
                        "### 2. La Idea Central: Más Allá del Dilema",
                        "### 2. L'Idée Centrale : Au-delà du Dilemme"
                    )
                )
                if (userContext.isNotBlank()) {
                    appendLine(
                        L(
                            lang,
                            "Olhando para o que você trouxe — *\"$userContext\"* — repare que a tensão não nasce porque uma força esteja 'errada', mas porque dois movimentos legítimos da sua potência vital — expandir e preservar — estão negociando o ritmo.",
                            "Reflecting on what you shared—*\"$userContext\"*—notice how tension arises not because one force is 'wrong', but because two legitimate movements of your vital drive to expand and preserve yourself are negotiating their rhythm.",
                            "Al mirar lo que compartiste —*\"$userContext\"*— observa que la tensión no surge porque una fuerza esté 'mal', sino porque dos movimientos legítimos de tu impulso vital —expandirte y preservarte— están negociando su ritmo.",
                            "En regardant ce que vous avez partagé —*\"$userContext\"*— remarquez que la tension ne naît pas parce qu'une force serait « mauvaise », mais parce que deux mouvements légitimes de votre élan vital — s'ouvrir et se préserver — négocient leur rythme."
                        )
                    )
                }
                appendLine()
                appendLine(
                    L(
                        lang,
                        "### 3. O Que Cada Esfera Revela Hoje",
                        "### 3. What Each Sphere Reveals Today",
                        "### 3. Lo Que Cada Esfera Revela Hoy",
                        "### 3. Ce Que Chaque Sphère Révèle Aujourd'hui"
                    )
                )
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

        // Rich, Deep, Accessible Portuguese Phenomenological Essay (seeded variety)
        val rng = Random(seed)
        return buildString {
            val titleTemplate = openingTitlesPt[rng.nextInt(openingTitlesPt.size)]
            appendLine("# " + titleTemplate.replace("%1\$s", primaryConcept).replace("%2\$s", secondaryConcept))
            val openingTemplate = openingLinesPt[rng.nextInt(openingLinesPt.size)]
            appendLine(openingTemplate.replace("%1\$s", primaryConcept).replace("%2\$s", secondaryConcept))
            appendLine()
            appendLine(anchorLensPt[rng.nextInt(anchorLensPt.size)])
            appendLine()
            appendLine(creativeDirectivesPt[rng.nextInt(creativeDirectivesPt.size)])
            appendLine()

            // Section 1: Spatial Geometry (Proximity = Affinity, Distance = Polarity)
            appendLine("### 1. A Geometria Invisível do Seu Momento")
            if (affinityPair != null) {
                val a = affinityPair.first
                val b = affinityPair.second
                val affinityNature = if (topology.closestDistance < 0.38f) {
                    affinityFramesPt[0]
                } else {
                    affinityFramesPt[1 + rng.nextInt(affinityFramesPt.size - 1)]
                }
                val affinityFormulation = rng.nextInt(3)
                when (affinityFormulation) {
                    0 -> appendLine("Ao posicionar **${a.name}** bem próximo de **${b.name}** ($affinityNature), você revelou onde mora o núcleo de gravidade da sua mente hoje. Aquilo que aproximamos no nosso espaço perceptivo funciona como um *acorde de fundo*: **${a.name}** está emprestando sua cor e seu tom para a forma como você experimenta **${b.name}**. Eles não estão competindo; estão alimentando um ao outro.")
                    1 -> appendLine("O par **${a.name}** ↔ **${b.name}** ($affinityNature) é o coração do seu mapa atual. Onde a atenção aproxima, o corpo declara aliança: essas duas forças estão operando em circuito fechado, e o estado de uma regula diretamente o estado da outra.")
                    else -> appendLine("Repare no abraço entre **${a.name}** e **${b.name}** ($affinityNature). Em termos deleuzianos, aí corre sua principal *linha de intensidade*: seguir esse fio — em vez de rompê-lo — é o caminho mais curto para a clareza hoje.")
                }
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
                    valence = valence,
                    seed = seed
                )
                appendLine(deepDilemmaAnalysis)
            } else {
                appendLine("Cada afeto que sentimos é uma variação da nossa *potência de agir*: quando olhamos para as forças de **$primaryConcept** e **$secondaryConcept** juntas, o segredo não é tentar apagar a esfera mais densa para ficar apenas com a mais leve. A vitalidade da mente nasce justamente de criar uma *linha de passagem* entre as duas: usar a lucidez de uma para dar contorno e direção à energia da outra.")
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

            appendLine()
            appendLine(closingLinesPt[rng.nextInt(closingLinesPt.size)])
        }.trim()
    }

    private fun distillDeepSphereInsight(
        sphere: SphereSpatialDescriptor,
        cleanExtract: String?,
        lang: String
    ): String {
        val lower = sphere.name.lowercase()
        val v = (sphere.name.hashCode() and Int.MAX_VALUE) % 2
        val isHeavy = sphere.weightHeaviness >= 0.55f
        val isHighImportance = sphere.importanceMass >= 1.55f
        val isMidHeavy = !isHeavy && sphere.weightHeaviness >= 0.38f

        val weightQualifierPt = when {
            isHighImportance && isHeavy -> "Configurada com alta importância e sensação densa (${(sphere.weightHeaviness * 100).toInt()}% de peso), esta esfera atua hoje como uma âncora grave que exige respeito ao seu ritmo."
            isHighImportance && isMidHeavy -> "Com alta importância e densidade intermediária (${(sphere.weightHeaviness * 100).toInt()}% de peso), ela tem presença firme sem esmagar: pede atenção contínua, não urgência."
            isHighImportance && !isHeavy -> "Com alta importância mas sensação leve (${(sphere.weightHeaviness * 100).toInt()}% de peso), ela brilha como uma fonte de inspiração aberta, sem cobrança sufocante."
            !isHighImportance && isHeavy -> "Mesmo em tamanho discreto, carrega uma densidade latente (${(sphere.weightHeaviness * 100).toInt()}% de peso) — um lembrete sutil no fundo da mente que pede acolhimento."
            !isHighImportance && isMidHeavy -> "Discreta no tamanho e morna na sensação (${(sphere.weightHeaviness * 100).toInt()}% de peso), ela trabalha em silêncio: influência de fundo que merece uma escuta atenta."
            else -> "Flutuando com suavidade e leveza (${(sphere.weightHeaviness * 100).toInt()}% de peso), oferece respiro e espaço livre ao redor das outras ideias."
        }

        if (lang != "pt") {
            val snippet = cleanExtract?.split(Regex("[.!?]"))?.firstOrNull { it.trim().length in 35..200 }?.trim()
            val weightPct = (sphere.weightHeaviness * 100).toInt()
            return if (!snippet.isNullOrBlank()) {
                L(
                    lang,
                    "$snippet. Na sua constelação de hoje (peso $weightPct%), ela dá o tom do seu espaço interno.",
                    "$snippet. In your constellation today (Weight $weightPct%), it shapes the tone of your inner space.",
                    "$snippet. En tu constelación de hoy (peso $weightPct%), define el tono de tu espacio interior.",
                    "$snippet. Dans votre constellation d'aujourd'hui (poids $weightPct%), elle donne le ton de votre espace intérieur."
                )
            } else {
                L(
                    lang,
                    "Funciona como uma lente vital na sua constelação mental (peso $weightPct%), equilibrando impulso e reflexão.",
                    "Acts as a vital lens in your mental constellation (Weight $weightPct%), balancing impulse and reflection.",
                    "Actúa como una lente vital en tu constelación mental (peso $weightPct%), equilibrando impulso y reflexión.",
                    "Elle agit comme une lentille vitale dans votre constellation mentale (poids $weightPct%), équilibrant l'élan et la réflexion."
                )
            }
        }

        val coreMeaning = when {
            lower.contains("desejo") || lower.contains("vontade") || lower.contains("paixão") ->
                if (v == 0) "O Desejo é a própria essência humana em movimento — não a carência do que falta, mas a força criadora que imagina novos futuros possíveis."
                else "O Desejo aponta para onde a vida quer se expandir: siga o vetor dele e observe o que cresce junto."
            lower.contains("medo") || lower.contains("receio") || lower.contains("cautela") ->
                if (v == 0) "O Medo não é um inimigo nem sinal de fraqueza: é uma sentinela antiga que só desperta diante daquilo que é precioso e tem valor real para a sua vida."
                else "O Medo guarda o que importa: onde ele aparece, há algo valioso pedindo proteção — não fuga."
            lower.contains("tempo") || lower.contains("futuro") || lower.contains("passado") || lower.contains("espera") ->
                if (v == 0) "Há o tempo do relógio (mecânico e apressado) e há o *tempo vivo da maturação*. Nem tudo o que é fértil floresce na velocidade da ansiedade."
                else "O Tempo no mapa pede outro ritmo: algumas esferas amadurecem devagar, e apressá-las estraga a colheita."
            lower.contains("identidade") || lower.contains("eu") ->
                "A Identidade não é uma estátua pronta, mas uma narrativa viva em constante refazimento. Mudar de posição ou acolher novas dúvidas não ameaça quem você é; amplia sua inteireza."
            lower.contains("criatividade") || lower.contains("arte") || lower.contains("invenção") ->
                "Criar é traçar uma *linha de fuga* — encontrar uma saída inédita onde o hábito só enxergava repetição. Ela transforma a tensão das outras esferas em matéria-prima expressiva."
            lower.contains("silêncio") || lower.contains("calma") || lower.contains("vazio") || lower.contains("paz") ->
                "O Silêncio é o intervalo acústico e mental indispensável para que os pensamentos decantem. Sem espaço vazio entre as notas, não existe música—apenas ruído."
            lower.contains("cansaço") || lower.contains("sono") || lower.contains("exaustão") || lower.contains("repouso") ->
                "O Cansaço é a sabedoria biológica do corpo interrompendo o excesso de exigência cognitiva. Honrar o descanso não é abandonar seus projetos, é devolver solo fértil para eles."
            lower.contains("justiça") || lower.contains("equilíbrio") || lower.contains("ética") ->
                "Representa a justa medida e a ecologia interior: o compromisso de não ser injusto consigo mesmo enquanto tenta dar conta das demandas do mundo."
            lower.contains("raiva") || lower.contains("irritação") || lower.contains("irritacao") || lower.contains("fúria") || lower.contains("furia") ->
                "A Raiva é energia de fronteira: ela sinaliza onde sua potência foi contrariada. Em vez de reprimi-la ou obedecê-la cegamente, use-a como bússola — ela aponta exatamente o limite que precisa ser redesenhado."
            lower.contains("tristeza") || lower.contains("melancolia") || lower.contains("nostalgia") || lower.contains("saudade") ->
                "A Tristeza é a maré baixa da alma: expõe o que a euforia esconde. Honrá-la não é afundar-se nela, é permitir que ela lave e reorganize o terreno interno antes da próxima maré cheia."
            lower.contains("alegria") || lower.contains("euforia") || lower.contains("entusiasmo") || lower.contains("empolgação") || lower.contains("empolgacao") ->
                "A Alegria é a passagem a uma perfeição maior — o sinal mais confiável de que sua potência está aumentando. Ancore-se nela: registre onde ela mora no corpo para poder revisitá-la."
            lower.contains("amor") || lower.contains("carinho") || lower.contains("afeto") || lower.contains("ternura") ->
                "O Amor é a atenção tornada morada: aquilo que amamos, sustentamos. Na sua constelação, ele funciona como cola gravitacional — aproxima o que estava disperso e dá peso ao que importa."
            lower.contains("vergonha") || lower.contains("culpa") ->
                "Vergonha e Culpa são afetos do olhar — supõem um tribunal interno. A virada aqui: trocar o julgamento pela compreensão das causas. O que tem causa, tem manejo; o que tem manejo, deixa de ser sentença."
            lower.contains("esperança") || lower.contains("esperanca") || lower.contains("otimismo") ->
                "A Esperança é a imaginação trabalhando a seu favor: o cérebro preditivo ensaiando futuros habitáveis. Alimente-a com um próximo passo concreto, pequeno o bastante para ser cumprido hoje."
            lower.contains("ansiedade") || lower.contains("ansios") || lower.contains("angústia") || lower.contains("angustia") || lower.contains("aflição") || lower.contains("aflicao") ->
                "A Ansiedade é o sistema de alarme disparando antes da hora: o corpo antecipando cenários que ainda não existem. Acalmar o corpo primeiro (respiração, pés no chão) é mais eficaz que debater com os cenários."
            lower.contains("coragem") || lower.contains("ousadia") ->
                "A Coragem não é ausência de medo, é o medo atravessado com lucidez. Ela aparece no mapa como vetor de expansão: siga a direção para onde ela aponta, mesmo em passos pequenos."
            lower.contains("liberdade") || lower.contains("autonomia") ->
                "A Liberdade não é fazer qualquer coisa, é agir a partir da compreensão das próprias causas. Cada esfera mapeada com honestidade aumenta seu grau de liberdade real."
            lower.contains("gratidão") || lower.contains("gratidao") ->
                "A Gratidão é atenção treinada para o que sustenta: ela converte o fundo em figura. Praticá-la reconfigura o campo perceptivo — o mapa literalmente muda de cor quando ela entra."
            lower.contains("solidão") || lower.contains("solidao") || lower.contains("sozinho") ->
                "A Solidão tem duas faces: abandono ou recolhimento. No mapa de hoje, pergunte-se qual face está ativa — o recolhimento escolhido é laboratório; o abandono sentido pede ponte, nem que seja mínima, com o mundo."
            lower.contains("poder") || lower.contains("força") || lower.contains("forca") ->
                "O Poder aqui é potência, não domínio: a capacidade de produzir efeitos no mundo. Pergunte-se onde sua energia vaza e onde ela constrói — o mapa mostra os dois pontos."
            lower.contains("foco") || lower.contains("clareza") ->
                "A atenção seletiva que recolhe a energia dispersa e a conduz para um único ponto de presença lúcida."
            else -> {
                val cleanSentences = cleanExtract
                    ?.split(Regex("(?<=[.!?])\\s+"))
                    ?.filter { it.trim().length in 40..220 }
                    ?.take(2)
                    ?.joinToString(" ")
                    ?.trim()

                if (!cleanSentences.isNullOrBlank()) {
                    val closer = if (v == 0) " Na sua constelação subjetiva, este conceito funciona como uma metáfora viva que organiza suas percepções de hoje."
                    else " Traga essa definição para perto do corpo: onde ela vibra mais forte é onde mora o trabalho de hoje."
                    "$cleanSentences$closer"
                } else {
                    // Espelho em branco: sem verbete e sem balde pronto, o sentido
                    // nasce da posição, do peso e das vizinhanças — não do dicionário.
                    // Vale para QUALQUER termo: "Jangada", "Capivara", "Amnésia".
                    val dist = hypot(sphere.posX - 0.5f, sphere.posY - 0.5f)
                    val place = if (dist < 0.22f) "no centro do seu mapa — o assunto ao redor do qual todo o resto orbita hoje"
                    else "nas bordas do seu mapa — presente, mas mantida a uma distância que também diz algo"
                    val density = if (sphere.weightHeaviness >= 0.55f) "com peso denso, pedindo respeito ao ritmo"
                    else "com leveza, pedindo espaço para respirar"
                    if (v == 0) "**${sphere.name}** não tem verbete pronto — e isso é potência, não falta. Posicionada $place, $density, ela funciona como um *espelho em branco*: o sentido dela será decidido pelo que você fizer com ela, não pelo dicionário."
                    else "Nenhuma definição externa dá conta de **${sphere.name}** hoje — e tudo bem: símbolos vivos escapam de verbetes. Leia-a pela geometria: está $place, $density. Pergunte-se o que essa posição protege ou anuncia."
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
        valence: Float,
        seed: Long
    ): String {
        val lower = userText.lowercase()
        val rng = Random(seed + lower.length)
        val somaticContext = when {
            heavyCount >= 2 && arousal > 0.65f ->
                "Repare: esferas com peso acentuado e ritmo interno elevado (${(arousal * 100).toInt()}%) indicam que essa questão não está só nas ideias — vibra fisicamente no corpo. Comece pelo corpo, depois volte às ideias."
            heavyCount >= 2 ->
                "Com ${heavyCount} esferas densas no mapa, há matéria acumulada pedindo digestão lenta. Não force síntese rápida; deixe as camadas se assentarem."
            arousal > 0.65f ->
                "Seu ritmo interno em ${(arousal * 100).toInt()}% mostra aceleração: os pensamentos correm mais rápido que a compreensão. Desacelerar o corpo é o atalho mais curto para clarear a mente."
            valence < 0.35f ->
                "Com abertura em ${(valence * 100).toInt()}%, o sistema está em modo de proteção — e tudo bem. Leituras úteis agora são as que cabem em passos pequenos e seguros."
            else ->
                "Seu estado corporal mostra espaço de sobriedade e contemplação (${(valence * 100).toInt()}% de abertura), o que permite observar essa questão sem ser engolido pela pressa."
        }

        return when {
            lower.contains("dividido") || lower.contains("dúvida") || lower.contains("escolh") || lower.contains("entre") ->
                "Quando nos sentimos divididos, costumamos achar que uma parte de nós está errada e precisa vencer a outra. Mas a ambivalência raramente é fraqueza: ela acontece quando **duas verdades legítimas** estão pedindo passagem ao mesmo tempo. **$dominant** aponta para aquilo que quer crescer, enquanto **$secondary** protege o que você não quer perder. $somaticContext Em vez de exigir uma resposta definitiva sob pressão hoje, permita um pequeno experimento prático que honre os dois lados sem que um precise aniquilar o outro."

            lower.contains("projeto") || lower.contains("trabalho") || lower.contains("criar") || lower.contains("começar") || lower.contains("novo") ->
                "Todo gesto de criação ou mudança real esbarra na fronteira entre a expansão (**$dominant**) e a conservação (**$secondary**). Na neurociência do processamento preditivo, o cérebro humano prefere o conhecido—mesmo quando insatisfatório—porque o conhecido exige menos gasto energético. Por isso, o frio na barriga ou a hesitação antes de avançar não são sinais de que você não é capaz; são apenas o seu sistema nervoso recalibrando o mapa diante do inédito. $somaticContext Reduza a escala do próximo passo até que ele pareça um teste curioso, e não um salto no escuro."

            lower.contains("ansios") || lower.contains("medo") || lower.contains("preocup") || lower.contains("tenso") || lower.contains("cansad") ->
                "A tensão acumulada surge quando a mente tenta resolver no plano abstrato do futuro algo que o corpo está sentindo no presente. Quando **$dominant** e **$secondary** se cruzam sob pressão, o pensamento tende a girar em círculos procurando garantias absolutas antes de descansar. $somaticContext A virada de chave aqui é inverter a ordem: primeiro oferecer segurança sensorial ao corpo (soltando a vigilância muscular), para só depois deixar que a relação entre **$dominant** e **$secondary** se decante naturalmente."

            lower.contains("luto") || lower.contains("perda") || lower.contains("morte") || lower.contains("partiu") || lower.contains("saudade") ->
                "A perda reorganiza o mapa inteiro: **$dominant** tenta seguir adiante enquanto **$secondary** guarda o vínculo. O luto não é um problema a resolver, é um território a atravessar — e atravessar leva o tempo da *duração interior*, não o do relógio. $somaticContext Permita-se rituais pequenos e repetidos de despedida: eles dão ao corpo as marcas de passagem que a mente sozinha não consegue produzir."

            lower.contains("raiva") || lower.contains("irritad") || lower.contains("conflito") || lower.contains("briga") || lower.contains("injust") ->
                "A raiva acusa onde a potência foi contrariada — e nisso ela é informante preciosa, não inimiga. Pergunte o que **$dominant** está defendendo e o que **$secondary** teme perder se o limite for redesenhado. $somaticContext Traduza a energia em frase-limite clara e concreta antes de qualquer conversa difícil: raiva com endereço vira negociação; sem endereço, vira incêndio."

            lower.contains("vergonha") || lower.contains("culpa") || lower.contains("errei") || lower.contains("arrepend") ->
                "Culpa e vergonha supõem um tribunal interno em sessão permanente. Mas todo tribunal pode ser revisto: troque o veredito pela autópsia das causas — o que faltava, o que sobrava, o que era impossível saber então. $somaticContext Entre **$dominant** e **$secondary** há material para reparação concreta, que dissolve culpa melhor que qualquer autoflagelo: um gesto, um pedido, um ajuste de rota."

            lower.contains("apatia") || lower.contains("sentido") || lower.contains("vazio") || lower.contains("desânimo") || lower.contains("desanimo") || lower.contains("propósito") || lower.contains("proposito") ->
                "A apatia raramente é falta de vontade — costuma ser excesso de exigência internalizada paralisando a potência de agir. Quando tudo pesa igual, nada se move. $somaticContext Reduza a unidade de ação até o ridículo (cinco minutos, um telefonema, uma caminhada no quarteirão): **$dominant** reacende por atrito com o real, não por deliberação abstrata. **$secondary** pode guardar o sentido maior enquanto isso."

            lower.contains("eufóri") || lower.contains("eufori") || lower.contains("empolgad") || lower.contains("decisão") || lower.contains("decisao") || lower.contains("oportunidade") ->
                "A expansão tem sua própria vertigem: **$dominant** puxa para o salto enquanto **$secondary** pede lastro. Nem freio total, nem voo cego — a sabedoria aqui é projetar o paraquedas antes de pular: defina o menor experimento reversível que testa a oportunidade. $somaticContext Euforia com método vira conquista; sem método, vira ressaca."

            lower.contains("ciúme") || lower.contains("ciume") || lower.contains("comparação") || lower.contains("comparacao") || lower.contains("inveja") ->
                "A comparação mede seu bastidor com o palco alheio — jogo em que sempre se perde. O que **$dominant** inveja no outro é, no fundo, um desejo próprio ainda sem endereço. $somaticContext Devolva a pergunta a si: se ninguém estivesse olhando, o que **$secondary** escolheria cultivar? A resposta a essa pergunta é o seu norte, não o feed alheio."

            else -> {
                val genericOpeners = listOf(
                    "Aquilo que você expressou revela como **$dominant** e **$secondary** estão tecendo um diálogo silencioso nos bastidores da sua atenção.",
                    "Por trás das suas palavras, **$dominant** e **$secondary** disputam a cena — e essa disputa, bem lida, é um mapa, não um veredito.",
                    "O que você trouxe mostra **$dominant** em primeiro plano com **$secondary** operando no fundo, como baixo-contínuo de uma peça maior."
                )
                genericOpeners[rng.nextInt(genericOpeners.size)] + " Muitas vezes, tratamos nossos estados internos como problemas a serem resolvidos rapidamente, quando na verdade eles são **paisagens a serem compreendidas**. $somaticContext Ao externalizar essas esferas na tela e dar a cada uma sua cor, seu peso e seu lugar exato no espaço, você já deixou de estar misturado à névoa do sentimento e assumiu o lugar de quem observa com lucidez e gentileza."
            }
        }
    }

    /**
     * Modular somatic practice composer: combines validated body techniques with
     * metaphors drawn from the user's spheres. Modules are seeded per reading,
     * so each reading assembles an unprecedented 3-step combination.
     */
    private fun breathingModule(variant: Int, primary: String, arousal: Float): String {
        val slow = arousal < 0.45f
        return when (variant) {
            0 -> "**Respiração de Tônus Vagal (4s/6s):** inspire pelo nariz em 4s acolhendo **$primary**; solte pela boca em 6s deixando o peso escorrer para o chão." + if (slow) " Repita 5 ciclos em ritmo de maré." else " Repita 3 ciclos, depois 1 suspiro longo de descarga."
            1 -> "**Respiração Diafragmática com Mãos no Ventre:** deite as mãos sobre o abdômen e encha-o como um balão em 5s; esvazie em 5s. 6 ciclos sentindo **$primary** subir e descer com a maré do ar."
            2 -> "**Respiração Quadrada de Estabilização:** inspire 4s, segure 4s, solte 4s, pause 4s — desenhando mentalmente um quadrado. 4 voltas para dar contorno firme a **$primary**."
            else -> "**Exalação Alongada de Descarga:** inspire normal pelo nariz e solte pela boca o dobro do tempo, com um suspiro audível no final. 5 vezes, imaginando **$primary** perdendo voltagem a cada ciclo."
        }
    }

    private fun gazeModule(variant: Int, primary: String, secondary: String): String {
        return when (variant) {
            0 -> "**Descompressão do Olhar (Visão Periférica):** fixando um ponto suave entre **$primary** e **$secondary**, abra a atenção para as bordas do campo visual sem mover a cabeça. 60 segundos: sair da visão em túnel desliga o alerta simpático."
            1 -> "**Varredura Lenta do Horizonte:** gire apenas os olhos da esquerda à direita, bem devagar, como quem lê o horizonte. 6 travessias completas, deixando **$primary** desfocar e voltar — treino de flexibilidade atencional."
            else -> "**Piscar Consciente e Foco Macio:** pisque devagar 10 vezes e repouse o olhar num ponto distante (janela, parede). A cada piscada, solte um microtensão ligada a **$secondary**."
        }
    }

    private fun tactileModule(variant: Int, primary: String, secondary: String, valence: Float): String {
        val gentle = valence < 0.45f
        return when (variant) {
            0 -> "**Enraizamento Postural 5-4-3-2-1:** nomeie 5 coisas que vê, 4 que toca, 3 que ouve, 2 que cheira, 1 que saboreia — ancorando **$primary** no presente sensorial." + if (gentle) " Faça sentado, sem pressa." else " Faça em pé, sentindo os pés no chão a cada item."
            1 -> "**Relaxamento Muscular Progressivo (ombros–mandíbula–mãos):** tensione ombros por 5s e solte; cerre a mandíbula 5s e solte com a língua solta; aperte os punhos 5s e abra. Despeje **$secondary** junto com cada soltura."
            2 -> "**Auto-toque de Contenção:** cruze os braços e segure os próprios ombros com pressão firme por 30s, respirando fundo — oferecendo a **$primary** a contenção que o sistema pede."
            else -> "**Varredura Corporal de 60 Segundos:** percorra mentalmente pés→pernas→ventre→peito→rosto, nomeando onde **$secondary** mora hoje e soltando só aquele ponto com uma exalação."
        }
    }

    private fun listeningModule(variant: Int, primary: String, secondary: String, focus: Float): String {
        val deep = focus > 0.6f
        return when (variant) {
            0 -> "**Escuta Harmônica Guiada:** feche os olhos por " + if (deep) "90" else "45" + " segundos ouvindo apenas a paisagem pentatônica do app; a cada nota, imagine **$primary** e **$secondary** trocando de lugar no espaço."
            1 -> "**Batida e Nomeação:** bata de leve dois dedos no peito em ritmo lento e nomeie em voz baixa o que sente a cada 4 batidas — dando a **$primary** um ritmo externo para se organizar."
            else -> "**Silêncio Compartilhado:** desligue-se de tudo por 1 minuto e conte apenas as respirações até 10, recomeçando ao se perder. Cada recomeço é **$secondary** sendo reintegrado sem julgamento."
        }
    }

    private fun buildDeepSomaticPractice(
        languageCode: String,
        arousal: Float,
        valence: Float,
        focus: Float,
        concepts: List<String>,
        topology: SpatialTopologyAnalysis,
        userPrompt: String,
        seed: Long
    ): String {
        val primary = topology.dominantSphere?.name ?: concepts.firstOrNull() ?: "Harmonia"
        val secondary = topology.closestAffinityPair?.second?.name
            ?: concepts.getOrNull(1)
            ?: "Presença"

        if (languageCode.lowercase() != "pt") {
            val lang = languageCode.lowercase()
            val rng = Random(seed)
            val breath = listOf(
                L(lang, "Inspire 4s pelo nariz acolhendo **$primary**; solte 6s pela boca deixando o peso assentar.",
                    "Inhale 4s through the nose welcoming **$primary**; exhale 6s through the mouth letting the weight sink.",
                    "Inhala 4s por la nariz acogiendo **$primary**; exhala 6s por la boca dejando que el peso baje.",
                    "Inspirez 4s par le nez en accueillant **$primary** ; expirez 6s par la bouche en laissant le poids descendre."),
                L(lang, "Respiração abdominal com as mãos no abdômen, 5s inspira / 5s expira, 6 ciclos.",
                    "Belly breathing with hands on the abdomen, 5s in / 5s out, 6 rounds.",
                    "Respiración abdominal con las manos en el abdomen, 5s dentro / 5s fuera, 6 ciclos.",
                    "Respiration abdominale, mains sur le ventre, 5s inspir / 5s expir, 6 cycles."),
                L(lang, "Respiração quadrada 4-4-4-4 por 4 ciclos para dar contorno firme a **$primary**.",
                    "Box breathing 4-4-4-4 for 4 rounds to give **$primary** a firm contour.",
                    "Respiración cuadrada 4-4-4-4 durante 4 ciclos para dar contorno firme a **$primary**.",
                    "Respiration carrée 4-4-4-4 pendant 4 cycles pour donner un contour net à **$primary**.")
            )
            val body = listOf(
                L(lang, "Suavize o olhar pelas bordas do ambiente por 60s sem mover a cabeça.",
                    "Soften your gaze to the room edges for 60s without moving your head.",
                    "Suaviza la mirada hacia los bordes de la sala durante 60s sin mover la cabeza.",
                    "Adoucissez le regard vers les bords de la pièce pendant 60s sans bouger la tête."),
                L(lang, "Soltura progressiva: ombros 5s tensos e relaxados, relaxe a mandíbula, abra as mãos.",
                    "Progressive release: shoulders 5s tense-and-let-go, jaw unclench, fists open.",
                    "Liberación progresiva: hombros 5s tensos y suelta, relaja la mandíbula, abre las manos.",
                    "Relâchement progressif : épaules 5s tendues puis relâchées, mâchoire desserrée, mains ouvertes."),
                L(lang, "Ancoragem sensorial 5-4-3-2-1: nomeie 5 vistas, 4 tocadas, 3 ouvidas, 2 cheiradas, 1 saboreada.",
                    "5-4-3-2-1 sensory grounding: name 5 seen, 4 touched, 3 heard, 2 smelled, 1 tasted.",
                    "Anclaje sensorial 5-4-3-2-1: nombra 5 vistas, 4 tocadas, 3 oídas, 2 olidas, 1 saboreada.",
                    "Ancrage sensoriel 5-4-3-2-1 : nommez 5 choses vues, 4 touchées, 3 entendues, 2 senties, 1 goûtée.")
            )
            return buildString {
                appendLine(
                    L(lang,
                        "**Reconfiguração Corporal em 3 Passos ($primary · $secondary):**",
                        "**3-Step Embodied Reconfiguration ($primary · $secondary):**",
                        "**Reconfiguración Corporal en 3 Pasos ($primary · $secondary):**",
                        "**Reconfiguration Corporelle en 3 Étapes ($primary · $secondary) :**")
                )
                appendLine(L(lang, "- **1. Respiração:** ${breath[rng.nextInt(breath.size)]}",
                    "- **1. Breath:** ${breath[rng.nextInt(breath.size)]}",
                    "- **1. Respiración:** ${breath[rng.nextInt(breath.size)]}",
                    "- **1. Respiration :** ${breath[rng.nextInt(breath.size)]}"))
                appendLine(L(lang, "- **2. Corpo:** ${body[rng.nextInt(body.size)]}",
                    "- **2. Body:** ${body[rng.nextInt(body.size)]}",
                    "- **2. Cuerpo:** ${body[rng.nextInt(body.size)]}",
                    "- **2. Corps :** ${body[rng.nextInt(body.size)]}"))
                appendLine(
                    L(lang,
                        "- **3. Escuta:** Feche os olhos por 45 segundos seguindo apenas a harmonia de fundo, imaginando **$primary** e **$secondary** trocando de lugar.",
                        "- **3. Listening:** Close your eyes for 45 seconds following only the background harmony, imagining **$primary** and **$secondary** trading places.",
                        "- **3. Escucha:** Cierra los ojos 45 segundos siguiendo solo la armonía de fondo, imaginando **$primary** y **$secondary** intercambiando lugares.",
                        "- **3. Écoute :** Fermez les yeux 45 secondes en suivant seulement l'harmonie de fond, en imaginant **$primary** et **$secondary** échanger leurs places.")
                )
            }.trim()
        }

        val rng = Random(seed + primary.length * 31L + secondary.length)
        return buildString {
            appendLine("**Prática Inédita em 3 Passos ($primary ↔ $secondary):**")
            appendLine("- **1. " + breathingModule(rng.nextInt(4), primary, arousal))
            appendLine("- **2. " + listOf(
                gazeModule(rng.nextInt(3), primary, secondary),
                tactileModule(rng.nextInt(4), primary, secondary, valence)
            )[rng.nextInt(2)])
            appendLine("- **3. " + listeningModule(rng.nextInt(3), primary, secondary, focus))
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
