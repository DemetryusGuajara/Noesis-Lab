package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier2LibraryTest {

    private fun filler(seed: String, paragraphs: Int): String =
        (1..paragraphs).joinToString("\n\n") {
            "$seed $it. O vento atravessa a areia e a mare sobe lenta sobre as pedras do porto, " +
                "enquanto barcos vazios balancam sem destino nenhum."
        }

    @Test
    fun findsPassageDeepInsideALongWork() {
        val target = "A liberdade nao e a ausencia de limites, mas a consciencia da necessidade " +
            "que nos constitui e nos obriga a escolher."
        val text = buildString {
            append("# Etica\n\n")
            append(filler("Prefacio", 60))
            append("\n\n## Parte III\n\n")
            append(target)
            append("\n\n")
            append(filler("Escolio", 80))
        }
        val corpus = Tier2Library.build(listOf(Tier2Library.Source("etica.md", text)))

        val hits = corpus.search("liberdade necessidade", limit = 4)

        assertTrue("esperava ao menos um trecho", hits.isNotEmpty())
        assertTrue(
            "o trecho certo deveria vir da pagina perdida no meio do livro",
            hits.first().text.contains("consciencia da necessidade")
        )
        assertEquals("Parte III", hits.first().section)
    }

    @Test
    fun matchesAcrossLanguagesThroughTheSynonymGlossary() {
        val pt = "# Medo\n\nO medo paralisa o corpo antes mesmo de qualquer ameaca concreta aparecer."
        val en = "# Fear\n\nFear is the mind reacting to a future it cannot control, a shadow cast by thought."
        val corpus = Tier2Library.build(
            listOf(
                Tier2Library.Source("medo.md", pt + "\n\n" + filler("Corpo", 6)),
                Tier2Library.Source("fear.md", en + "\n\n" + filler("Mind", 6))
            )
        )

        val fromPortuguese = corpus.search("medo")
        val fromFrench = corpus.search("peur")

        assertTrue(fromPortuguese.any { it.text.contains("Fear is the mind") })
        assertTrue(fromFrench.any { it.title == "Fear" })
    }

    @Test
    fun respectsDocumentDiversityAndLimits() {
        val corpus = Tier2Library.build(
            listOf(
                Tier2Library.Source("a.md", "# A\n\nA morte chega como interrupcao de todo projeto."),
                Tier2Library.Source("b.md", "# B\n\nA morte e o horizonte contra o qual a vida ganha forma."),
                Tier2Library.Source("c.md", "# C\n\nPensar a morte e aprender a viver com lucidez.")
            )
        )

        val hits = corpus.search("morte", limit = 3, perDocLimit = 1)

        assertEquals(3, hits.size)
        assertEquals(3, hits.map { it.docId }.distinct().size)
    }

    @Test
    fun readsFrontMatterAndCitesWorkAndPosition() {
        val text = """
            ---
            title: Etica — Parte III
            author: Espinosa
            ---
            ## Proposicao 6

            Uma coisa esforca-se por perseverar no seu ser, e esse esforco e o proprio desejo de existir.

            ## Proposicao 7

            O desejo e a propria essencia do homem enquanto determinada a agir.
        """.trimIndent()
        val corpus = Tier2Library.build(listOf(Tier2Library.Source("spinoza.md", text)))

        val rendered = corpus.render("desejo essencia", maxChars = 600)

        assertTrue(rendered.contains("Espinosa — Etica — Parte III"))
        assertTrue(rendered.contains("Proposicao"))
        assertTrue(rendered.contains("trecho "))
    }

    @Test
    fun returnsNothingWhenTheLibraryHasNoRelevantPassage() {
        val corpus = Tier2Library.build(
            listOf(Tier2Library.Source("a.md", "# A\n\nO vento atravessa a areia e a mare sobe lenta."))
        )

        assertTrue(corpus.search("criptomoeda blockchain").isEmpty())
        assertEquals("", corpus.render("criptomoeda blockchain"))
    }

    @Test
    fun renderStaysInsideTheCharacterBudget() {
        val text = "# Livro\n\n" + (1..40).joinToString("\n\n") {
            "Paragrafo $it sobre a consciencia e o corpo, escrito para ocupar espaco no orcamento."
        }
        val corpus = Tier2Library.build(listOf(Tier2Library.Source("livro.md", text)))

        val rendered = corpus.render("consciencia corpo", maxChars = 480)

        assertTrue(rendered.isNotEmpty())
        assertTrue("saiu ${rendered.length} chars", rendered.length <= 480)
    }

    @Test
    fun themesGiveVocabularyWithoutReproducingText() {
        val corpus = Tier2Library.build(
            listOf(
                Tier2Library.Source(
                    "a.md",
                    "# Medo\n\nO medo cresce na incerteza e na fragilidade do corpo, quando a " +
                        "circunstancia parece maior que a resposta disponivel."
                )
            )
        )

        val themes = corpus.themes("medo")

        assertTrue(themes.isNotEmpty())
        assertTrue("viraram frases: $themes", themes.none { it.contains(" ") })
        assertTrue("termo da consulta voltou: $themes", themes.none { it == "medo" })
        assertTrue(themes.all { it.length >= 5 })
    }

    @Test
    fun themesAreEmptyWhenNothingMatches() {
        val corpus = Tier2Library.build(
            listOf(Tier2Library.Source("a.md", "# A\n\nO vento atravessa a areia e a mare sobe lenta."))
        )

        assertTrue(corpus.themes("criptomoeda blockchain").isEmpty())
    }

    @Test
    fun emptyLibraryStaysEmpty() {
        val corpus = Tier2Library.build(emptyList())

        assertTrue(corpus.isEmpty)
        assertTrue(corpus.search("qualquer coisa").isEmpty())
        assertEquals("", corpus.render("qualquer coisa"))
    }

    @Test
    fun foldingIgnoresAccentsAndCase() {
        val corpus = Tier2Library.build(
            listOf(Tier2Library.Source("a.md", "# A\n\nA consciência desperta quando o corpo silencia."))
        )

        assertTrue(corpus.search("CONSCIENCIA").isNotEmpty())
        assertTrue(corpus.search("consciência").isNotEmpty())
        assertNotNull(corpus.search("consciencia").firstOrNull())
    }
}
