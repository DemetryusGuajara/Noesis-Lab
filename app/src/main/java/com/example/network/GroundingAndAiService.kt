package com.example.network

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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches theoretical context silently in the background from Wikipedia and Crossref
     * so the AI can weave rich insights without exposing academic citations or jargon to the user.
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

            val searchTerms = (queryConcepts.take(2) + extractKeywords(userPrompt)).distinct().take(3)
            val combinedSearch = if (searchTerms.isNotEmpty()) {
                searchTerms.joinToString(" ")
            } else {
                "Emotions Perception Creativity"
            }

            val wikiDeferred = async { fetchWikipediaArticles(combinedSearch, wikiLang) }
            val academicDeferred = async { fetchAcademicWorks(combinedSearch) }

            GroundingBundle(
                wikiSources = wikiDeferred.await(),
                academicSources = academicDeferred.await()
            )
        }
    }

    private fun fetchWikipediaArticles(query: String, lang: String): List<GroundingSource> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=2"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/1.0 (Android)")
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
                        .trim()
                    if (title.isNotBlank()) {
                        results.add(
                            GroundingSource(
                                sourceType = "WIKIPEDIA",
                                title = title,
                                subtitleOrAuthors = "Wikipedia",
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
            val scholarlyQuery = "$query psychology perception"
            val encoded = URLEncoder.encode(scholarlyQuery, "UTF-8")
            val url = "https://api.crossref.org/works?query=$encoded&rows=2&select=title,abstract"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/1.0")
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
                        .take(180)
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
     * Orchestrates an accessible, warm, deeply insightful reading that combines:
     * 1. The visual map of spheres from Tab 1 (emotions + abstract concepts + their weights & distances)
     * 2. The user's current body/feeling sliders
     * 3. The optional text written in Tab 3
     * Background research from Wikipedia & academic literature informs the depth silently, NEVER shown raw to the user.
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
        activeModelId: String?
    ): OrchestratedSimulationResult = withContext(Dispatchers.IO) {
        // Silent background enrichment (never displayed in UI)
        val grounding = fetchLiveGrounding(
            queryConcepts = conceptNames,
            userPrompt = userPrompt,
            languageCode = languageCode
        )

        val silentBackgroundNotes = (grounding.wikiSources + grounding.academicSources)
            .joinToString("\n") { "- ${it.title}: ${it.snippet}" }

        val systemPrompt = """
            Você é um guia criativo e acolhedor de autoconhecimento no Noesis Lab.
            $languageInstruction
            
            REGRAS IMPORTANTES DE ESTILO:
            1. Seja claro, humano, envolvente e acessível para qualquer pessoa. PROIBIDO usar jargões acadêmicos difíceis (não cite nomes de filósofos, não use termos como 'autopoiético', 'tectônica psíquica', 'svabhava', 'inferência bayesiana' ou citações da Wikipédia).
            2. A sua resposta deve integrar a INTERSEÇÃO entre:
               - O desenho que o usuário montou na Aba 1 (as esferas de emoções e conceitos ativos: $particlesSummary)
               - O ritmo interno atual do usuário (Ritmo=${(arousal * 100).toInt()}%, Abertura=${(valence * 100).toInt()}%, Foco=${(focus * 100).toInt()}%)
               - O que ele escreveu na caixa de texto.
            3. Use as referências silenciosas de bastidores apenas como inspiração invisível para criar metáforas bonitas, simples e reveladoras sobre o estado atual da pessoa.
            4. Ao final, separe com "---SOMATIC---" um exercício prático e gostoso de 30 segundos (respiração, visualização de cor ou percepção corporal) para a pessoa experimentar agora.
        """.trimIndent()

        val userMessage = """
            Esferas desenhadas pelo usuário na Aba 1: $particlesSummary
            Contexto ou pergunta escrita pelo usuário: $userPrompt
            (Notas silenciosas de bastidores para inspiração: $silentBackgroundNotes)
        """.trimIndent()

        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            val cleanBase = normalizeOpenAiBaseUrl(activeBaseUrl)
            val payload = JSONObject().apply {
                put("model", activeModelId)
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
                put("temperature", 0.7)
            }

            val aiResult = runCatching {
                val reqBuilder = Request.Builder()
                    .url("$cleanBase/chat/completions")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                if (!activeApiKey.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${activeApiKey.trim()}")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bodyStr = resp.body?.string() ?: return@runCatching null
                    val content = JSONObject(bodyStr)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                    content?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()

            if (aiResult != null) {
                val parts = aiResult.split("---SOMATIC---")
                val mainNarrative = parts[0].trim()
                val somatic = if (parts.size > 1) parts[1].trim() else buildLocalizedSomaticShift(languageCode, arousal, valence, conceptNames)
                return@withContext OrchestratedSimulationResult(
                    narrativeMarkdown = mainNarrative,
                    somaticShiftSuggestion = somatic,
                    providerLabel = "${activeProviderName ?: "BYOK"} · $activeModelId",
                    grounding = grounding
                )
            }
        }

        val synthesized = synthesizeAccessibleNarrativeLocally(
            userPrompt = userPrompt,
            conceptNames = conceptNames,
            arousal = arousal,
            valence = valence,
            focus = focus,
            languageCode = languageCode
        )
        val somatic = buildLocalizedSomaticShift(languageCode, arousal, valence, conceptNames)

        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Noesis Creative Engine",
            grounding = grounding
        )
    }

    private fun synthesizeAccessibleNarrativeLocally(
        userPrompt: String,
        conceptNames: List<String>,
        arousal: Float,
        valence: Float,
        focus: Float,
        languageCode: String
    ): String {
        val conceptsText = if (conceptNames.isNotEmpty()) conceptNames.joinToString(", ") else "Desejo, Medo, Tempo"
        val firstConcept = conceptNames.firstOrNull() ?: "Desejo"
        val secondConcept = conceptNames.getOrNull(1) ?: "Tempo"

        return when (languageCode.lowercase()) {
            "en" -> """
                ### What Your Mix of Ideas Reveals ($conceptsText)
                Looking at the canvas you shaped in Tab 1 alongside what you shared (*"$userPrompt"*), your mind right now is weaving together **$conceptsText**.
                
                Notice how **$firstConcept** and **$secondConcept** aren't isolated—they are pulling on each other like colors blending in water:
                - **The Conversation Between Your Spheres:** When you place concepts like these side by side, one acts as an anchor while the other acts as a spark of curiosity. Instead of fighting what feels uncertain, your mind is trying to build a bridge between them.
                - **Your Current Rhythm (${(arousal * 100).toInt()}% Pace · ${(valence * 100).toInt()}% Openness):** Because your energy is at ${(arousal * 100).toInt()}%, you don't need to force an immediate answer. Give yourself permission to treat this state like an artist testing colors on a canvas—every concept you added has a valid reason to be here today.
            """.trimIndent()

            "es" -> """
                ### Lo que Revela tu Mezcla Actual ($conceptsText)
                Al unir el mapa de esferas que armaste en la Pestaña 1 con tu reflexión (*"$userPrompt"*), vemos cómo **$conceptsText** están dialogando dentro de ti en este momento.
                
                - **El Encuentro entre $firstConcept y $secondConcept:** Ninguna de estas ideas está sola. Cuando se acercan en tu espacio mental, lo que parecía una duda o tensión se convierte en terreno fértil para mirar tu momento desde otro ángulo.
                - **Tu Clima Interior (${(arousal * 100).toInt()}% Ritmo · ${(valence * 100).toInt()}% Apertura):** No hace falta resolver todo de golpe; observa cómo cada esfera aporta un matiz distinto a tu día.
            """.trimIndent()

            "fr" -> """
                ### Ce que Révèle Votre Mélange ($conceptsText)
                En reliant votre carte de sphères (Onglet 1) à votre réflexion (*"$userPrompt"*), votre esprit fait dialoguer **$conceptsText**.
                
                - **L'Échange entre $firstConcept et $secondConcept :** Ces deux éléments s'influencent mutuellement comme deux notes d'un même accord. Ce qui semblait opposé devient une nuance créative.
                - **Votre Rythme Actuel (${(arousal * 100).toInt()}% Rythme · ${(valence * 100).toInt()}% Ouverture) :** Laissez ces idées respirer ensemble sans chercher à tout figer immédiatement.
            """.trimIndent()

            else -> """
                ### O Que o Seu Desenho de Estado Revela ($conceptsText)
                Ao juntar o mapa de esferas que você montou na Aba 1 com o que você trouxe agora (*"$userPrompt"*), fica claro que o seu momento atual nasce do encontro entre **$conceptsText**.
                
                Repare em como **$firstConcept** e **$secondConcept** conversam entre si quando estão próximos:
                - **A Mistura das Suas Esferas:** Muitas vezes achamos que sentimos apenas uma coisa por vez, mas o seu mapa mostra uma combinação rica. Enquanto **$firstConcept** puxa sua atenção para o que importa, **$secondConcept** dá o tom e a cor de fundo da sua percepção hoje.
                - **Seu Clima Interno (${(arousal * 100).toInt()}% de Ritmo · ${(valence * 100).toInt()}% de Abertura):** Com essa configuração, o segredo não é tentar apagar nenhuma dessas esferas, mas deixá-las conviver no mesmo espaço com mais leveza — como notas diferentes que, juntas, formam um acorde interessante.
            """.trimIndent()
        }
    }

    private fun buildLocalizedSomaticShift(
        languageCode: String,
        arousal: Float,
        valence: Float,
        concepts: List<String>
    ): String {
        val primaryConcept = concepts.firstOrNull() ?: "essa ideia central"
        return when (languageCode.lowercase()) {
            "en" -> if (arousal > 0.60f) {
                "Take a slow breath in for 4 seconds, and exhale for 6 seconds while relaxing your shoulders. Picture '$primaryConcept' softening from a tight knot into a warm, spacious glow in the center of your chest."
            } else {
                "Feel the weight of your hands holding the phone and listen to the chord playing in the background. Imagine '$primaryConcept' floating gently with plenty of room around it, giving you clarity for your next step."
            }
            "es" -> "Inhala profundo y suelta el aire despacio relajando los hombros. Imagina que '$primaryConcept' deja de ser un peso en la mente y se convierte en una luz cálida y tranquila en el pecho."
            "fr" -> "Inspirez calmement et relâchez vos épaules en expirant. Imaginez que '$primaryConcept' s'ouvre comme un espace lumineux et paisible autour de vous."
            else -> if (arousal > 0.60f) {
                "Solte o ar devagar pela boca durante 6 segundos e deixe os ombros descerem. Imagine que '$primaryConcept' deixa de ser um ponto apertado na cabeça e se espalha como uma onda morna, calma e espaçosa pelo peito."
            } else {
                "Sinta o apoio das suas mãos segurando o celular e ouça a harmonia sonora ao fundo. Visualize '$primaryConcept' flutuando com bastante espaço ao redor, sem pressa e sem cobrança."
            }
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
