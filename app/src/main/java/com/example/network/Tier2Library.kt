package com.example.network

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/**
 * Offline retrieval engine for the Tier 2 internal library (assets/tier2).
 *
 * Pipeline: parse (optional front matter + markdown headings) -> chunk by
 * paragraph (~900 chars, sentence-aligned) -> inverted term statistics ->
 * BM25 ranking with a small pt/en/es/fr synonym glossary -> citation-aware
 * excerpt rendering.
 *
 * No Android or third-party dependencies: it runs on the JVM, so the ranking
 * can be unit-tested without a device.
 */
object Tier2Library {

    private const val TARGET_CHUNK_CHARS = 900
    private const val MAX_CHUNK_CHARS = 1400
    private const val MIN_CHUNK_CHARS = 30

    private const val BM25_K1 = 1.2
    private const val BM25_B = 0.75

    /** Raw asset: file name is only used as a fallback title. */
    data class Source(val fileName: String, val text: String)

    /** One retrievable passage, carrying enough context to be cited. */
    data class Chunk(
        val docId: Int,
        val title: String,
        val author: String?,
        val section: String?,
        val position: Int,
        val total: Int,
        val text: String
    )

    private data class ParsedChunk(
        val chunk: Chunk,
        val terms: List<String>,
        val headingTerms: Set<String>,
        /** folded term -> first spelling seen in the work (keeps accents). */
        val displayForms: Map<String, String>
    )

    /**
     * Compact inverted index: `term -> [chunkIndex, termFrequency, ...]`.
     * Keeps memory proportional to the number of distinct (term, chunk) pairs
     * instead of one map per chunk, so a multi-megabyte library stays cheap.
     */
    class Corpus internal constructor(
        private val chunks: List<Chunk>,
        private val lengths: IntArray,
        private val headingTerms: List<Set<String>>,
        private val postings: Map<String, IntArray>,
        private val idf: Map<String, Double>,
        private val averageLength: Double,
        private val displayForms: Map<String, String>
    ) {
        val chunkCount: Int get() = chunks.size
        val isEmpty: Boolean get() = chunks.isEmpty()

        /**
         * Best passages for [query], at most [limit], never more than
         * [perDocLimit] from the same work. Returns an empty list when nothing
         * matches — injecting unrelated text is worse than injecting nothing.
         */
        fun search(query: String, limit: Int = 4, perDocLimit: Int = 2): List<Chunk> =
            select(rank(query), limit, perDocLimit)

        /**
         * Vocabulary the library associates with [query], without any sentence
         * or attribution: isolated words that the works use to think this theme.
         * Meant for the internal engine, whose output must never reproduce text.
         */
        fun themes(
            query: String,
            limit: Int = 4,
            perDocLimit: Int = 2,
            maxTerms: Int = 6
        ): List<String> {
            val order = rank(query)
            if (order.isEmpty()) return emptyList()
            val picked = select(order, limit, perDocLimit)
            if (picked.isEmpty()) return emptyList()
            val hitIndices = picked.map { chunks.indexOf(it) }.filter { it >= 0 }.toHashSet()
            if (hitIndices.isEmpty()) return emptyList()

            val queryTerms = expandQuery(tokenize(query)).keys
            val weights = mutableMapOf<String, Double>()
            for ((term, posting) in postings) {
                if (term.length < 5 || term in queryTerms) continue
                var frequency = 0
                var i = 0
                while (i < posting.size) {
                    if (posting[i] in hitIndices) frequency += posting[i + 1]
                    i += 2
                }
                if (frequency > 0) weights[term] = frequency * (idf[term] ?: 0.0)
            }
            return weights.entries
                .sortedByDescending { it.value }
                .take(maxTerms)
                .map { displayForms[it.key] ?: it.key }
        }

        /** Chunk indices ordered by relevance; empty when nothing matches. */
        private fun rank(query: String): List<Int> {
            if (chunks.isEmpty()) return emptyList()
            val weightedTerms = expandQuery(tokenize(query))
            if (weightedTerms.isEmpty()) return emptyList()

            val scores = DoubleArray(chunks.size)
            for ((term, weight) in weightedTerms) {
                val posting = postings[term] ?: continue
                val idfValue = idf[term] ?: continue
                var i = 0
                while (i < posting.size) {
                    val index = posting[i]
                    val tf = posting[i + 1]
                    val norm = BM25_K1 * (1 - BM25_B + BM25_B * lengths[index] / averageLength)
                    scores[index] += weight * idfValue * (tf * (BM25_K1 + 1)) / (tf + norm)
                    i += 2
                }
            }

            // A concept that appears in a title or section heading is a strong
            // signal even when the passage never repeats the word.
            for (term in weightedTerms.keys) {
                val idfValue = idf[term] ?: continue
                val weight = weightedTerms.getValue(term)
                for (index in chunks.indices) {
                    if (term in headingTerms[index]) {
                        scores[index] += weight * idfValue * 1.6
                    }
                }
            }

            return chunks.indices.filter { scores[it] > 0.0 }
                .sortedByDescending { scores[it] }
        }

        private fun select(order: List<Int>, limit: Int, perDocLimit: Int): List<Chunk> {
            if (limit <= 0) return emptyList()
            val perDoc = mutableMapOf<Int, Int>()
            val picked = mutableListOf<Chunk>()
            // First pass: one passage per work, so a single book cannot drown
            // out the rest of the library. Second pass fills up to perDocLimit.
            for (round in 0 until perDocLimit.coerceAtLeast(1)) {
                for (index in order) {
                    if (picked.size >= limit) return picked
                    val chunk = chunks[index]
                    val used = perDoc[chunk.docId] ?: 0
                    if (used != round) continue
                    perDoc[chunk.docId] = used + 1
                    picked += chunk
                }
            }
            return picked
        }

        /** Ready-to-inject block for the prompt, with work/section/position. */
        fun render(
            query: String,
            maxChars: Int = 900,
            limit: Int = 4,
            perDocLimit: Int = 2
        ): String {
            val hits = search(query, limit, perDocLimit)
            if (hits.isEmpty()) return ""
            val sb = StringBuilder()
            var remaining = maxChars
            hits.forEachIndexed { i, hit ->
                if (remaining < 140) return@forEachIndexed
                val header = buildString {
                    append("- ")
                    hit.author?.let { append(it).append(" — ") }
                    append(hit.title)
                    hit.section?.let { append(", ").append(it) }
                    append(" (trecho ")
                    append(hit.position + 1)
                    append('/')
                    append(hit.total)
                    append("): ")
                }
                val cap = minOf(remaining - header.length, 560)
                if (cap < 120) return@forEachIndexed
                val excerpt = truncateAtSentence(hit.text, cap)
                if (i > 0) sb.append('\n')
                sb.append(header).append(excerpt)
                remaining -= header.length + excerpt.length
            }
            return sb.toString().trim()
        }
    }

