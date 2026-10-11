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
    val sourceType: String, // "WIKIPEDIA" or "ACADEMIC"
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
     * Fetches real theoretical context from Wikipedia REST API (in the active app language)
     * and academic literature from Crossref Works API (Google Scholar / Academic index)
     * using the device's internet connection.
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
                "Consciousness Phenomenology Neuroscience"
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
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=3"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/1.0 (ConsciousSimulation; Android)")
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
                        val articleUrl = "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"
                        results.add(
                            GroundingSource(
                                sourceType = "WIKIPEDIA",
                                title = title,
                                subtitleOrAuthors = "Wikipedia ($lang)",
                                snippet = rawSnippet.ifBlank { "Conceito enciclopédico indexado." },
                                url = articleUrl
                            )
                        )
                    }
                }
                results
            }
        }.getOrElse { emptyList() }.ifEmpty {
            // Fallback theoretical grounding if device is temporarily offline
            listOf(
                GroundingSource(
                    sourceType = "WIKIPEDIA",
                    title = "Fenomenologia da Percepção (Maurice Merleau-Ponty)",
                    subtitleOrAuthors = "Base Teórica Enciclopédica",
                    snippet = "A percepção não é uma representação passiva, mas um campo relacional corporificado onde sujeito e mundo se co-constituem.",
                    url = "https://pt.wikipedia.org/wiki/Maurice_Merleau-Ponty"
                ),
                GroundingSource(
                    sourceType = "WIKIPEDIA",
                    title = "Śūnyatā & Originação Dependente (Nagarjuna)",
                    subtitleOrAuthors = "Filosofia da Mente Madhyamaka",
                    snippet = "Os fenômenos mentais emergem em rede relacional sem substância isolada (svabhāva), permitindo mutação contínua de estados.",
                    url = "https://pt.wikipedia.org/wiki/Sunyata"
                )
            )
        }
    }

    private fun fetchAcademicWorks(query: String): List<GroundingSource> {
        return runCatching {
            val scholarlyQuery = "$query consciousness neuroscience philosophy"
            val encoded = URLEncoder.encode(scholarlyQuery, "UTF-8")
            val url = "https://api.crossref.org/works?query=$encoded&rows=3&select=DOI,title,author,published-print,abstract"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NoesisLab/1.0 (mailto:research@noesislab.org)")
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
                    val title = if (titleArr != null && titleArr.length() > 0) titleArr.optString(0) else "Academic Paper"
                    val doi = obj.optString("DOI", "")

                    val authorsArr = obj.optJSONArray("author")
                    val authorsList = mutableListOf<String>()
                    if (authorsArr != null) {
                        for (a in 0 until minOf(authorsArr.length(), 3)) {
                            val au = authorsArr.getJSONObject(a)
                            val family = au.optString("family", "")
                            if (family.isNotBlank()) authorsList.add(family)
                        }
                    }
                    val authorText = if (authorsList.isNotEmpty()) authorsList.joinToString(", ") else "Academic Literature"

                    val rawAbstract = obj.optString("abstract", "")
                        .replace(Regex("<[^>]*>"), "")
                        .trim()
                        .take(220)

                    list.add(
                        GroundingSource(
                            sourceType = "ACADEMIC",
                            title = title,
                            subtitleOrAuthors = authorText,
                            snippet = if (rawAbstract.isNotBlank()) "$rawAbstract..." else "DOI: $doi — Indexado em literatura científica revisada por pares.",
                            url = if (doi.isNotBlank()) "https://doi.org/$doi" else "https://scholar.google.com"
                        )
                    )
                }
                list
            }
        }.getOrElse { emptyList() }.ifEmpty {
            listOf(
                GroundingSource(
                    sourceType = "ACADEMIC",
                    title = "The Free-Energy Principle: A Unified Brain Theory?",
                    subtitleOrAuthors = "Friston, K. (Nature Reviews Neuroscience)",
                    snippet = "Modela o cérebro como um motor de inferência ativa que minimiza a entropia perceptiva e a surpresa variacional.",
                    url = "https://doi.org/10.1038/nrn2787"
                ),
                GroundingSource(
                    sourceType = "ACADEMIC",
                    title = "Neurophenomenology: A Methodological Remedy for the Hard Problem",
                    subtitleOrAuthors = "Varela, F. J. (Journal of Consciousness Studies)",
                    snippet = "Articula a experiência vivida em primeira pessoa com a dinâmica de redes neurais não-lineares e atratores.",
                    url = "https://scholar.google.com"
                )
            )
        }
    }

    /**
     * Strict OpenAI-Compatible BYOK Provider Verifier:
     * 1. Normalizes baseUrl (accepts with or without /v1)
     * 2. Calls GET {baseUrl}/models with Bearer token to parse available models for the user's account
     * 3. Sends an actual lightweight POST {baseUrl}/chat/completions ping ("oi") with strict minimal
     *    OpenAI parameters (`model`, `messages`, `max_tokens`) to each candidate model.
     * 4. Filters out any model that returns 401, 403, 404, 429 (rate limit), 500+, or empty response,
     *    returning ONLY models that actually responded 200 OK.
     */
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

        // Prioritize up to 12 candidate chat models so verification completes swiftly without hanging
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
                "Foram encontrados ${discoveredIds.size} modelos em /models, mas nenhum respondeu 200 OK ao ping (erros 403/404/429 filtrados)."
            } else null
        )
    }

    /**
     * Sends a strictly standard, minimal OpenAI-compatible POST /chat/completions request
     * with NO eccentric parameters (only `model`, `messages`, `max_tokens`).
     */
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
                    // Strictly reject 401, 403, 404, 429, 500, etc.
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
     * Orchestrates the conscious simulation response using live Wikipedia + Academic grounding,
     * particle field physics, and biofeedback state. Uses the active BYOK OpenAI-compatible provider
     * if configured, or seamlessly falls back to the built-in phenomenological synthesis engine
     * so the app works immediately out of the box.
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
        val grounding = fetchLiveGrounding(
            queryConcepts = conceptNames,
            userPrompt = userPrompt,
            languageCode = languageCode
        )

        val wikiContext = grounding.wikiSources.joinToString("\n") {
            "- [Wikipedia] ${it.title}: ${it.snippet}"
        }
        val academicContext = grounding.academicSources.joinToString("\n") {
            "- [Academic/Crossref] ${it.title} (${it.subtitleOrAuthors}): ${it.snippet}"
        }

        val biofeedbackDescriptor = buildString {
            append("Arousal=${(arousal * 100).toInt()}%, ")
            append("AffectiveValence=${(valence * 100).toInt()}%, ")
            append("MeditativeFocus=${(focus * 100).toInt()}%")
        }

        val systemPrompt = """
            Você é o Engenheiro de Realidades Mentais do Noesis Lab.
            $languageInstruction
            
            Sua tarefa é modelar uma 'simulação consciente' viva onde conceitos e emoções se comportam como partículas e estruturas arquitetônicas num campo de percepção.
            Use os dados teóricos reais coletados agora da Wikipédia e da literatura acadêmica abaixo para fundamentar a resposta em neurociência (Friston, Varela, Damasio), física teórica (campos quânticos, termodinâmica, Rovelli) e filosofia da mente (Deleuze, Merleau-Ponty, Nagarjuna, Spinoza).
            
            Adapte o ritmo das frases, as metáforas e as sugestões sensoriais ao Biofeedback atual do usuário ($biofeedbackDescriptor), induzindo mudanças perceptivas e cognitivas sentidas enquanto ele lê.
            Ao final, inclua uma seção separada por "---SOMATIC---" com um protocolo curto e direto de experimentação somática/perceptiva imediata.
        """.trimIndent()

        val userMessage = """
            Estado do Campo de Partículas: $particlesSummary
            Biofeedback do Usuário: $biofeedbackDescriptor
            
            Fontes da Wikipédia em tempo real:
            $wikiContext
            
            Fontes Acadêmicas em tempo real:
            $academicContext
            
            Indagação / Experimento do Usuário:
            $userPrompt
        """.trimIndent()

        if (!activeBaseUrl.isNullOrBlank() && !activeModelId.isNullOrBlank()) {
            val cleanBase = normalizeOpenAiBaseUrl(activeBaseUrl)
            // Strict OpenAI-compatible payload: only model, messages, temperature
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

        // Built-in Theoretical Orchestrator combining the live Wikipedia & Academic grounding with Biofeedback
        val synthesized = synthesizeGroundedNarrativeLocally(
            userPrompt = userPrompt,
            conceptNames = conceptNames,
            arousal = arousal,
            valence = valence,
            focus = focus,
            grounding = grounding,
            languageCode = languageCode
        )
        val somatic = buildLocalizedSomaticShift(languageCode, arousal, valence, conceptNames)

        OrchestratedSimulationResult(
            narrativeMarkdown = synthesized,
            somaticShiftSuggestion = somatic,
            providerLabel = "Noesis Phenomenological Engine + Live Web Grounding",
            grounding = grounding
        )
    }

    private fun synthesizeGroundedNarrativeLocally(
        userPrompt: String,
        conceptNames: List<String>,
        arousal: Float,
        valence: Float,
        focus: Float,
        grounding: GroundingBundle,
        languageCode: String
    ): String {
        val conceptsText = if (conceptNames.isNotEmpty()) conceptNames.joinToString(", ") else "Consciência, Tempo, Desejo"
        val topWiki = grounding.wikiSources.firstOrNull()
        val topAcademic = grounding.academicSources.firstOrNull()

        return when (languageCode.lowercase()) {
            "en" -> """
                ### I. Field Dynamics & Particle Behavior ($conceptsText)
                In your perceptual field right now, **$conceptsText** do not behave as static nouns—they act as **interacting excitations in a non-linear manifold**. As your current autonomic arousal sits at **${(arousal * 100).toInt()}%** with a meditative focus of **${(focus * 100).toInt()}%**, the metric space between these concepts warps:
                
                - **Deleuzian Rhizomatics & Attractors:** Rather than following linear cause-and-effect, your inquiry (*"$userPrompt"*) generates a strange attractor. High-charge concepts pull peripheral memories into orbital resonance, while opposing valences create standing interference waves.
                - **Nagarjuna's Dependent Origination (*Pratītyasamutpāda*):** Notice how none of these emotional particles possess independent self-nature (*svabhāva*). Remove the observer's boundary, and fear or domination immediately loses its gravitational mass, dissolving into relational openness.
                
                ### II. Theoretical & Neuroscientific Grounding
                - **Encyclopedic Synthesis (${topWiki?.title ?: "Phenomenology"}):** ${topWiki?.snippet ?: "Perceptual space is actively constituted through embodied motor intentionality."}
                - **Scientific Literature (${topAcademic?.title ?: "Active Inference"} — ${topAcademic?.subtitleOrAuthors ?: "Neuroscience"}):** Under Karl Friston's Free-Energy Principle and Varela's neurophenomenology, what you feel as internal tension is **precision-weighted prediction error** vibrating across cortical hierarchies.
                
                ### III. Architectural Mutation
                As you read these words, the mental chamber shifts from a closed defensive labyrinth into an open **Merleau-Pontian perceptual horizon**, allowing the energy of the collision to reorganize into lucid structural coherence.
            """.trimIndent()

            "es" -> """
                ### I. Dinámica de Campo y Comportamiento de Partículas ($conceptsText)
                En tu campo perceptivo actual, **$conceptsText** no operan como entidades fijas, sino como **excitaciones acopladas en una variedad topológica viva**. Con tu activación autonómica al **${(arousal * 100).toInt()}%** y una inmersión meditativa del **${(focus * 100).toInt()}%**, la geometría interna se curva:
                
                - **Rizoma y Atractores (Deleuze):** Tu exploración (*"$userPrompt"*) inaugura un campo de intensidades donde cada emoción emite filamentos hacia las demás, transformando la fricción psíquica en potencia creativa (*conatus*).
                - **Vacuidad Relacional (Nagarjuna):** Ninguna partícula mental existe por sí misma (*svabhāva*). Al observar cómo el Miedo o el Deseo dependen mutuamente del horizonte temporal, su rigidez se vuelve translúcida.
                
                ### II. Fundamentación Teórica en Tiempo Real
                - **Síntesis Wikipedia (${topWiki?.title ?: "Fenomenología"}):** ${topWiki?.snippet ?: "El cuerpo vivido configura la curvatura del espacio percibido."}
                - **Literatura Científica (${topAcademic?.title ?: "Inferencia Activa"} — ${topAcademic?.subtitleOrAuthors ?: "Neurociencia"}):** Desde la neurociencia predictiva, el dilema experimentado corresponde a una actualización de creencias bayesianas encarnadas.
            """.trimIndent()

            "fr" -> """
                ### I. Dynamique du Champ et Comportement des Particules ($conceptsText)
                Dans votre champ perceptif actuel, **$conceptsText** agissent comme des **oscillateurs couplés au sein d'une variété topologique vivante**. Avec une activation autonomique de **${(arousal * 100).toInt()}%** et une profondeur de focus de **${(focus * 100).toInt()}%**, l'espace intérieur se plie :
                
                - **Flux Rhizomatiques (Deleuze) :** Votre questionnement (*"$userPrompt"*) crée un attracteur étrange où les affects se composent et bifurquent.
                - **Vacuité Relationnelle (Nagarjuna / Śūnyatā) :** Aucune particule émotionnelle ne possède d'existence intrinsèque isolée (*svabhāva*) ; chacune émerge par co-production dépendante.
                
                ### II. Ancrage Théorique en Direct
                - **Synthèse Wikipédia (${topWiki?.title ?: "Phénoménologie"}) :** ${topWiki?.snippet ?: "La perception est une co-naissance incarnée entre le sujet et le monde."}
                - **Littérature Scientifique (${topAcademic?.title ?: "Inférence Active"} — ${topAcademic?.subtitleOrAuthors ?: "Neurosciences"}) :** L'architecture neuronale ajuste sa précision synaptique pour transformer l'entropie affective en clarté structurelle.
            """.trimIndent()

            else -> """
                ### I. Dinâmica do Campo & Comportamento de Partículas ($conceptsText)
                No seu campo de percepção neste instante, **$conceptsText** não se comportam como conceitos estáticos, mas como **excitações acopladas em uma variedade topológica viva**. Com sua ativação autonômica calibrada em **${(arousal * 100).toInt()}%** e profundidade de foco em **${(focus * 100).toInt()}%**, o espaço-tempo subjetivo dobra-se da seguinte forma:
                
                - **Máquinas Desejantes e Rizoma (Deleuze & Guattari):** Sua indagação (*"$userPrompt"*) atua como um vetor de desterritorialização. Em vez de um conflito binário rígido, as partículas entram em ressonância harmônica, convertendo massa semântica densa em linhas de fuga criativas.
                - **Originação Dependente e Śūnyatā (Nagarjuna):** Ao inspecionar o núcleo de cada partícula, percebe-se que nenhuma possui natureza própria isolada (*svabhāva*). A tensão só persiste enquanto a fronteira da **Identidade** tenta congelar o fluxo relacional.
                
                ### II. Fundamentação Teórica & Neurocientífica ao Vivo
                - **Síntese Enciclopédica (${topWiki?.title ?: "Fenomenologia da Percepção"}):** ${topWiki?.snippet ?: "Em Merleau-Ponty, o corpo próprio é a matriz geométrica de todo espaço vivido e intencionalidade."}
                - **Literatura Acadêmica (${topAcademic?.title ?: "Inferência Ativa e Energia Livre"} — ${topAcademic?.subtitleOrAuthors ?: "Neurociência Cognitiva"}):** Sob o Princípio da Energia Livre (Friston) e a Neurofenomenologia (Varela), a colisão entre essas ideias representa um ajuste de precisão preditiva no córtex interoceptivo.
                
                ### III. Mutação Arquitetônica
                Observe como as paredes mentais ao redor desse tema deixam de ser monolitos opacos e assumem a geometria de um pórtico permeável: a carga antes represada agora circula como potência de agir (*conatus*).
            """.trimIndent()
        }
    }

    private fun buildLocalizedSomaticShift(
        languageCode: String,
        arousal: Float,
        valence: Float,
        concepts: List<String>
    ): String {
        val primaryConcept = concepts.firstOrNull() ?: "o conceito central"
        return when (languageCode.lowercase()) {
            "en" -> if (arousal > 0.65f) {
                "Soften the muscles behind your eyes and widen your peripheral vision beyond the edges of the screen. As you exhale slowly for 6 seconds, visualize the high-frequency oscillation of '$primaryConcept' expanding into a cool, wide horizon across your chest."
            } else {
                "Notice the subtle weight of your hands holding the device. Imagine a warm golden-terracotta filament connecting the center of your sternum to the '$primaryConcept' particle, translating abstract thought into quiet bodily presence."
            }
            "es" -> "Relaja el espacio entre tus sienes y amplía tu visión periférica. Al exhalar, siente cómo la densidad gravitacional de '$primaryConcept' se transforma de un punto fijo en una onda cálida y espaciosa en el centro del pecho."
            "fr" -> "Relâchez la tension derrière vos yeux et élargissez votre vision périphérique. En expirant lentement, visualisez la particule '$primaryConcept' se dilater en une onde lumineuse et calme à travers votre espace thoracique."
            else -> if (arousal > 0.65f) {
                "Descontraia a musculatura atrás dos olhos e amplie sua visão periférica para além das bordas do aparelho. Ao expirar por 6 segundos, sinta a vibração rápida de '$primaryConcept' desacelerar para uma onda larga, morna e espaçosa no centro do tórax."
            } else {
                "Perceba a temperatura das suas mãos sustentando o aparelho e o intervalo silencioso entre duas respirações. Visualize '$primaryConcept' deixando de ser um peso abstrato na testa para se tornar uma arquitetura aberta, iluminada e habitável ao seu redor."
            }
        }
    }

    private fun normalizeOpenAiBaseUrl(raw: String): String {
        var trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        // Standard OpenAI-compatible endpoints end with /v1 (or /api/v1, /openai/v1)
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
