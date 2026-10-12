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
    val subtitleOrAuthors: String,
    val snippet: String,
    val url: String
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Queries live Wikipedia + Crossref academic abstracts for EACH individual concept
     * on the user's canvas (e.g. "Jaguatirica", "Arquitetura", "Medo") so the Built-in Engine
     * and BYOK models always weave real, empirical, concept-specific properties into the reading.
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

            val normalizedConcepts = queryConcepts.map { formatConceptTitleCase(it) }.distinct().take(4)
            val promptKeywords = extractKeywords(userPrompt).map { formatConceptTitleCase(it) }
            val allTerms = (normalizedConcepts + promptKeywords).distinct().take(4)

            val wikiDeferreds = allTerms.map { term ->
                async { fetchWikipediaSummaryAndSearch(term, wikiLang) }
            }
            val academicDeferred = async {
                fetchAcademicWorks(allTerms.joinToString(" ").ifBlank { "Perception Consciousness Emotion" })
            }

            val wikiList = wikiDeferreds.flatMap { it.await() }.distinctBy { it.title }.take(5)
            val acadList = academicDeferred.await()

            GroundingBundle(
                wikiSources = wikiList,
                academicSources = acadList
            )
        }
    }

    private fun fetchWikipediaSummaryAndSearch(term: String, lang: String): List<GroundingSource> {
        return runCatching {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=2"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/2.0 (Android)")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                val searchArr = JSONObject(body)
                    .optJSONObject("query")
                    ?.optJSONArray("search") ?: return@runCatching emptyList()

                val results = mutableListOf<GroundingSource>()
                for (i in 0 until searchArr.length()) {
                    val item = searchArr.getJSONObject(i)
                    val title = item.optString("title", "")
                    val rawSnippet = item.optString("snippet", "")
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&#039;", "'")
                        .trim()
                    if (title.isNotBlank() && rawSnippet.isNotBlank()) {
                        results.add(
                            GroundingSource(
                                sourceType = "WIKIPEDIA",
                                title = formatConceptTitleCase(title),
                                subtitleOrAuthors = term,
                                snippet = rawSnippet,
                                url = ""
                            )
                        )
                    }
                }
                results
            }
        }.getOrElse { emptyList() }
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
                                subtitleOrAuthors = "",
                                snippet = rawAbstract,
                                url = ""
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
     * Strict 2-tier orchestration (Items 10, 12, 13, 14):
     * 1. Option 1: User's configured BYOK OpenAI-compatible provider (if active).
     * 2. Option 2: Built-in Web-Grounded Synthesizer that actively weaves the live Wikipedia
     *    and Crossref definitions of ANY concept (even "Jaguatirica", "Arquitetura", etc.)
     *    with the user's sphere proximities, color temperatures, weights, and notes,
     *    generating fresh, non-repetitive practical sensory exercises every time.
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

        onStageProgress("Buscando referências reais sobre ${titleCasedConcepts.take(3).joinToString(", ")}...")
        val grounding = fetchLiveGrounding(
            queryConcepts = titleCasedConcepts,
            userPrompt = userPrompt,
            languageCode = languageCode
        )

        val silentBackgroundNotes = (grounding.wikiSources + grounding.academicSources)
            .joinToString("\n") { "- ${it.title}: ${it.snippet}" }

        // Option 1: BYOK Provider (if configured by user)
        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            onStageProgress("Consultando ${activeProviderName ?: "BYOK"} ($activeModelId)...")
            val systemPrompt = """
                Você é um guia criativo e acolhedor de autoconhecimento no Noesis Lab.
                $languageInstruction
                
                REGRAS IMPORTANTES:
                1. Escreva sempre os nomes de estados, conceitos e emoções com a Primeira Letra Maiúscula (ex: ${titleCasedConcepts.joinToString(", ")}).
                2. Seja claro, humano, envolvente e acessível. Não use jargões acadêmicos pesados.
                3. Integre a interseção entre as esferas da Aba 1 ($particlesSummary), o ritmo atual do usuário (Ritmo=${(arousal * 100).toInt()}%, Abertura=${(valence * 100).toInt()}%, Foco=${(focus * 100).toInt()}%), o que ele escreveu e as notas de pesquisa sobre cada conceito.
                4. Ao final, separe com "---SOMATIC---" um experimento prático sensorial inédito e criativo conectado especificamente às características dos conceitos presentes (${titleCasedConcepts.joinToString(", ")}).
            """.trimIndent()

            val userMessage = """
                Esferas desenhadas na Aba 1: $particlesSummary
                Texto do usuário: $userPrompt
                (Pesquisa de bastidores em tempo real sobre os termos: $silentBackgroundNotes)
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
                        grounding = grounding,
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

        // Option 2: Built-in Web-Grounded Engine (Default for free users)
        onStageProgress("Sintetizando leitura contextual e experimento sensorial...")
        val synthesized = synthesizeWebGroundedNarrative(
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
            grounding = grounding,
            userPrompt = userPrompt
        )

        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Motor Interno + Pesquisa Web",
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
                put("temperature", 0.75)
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
     * Synthesizes a rich, accessible, markdown-formatted reading that actively incorporates
     * the live Wikipedia/Web discoveries about every custom sphere (e.g., "Jaguatirica", "Arquitetura")
     * and the exact slider weights/colors.
     */
    private fun synthesizeWebGroundedNarrative(
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
        val conceptsText = conceptsList.joinToString(", ")
        val firstConcept = conceptsList.firstOrNull() ?: "Desejo"
        val secondConcept = conceptsList.getOrNull(1) ?: "Tempo"

        // Build concept-specific insights from live Wikipedia snippets
        val webInsightsBullets = grounding.wikiSources.take(3).mapNotNull { src ->
            val cleanSnippet = src.snippet.take(200).trimEnd('.', ' ') + "."
            "- **A Presença de ${formatConceptTitleCase(src.subtitleOrAuthors.ifBlank { src.title })} no Seu Mapa:** Na natureza e na cultura, *${src.title}* traz a ideia de que *\"$cleanSnippet\"* Quando você coloca **${formatConceptTitleCase(src.subtitleOrAuthors.ifBlank { src.title })}** como uma esfera viva hoje, sua mente está emprestando exatamente essa qualidade para traduzir o que você sente."
        }.joinToString("\n\n")

        return when (languageCode.lowercase()) {
            "en" -> buildString {
                appendLine("### Your Current State Synthesis: $conceptsText")
                appendLine("By combining the spheres you arranged in Tab 1 ($particlesSummary) with your reflection (*\"$userPrompt\"*), your mental space right now is shaped by the encounter between **$conceptsText**.")
                appendLine()
                if (grounding.wikiSources.isNotEmpty()) {
                    appendLine("### How Each Concept Shapes Your Moment")
                    grounding.wikiSources.take(3).forEach { src ->
                        val term = formatConceptTitleCase(src.subtitleOrAuthors.ifBlank { src.title })
                        appendLine("- **The Role of $term:** Drawing from real-world context (*${src.snippet.take(180)}...*), placing **$term** in your field acts as a living metaphor for how your attention is moving today.")
                    }
                    appendLine()
                }
                appendLine("### The Bridge Between $firstConcept and $secondConcept")
                appendLine("With your inner pace at **${(arousal * 100).toInt()}%** and emotional openness at **${(valence * 100).toInt()}%**, notice how **$firstConcept** and **$secondConcept** blend their colors and tones on your canvas. Rather than pulling you in opposite directions, their proximity creates a third, creative space where both can coexist without pressure.")
            }.trim()

            "es" -> buildString {
                appendLine("### Síntesis de tu Estado Actual: $conceptsText")
                appendLine("Al unir el mapa de esferas de la Pestaña 1 ($particlesSummary) con tu reflexión (*\"$userPrompt\"*), tu espacio interior hoy nace del diálogo entre **$conceptsText**.")
                appendLine()
                if (grounding.wikiSources.isNotEmpty()) {
                    appendLine("### El Significado Vivo de tus Esferas")
                    grounding.wikiSources.take(3).forEach { src ->
                        val term = formatConceptTitleCase(src.subtitleOrAuthors.ifBlank { src.title })
                        appendLine("- **La Energía de $term:** Inspirado en su naturaleza real (*${src.snippet.take(180)}...*), traer **$term** a tu mapa revela una cualidad única que tu mente está buscando expresar hoy.")
                    }
                    appendLine()
                }
                appendLine("### El Encuentro entre $firstConcept y $secondConcept")
                appendLine("Con un ritmo interno del **${(arousal * 100).toInt()}%** y una apertura del **${(valence * 100).toInt()}%**, permite que **$firstConcept** y **$secondConcept** mezclen sus matices sin forzar una respuesta inmediata.")
            }.trim()

            "fr" -> buildString {
                appendLine("### Synthèse de Votre État Actuel : $conceptsText")
                appendLine("En reliant vos sphères de l'Onglet 1 ($particlesSummary) à votre réflexion (*\"$userPrompt\"*), votre espace mental fait dialoguer **$conceptsText**.")
                appendLine()
                if (grounding.wikiSources.isNotEmpty()) {
                    appendLine("### La Résonance de Vos Concepts")
                    grounding.wikiSources.take(3).forEach { src ->
                        val term = formatConceptTitleCase(src.subtitleOrAuthors.ifBlank { src.title })
                        appendLine("- **La Présence de $term :** En s'appuyant sur sa réalité (*${src.snippet.take(180)}...*), **$term** agit comme une métaphore vivante dans votre état d'esprit.")
                    }
                    appendLine()
                }
                appendLine("### L'Alliance entre $firstConcept et $secondConcept")
                appendLine("Avec un rythme intérieur à **${(arousal * 100).toInt()}%** et une ouverture à **${(valence * 100).toInt()}%**, laissez **$firstConcept** et **$secondConcept** fusionner leurs couleurs et leurs notes.")
            }.trim()

            else -> buildString {
                appendLine("### O Desenho do Seu Estado Atual: $conceptsText")
                appendLine("Ao cruzar a configuração das esferas que você posicionou na Aba 1 ($particlesSummary) com o seu momento (*\"$userPrompt\"*), vemos uma conversa direta entre **$conceptsText**.")
                appendLine()
                if (webInsightsBullets.isNotBlank()) {
                    appendLine("### O Que Cada Conceito Traz Para a Sua Mente Hoje")
                    appendLine(webInsightsBullets)
                    appendLine()
                }
                appendLine("### A Mistura de Cores e Sons entre $firstConcept e $secondConcept")
                appendLine("- **Afinidade e Contraste:** Quando **$firstConcept** e **$secondConcept** compartilham o mesmo campo, a cor quente daquilo que é mais urgente encontra o tom suave do que traz respiro. Você não precisa escolher apenas um lado dessa mistura.")
                appendLine("- **Sintonia Corporal (${(arousal * 100).toInt()}% de Ritmo · ${(valence * 100).toInt()}% de Abertura · ${(focus * 100).toInt()}% de Foco):** A configuração que você desenhou mostra que sua mente está buscando transformar peso acumulado em movimento criativo e clareza.")
            }.trim()
        }
    }

    /**
     * Generates a truly unique, non-repetitive sensory/somatic experiment every time (Item 13),
     * tailored to the specific concepts, Wikipedia imagery, and slider combination.
     */
    private fun buildDynamicWebGroundedSomaticPractice(
        languageCode: String,
        arousal: Float,
        valence: Float,
        focus: Float,
        concepts: List<String>,
        grounding: GroundingBundle,
        userPrompt: String
    ): String {
        val primary = concepts.firstOrNull() ?: "Harmonia"
        val secondary = concepts.getOrNull(1) ?: "Presença"
        val wikiHint = grounding.wikiSources.firstOrNull()?.snippet?.take(95)?.trimEnd('.', ',') ?: ""

        // Deterministic yet varied seed based on current concepts + sliders + prompt + timestamp bucket
        val variationSeed = abs((concepts.joinToString() + userPrompt + (arousal * 10).toInt() + (valence * 10).toInt() + (System.currentTimeMillis() / 2000)).hashCode()) % 6

        if (languageCode.lowercase() != "pt") {
            return when (variationSeed) {
                0 -> "Close your eyes for 20 seconds and listen to the harmonic chord generated by **$primary** and **$secondary**. Inhale imagining the cooler blue hue entering your lungs, and exhale letting the warmer red tension melt down through your shoulders."
                1 -> "Look at the color gradient connecting **$primary** and **$secondary** on your screen. Gently press your thumb and index finger together as you inhale for 4 seconds, then release the pressure completely as you exhale for 6 seconds."
                2 -> "Place one hand over your chest and notice the rhythm of your breath (${(arousal * 100).toInt()}% pace). Picture **$primary** transforming from a heavy solid object into a warm, flowing wave of light."
                3 -> "Soften your gaze so you see the whole screen at once without focusing on a single point. Let the sound of **$primary** and **$secondary** blend in your ears while you drop your jaw and relax your tongue."
                4 -> "Take three slow breaths. On the first, acknowledge **$primary**; on the second, welcome **$secondary**; on the third, feel the open space between them expanding inside your torso."
                else -> "Feel the temperature of your hands holding the device. Imagine the qualities of **$primary** and **$secondary** balancing each other like two musical notes forming a calm, steady chord."
            }
        }

        val hintClause = if (wikiHint.isNotBlank()) " — evocando a essência de *\"$wikiHint...\"* —" else ""

        return when (variationSeed) {
            0 -> "**Experimento de Fusão Cromática ($primary + $secondary):** Olhe para o degradê de luz que une **$primary** e **$secondary** no seu mapa$hintClause. Inspire pelo nariz por 4 segundos imaginando a cor mais fria refrescando sua testa, e solte o ar pela boca por 6 segundos sentindo a cor mais quente aquecer e relaxar o centro do peito."
            1 -> "**Experimento de Descompressão Tátil ($primary):** Com os olhos semiabertos ouvindo o acorde modular ao fundo, toque suavemente a ponta do polegar na ponta do indicador. Enquanto inspira, sinta a importância que você deu a **$primary**; ao soltar o ar devagar, afaste os dedos milímetro por milímetro, soltando qualquer peso ligado a **$secondary**."
            2 -> "**Experimento de Ressonância Auditiva ($primary & $secondary):** Feche os olhos por 25 segundos e tente identificar as duas notas principais que estão se somando na música agora. Uma representa **$primary** e a outra **$secondary**$hintClause. Perceba como elas não brigam no ar — deixe sua respiração seguir o pulso lento desse acorde."
            3 -> "**Experimento de Visão Periférica e Espaço Interno:** Sem mover a cabeça, amplie seu olhar para enxergar os quatro cantos da tela ao mesmo tempo. Enquanto faz isso, imagine que a distância física entre **$primary** e **$secondary** se abre também dentro da sua caixa torácica, criando um espaço amplo, calmo e arejado."
            4 -> "**Experimento de Gravidade Zero ($primary):** Apoie os pés ou as costas confortavelmente e solte o peso da mandíbula. Em três respirações fundas, imagine que a esfera **$primary**$hintClause transfere sua melhor qualidade para o seu corpo, enquanto toda a rigidez se dissolve no som ambiente."
            else -> "**Experimento de Pulso em Três Tempos ($primary · $secondary):** Na primeira inspiração, acolha a presença de **$primary** sem julgamento. Na segunda inspiração, perceba como **$secondary** complementa seu momento atual. Na terceira expiração longa, solte os ombros e sinta o equilíbrio entre ambas."
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

    private fun extractKeywords(prompt: String): List<String> {
        val stopWords = setOf(
            "como", "para", "que", "uma", "com", "dos", "das", "por", "mais", "entre",
            "sobre", "quando", "onde", "qual", "isso", "este", "esta", "meu", "minha",
            "estou", "sentindo", "muito", "pouco", "hoje", "agora",
            "the", "and", "for", "with", "from", "that", "this", "what", "how", "into"
        )
        return prompt
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 4 && it.lowercase() !in stopWords }
            .take(3)
    }
}