    // ---------------------------------------------------------------- build

    fun build(sources: List<Source>): Corpus {
        val parsed = mutableListOf<ParsedChunk>()
        sources.forEachIndexed { docId, source ->
            parsed += parseDocument(source, docId)
        }
        if (parsed.isEmpty()) {
            return Corpus(
                emptyList(), IntArray(0), emptyList(), emptyMap(), emptyMap(), 1.0, emptyMap()
            )
        }

        val postingLists = mutableMapOf<String, MutableList<Int>>()
        val documentFrequency = mutableMapOf<String, Int>()
        val lengths = IntArray(parsed.size)
        parsed.forEachIndexed { index, document ->
            val tokens = document.terms
            lengths[index] = tokens.size.coerceAtLeast(1)
            for ((term, frequency) in tokens.groupingBy { it }.eachCount()) {
                postingLists.getOrPut(term) { mutableListOf() }.apply {
                    add(index)
                    add(frequency)
                }
                documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
            }
        }

        val total = parsed.size.toDouble()
        val idf = documentFrequency.mapValues { (_, df) ->
            ln(1.0 + (total - df + 0.5) / (df + 0.5))
        }
        val postings = postingLists.mapValues { (_, list) -> list.toIntArray() }
        val averageLength = lengths.sum().toDouble() / parsed.size
        val displayForms = mutableMapOf<String, String>()
        for (document in parsed) {
            for ((folded, raw) in document.displayForms) {
                displayForms.putIfAbsent(folded, raw)
            }
        }
        return Corpus(
            chunks = parsed.map { it.chunk },
            lengths = lengths,
            headingTerms = parsed.map { it.headingTerms },
            postings = postings,
            idf = idf,
            averageLength = averageLength.coerceAtLeast(1.0),
            displayForms = displayForms
        )
    }

    // ---------------------------------------------------------------- parse

    private fun parseDocument(source: Source, docId: Int): List<ParsedChunk> {
        val (meta, body) = splitFrontMatter(source.text)
        val fallbackTitle = source.fileName.substringBeforeLast('.')

        val paragraphs = mutableListOf<Paragraph>()
        var title: String? = meta["title"]
        var currentSection: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString().trim()
            buffer.setLength(0)
            if (text.isNotEmpty()) paragraphs += Paragraph(text, currentSection)
        }

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flush()
                return@forEach
            }
            val heading = HEADING.find(line)
            if (heading != null) {
                flush()
                val level = heading.groupValues[1].length
                val label = heading.groupValues[2].trim().trimEnd('#').trim()
                if (label.isNotEmpty()) {
                    if (level == 1 && title.isNullOrBlank()) title = label
                    currentSection = label
                }
                return@forEach
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(line.replace(WHITESPACE, " "))
        }
        flush()

        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val author = meta["author"]?.takeIf { it.isNotBlank() }
            ?: meta["autor"]?.takeIf { it.isNotBlank() }

        val chunks = mutableListOf<Pair<String, String?>>()
        val acc = StringBuilder()
        var accSection: String? = null

        fun flushChunk() {
            val text = acc.toString().trim()
            acc.setLength(0)
            if (text.length >= MIN_CHUNK_CHARS) chunks += text to accSection
            accSection = null
        }

        for (paragraph in paragraphs) {
            // Never merge passages from different sections: the section label is
            // what makes the citation meaningful.
            if (acc.isNotEmpty() && paragraph.section != accSection) flushChunk()
            if (acc.isEmpty()) accSection = paragraph.section
            if (acc.isNotEmpty()) acc.append(' ')
            acc.append(paragraph.text)
            if (acc.length >= TARGET_CHUNK_CHARS) flushChunk()
            if (acc.length >= MAX_CHUNK_CHARS) flushChunk()
        }
        flushChunk()

        return chunks.mapIndexed { index, (text, section) ->
            val chunk = Chunk(
                docId = docId,
                title = resolvedTitle,
                author = author,
                section = section,
                position = index,
                total = chunks.size,
                text = text
            )
            val pairs = tokenPairs(text)
            ParsedChunk(
                chunk = chunk,
                terms = pairs.map { it.first },
                headingTerms = tokenize("$resolvedTitle ${section ?: ""}").toSet(),
                displayForms = pairs.associate { it.first to it.second }
            )
        }
    }

    private data class Paragraph(val text: String, val section: String?)

    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to normalized
        val closing = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closing < 0) return emptyMap<String, String>() to normalized
        val meta = mutableMapOf<String, String>()
        lines.subList(1, closing + 1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim().trim('"', '\'').trim()
                if (key.isNotEmpty() && value.isNotEmpty()) meta[key] = value
            }
        }
        return meta to lines.drop(closing + 2).joinToString("\n")
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val WHITESPACE = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    // --------------------------------------------------------------- tokens

    fun tokenize(text: String): List<String> = tokenPairs(text).map { it.first }

    /** Folded token with the spelling it had in the text (accents preserved). */
    private fun tokenPairs(text: String): List<Pair<String, String>> =
        WORD.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .mapNotNull { raw ->
                val folded = fold(raw)
                if (folded.length >= 3 && folded !in STOP_WORDS) folded to raw else null
            }
            .toList()

    /** Lowercase + accent folding, so "medo"/"medó" and "être"/"etre" meet. */
    fun fold(token: String): String =
        Normalizer.normalize(token, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

    private val DIACRITICS = Regex("\\p{M}+")

    private fun expandQuery(tokens: List<String>): Map<String, Double> {
        val weighted = mutableMapOf<String, Double>()
        fun add(term: String, weight: Double) {
            if (weighted[term] == null || weighted.getValue(term) < weight) weighted[term] = weight
        }
        for (token in tokens) {
            add(token, 1.0)
            val group = SYNONYM_GROUP[token] ?: continue
            for (variant in group) {
                add(variant, 0.55)
                // Inflected/plural forms present in the corpus.
                add(variant + "s", 0.4)
            }
        }
        return weighted
    }

    /**
     * Concept groups across pt/en/es/fr. Terms are already folded; each group
     * is written with its English head word first only for readability.
     */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "fear" to listOf("fear", "medo", "miedo", "peur", "terror", "angst", "angustia", "angoisse"),
        "death" to listOf("death", "morte", "muerte", "mort", "morrer", "dying", "finitude", "finitude", "mortal"),
        "love" to listOf("love", "amor", "amour", "amar", "aime", "liebe", "affection", "afeto", "carino"),
        "freedom" to listOf("freedom", "liberdade", "libertad", "liberte", "free", "livre", "libre"),
        "power" to listOf("power", "poder", "pouvoir", "potencia", "puissance", "dominio", "dominance"),
        "time" to listOf("time", "tempo", "tiempo", "temps", "temporal", "temporality", "duracao", "duree"),
        "body" to listOf("body", "corpo", "cuerpo", "corps", "corporal", "soma", "carne", "chair"),
        "mind" to listOf("mind", "mente", "esprit", "espirito", "mental", "pensamento", "thought", "pensee"),
        "desire" to listOf("desire", "desejo", "deseo", "desir", "desejar", "appetite", "apetite"),
        "sadness" to listOf("sadness", "tristeza", "tristesse", "melancolia", "melancholy", "luto", "mourning"),
        "joy" to listOf("joy", "alegria", "joie", "felicidade", "happiness", "bonheur", "gozo", "jouissance"),
        "anger" to listOf("anger", "raiva", "ira", "colere", "odio", "hate", "haine", "rancor"),
        "anxiety" to listOf("anxiety", "ansiedade", "ansiedad", "anxiete", "inquietude", "inquietud", "restlessness"),
        "loneliness" to listOf("loneliness", "solidao", "soledad", "solitude", "isolamento", "isolation"),
        "guilt" to listOf("guilt", "culpa", "culpabilite", "culpable", "remorso", "remorse"),
        "shame" to listOf("shame", "vergonha", "vergüenza", "vergogne", "honte", "humilhacao", "humiliation"),
        "hope" to listOf("hope", "esperanca", "esperanza", "espoir", "expectativa", "expectation"),
        "emptiness" to listOf("emptiness", "vazio", "vacio", "vide", "nada", "nothingness", "nihil", "nient"),
        "identity" to listOf("identity", "identidade", "identidad", "identite", "self", "si-mesmo", "ipseidade"),
        "consciousness" to listOf("consciousness", "consciencia", "conciencia", "conscience", "awareness", "percepcao"),
        "will" to listOf("will", "vontade", "voluntad", "volonte", "vontade", "volition"),
        "reason" to listOf("reason", "razao", "razon", "raison", "racional", "rational", "logica", "logic"),
        "nature" to listOf("nature", "natureza", "naturaleza", "physis", "natural", "cosmos"),
        "god" to listOf("god", "deus", "dios", "dieu", "divino", "divine", "sagrado", "sacred"),
        "soul" to listOf("soul", "alma", "ame", "anima", "psyche", "psique"),
        "suffering" to listOf("suffering", "sofrimento", "sufrimiento", "souffrance", "dor", "pain", "douleur"),
        "pleasure" to listOf("pleasure", "prazer", "placer", "plaisir", "gozo", "deleite"),
        "memory" to listOf("memory", "memoria", "memoire", "lembranca", "souvenir", "recordacao"),
        "dream" to listOf("dream", "sonho", "sueno", "reve", "sonhar", "sonar", "songe"),
        "work" to listOf("work", "trabalho", "trabajo", "travail", "labor", "oficio", "metier"),
        "money" to listOf("money", "dinheiro", "dinero", "argent", "riqueza", "wealth", "capital"),
        "family" to listOf("family", "familia", "famille", "parentesco", "kinship", "filho", "child"),
        "friendship" to listOf("friendship", "amizade", "amistad", "amitie", "amigo", "friend", "ami"),
        "truth" to listOf("truth", "verdade", "verdad", "verite", "verum", "aletheia"),
        "beauty" to listOf("beauty", "beleza", "belleza", "beaute", "belo", "sublime"),
        "chaos" to listOf("chaos", "caos", "caos", "desordem", "disorder", "desordre"),
        "order" to listOf("order", "ordem", "orden", "ordre", "estrutura", "structure"),
        "change" to listOf("change", "mudanca", "cambio", "changement", "transformacao", "transformation", "devir", "becoming"),
        "past" to listOf("past", "passado", "pasado", "passe", "memoria", "historia", "history"),
        "future" to listOf("future", "futuro", "futur", "porvir", "a-venir", "expectativa"),
        "present" to listOf("present", "presente", "present", "agora", "now", "instante", "instant"),
        "childhood" to listOf("childhood", "infancia", "enfance", "crianca", "enfant", "infantil"),
        "self" to listOf("self", "eu", "yo", "moi", "ego", "sujeito", "subject", "sujet"),
        "other" to listOf("other", "outro", "otro", "autre", "alteridade", "alterity", "outrem"),
        "language" to listOf("language", "linguagem", "lenguaje", "langage", "palavra", "word", "mot", "discurso"),
        "silence" to listOf("silence", "silencio", "silencio", "silencio", "calar", "sich schweigen", "mutismo"),
        "solitude" to listOf("solitude", "solidao", "soledad", "retiro", "retreat", "recolhimento")
    )

    private val SYNONYM_GROUP: Map<String, List<String>> by lazy {
        val map = mutableMapOf<String, List<String>>()
        for (variants in SYNONYMS.values) {
            val cleaned = variants.map { fold(it) }.distinct()
            for (variant in cleaned) {
                val existing = map[variant]
                map[variant] = if (existing == null) cleaned else (existing + cleaned).distinct()
            }
        }
        map
    }

    private val STOP_WORDS: Set<String> = setOf(
        // pt
        "que", "com", "para", "por", "uma", "dos", "das", "como", "mais", "mas", "seu", "sua",
        "seus", "suas", "ele", "ela", "eles", "elas", "isso", "isto", "aquilo", "entre", "sobre",
        "quando", "onde", "porque", "pelo", "pela", "num", "numa", "sao", "ser", "esta", "estao",
        "foi", "era", "tem", "ter", "haver", "tambem", "todo", "toda", "todos", "todas", "nos",
        "vos", "lhe", "lhes", "meu", "minha", "nao", "sim", "aos", "à", "às", "ao",
        // en
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "his", "her",
        "its", "their", "they", "them", "you", "your", "our", "but", "not", "have", "has", "had",
        "will", "would", "can", "could", "into", "than", "then", "there", "here", "which", "who",
        "what", "when", "where", "why", "how", "all", "any", "some", "such", "only", "also",
        // es
        "los", "las", "una", "unos", "unas", "con", "por", "para", "como", "mas", "sus", "este",
        "esta", "estos", "estas", "eso", "esa", "esos", "esas", "hay", "muy", "sin", "sobre",
        "entre", "cuando", "donde", "porque", "del", "al", "ser", "son", "fue", "era", "tiene",
        "tambien", "todo", "toda", "todos", "todas", "nos", "les", "mi", "mis", "tu", "tus",
        // fr
        "les", "des", "une", "aux", "avec", "pour", "par", "comme", "mais", "ses", "cet", "cette",
        "ces", "ceci", "cela", "sont", "etait", "ete", "avoir", "fait", "plus", "sans", "sur",
        "entre", "quand", "ou", "parce", "dans", "nous", "vous", "ils", "elles", "leur", "leurs",
        "mon", "ma", "mes", "ton", "ta", "tes", "tout", "toute", "tous", "toutes", "peut", "dont"
    )

    // -------------------------------------------------------------- helpers

    private fun truncateAtSentence(text: String, cap: Int): String {
        if (text.length <= cap) return text
        val window = text.substring(0, cap)
        val boundary = maxOf(
            window.lastIndexOf(". "),
            window.lastIndexOf("! "),
            window.lastIndexOf("? "),
            window.lastIndexOf("; ")
        )
        val cut = if (boundary >= (cap * 0.55).toInt()) boundary + 1 else window.lastIndexOf(' ')
        val end = if (cut > 0) cut else cap
        return text.substring(0, end).trimEnd().trimEnd(',', ';', ':') + "…"
    }
}
