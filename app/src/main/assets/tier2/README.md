# Biblioteca Interna do Tier 2

Esta pasta alimenta o motor interno (o que roda **sem LLM/BYOK**). O
recuperador está em `app/src/main/java/com/example/network/Tier2Library.kt`
e é testado por `app/src/test/java/com/example/network/Tier2LibraryTest.kt`.

Regra de ouro: **mande obra inteira, sem cortar**. O motor recorta parágrafos e
escolhe as passagens certas por ranking, então quanto mais texto, melhor.

## 1. Formato do arquivo

| Item | Especificação |
|---|---|
| Extensão | `.md` ou `.txt` (maiúsculas também valem) |
| Codificação | **UTF-8 sem BOM** |
| Nome do arquivo | Livre. Serve de título de reserva se você não usar front matter nem `# Título`. Evite acentos/espaços — o nome entra no índice como identificação. |
| Ignorados | Arquivos que começam com `_` ou `readme` |
| Tamanho | Sem limite prático. Um livro de ~400 páginas em texto puro ≈ 0,6–1 MB. |
| Um arquivo por obra | Sim. Não junte autores diferentes no mesmo arquivo (o arquivo é a unidade de obra no índice e na regra de “2 passagens por obra”). |

## 2. Cabeçalho opcional (front matter)

Serve para organizar o índice internamente (autor nunca é exibido ao usuário).
Tem de estar nas **primeiras linhas** do arquivo, delimitado por `---`:

```
---
title: Ética — Parte III
author: Espinosa
---
```

Chaves reconhecidas: `title` e `author` (também aceita `autor`). Qualquer outra
chave é simplesmente ignorada. Se não houver
front matter, o título vem do primeiro `# Título`; se também não houver, do nome do arquivo.

## 3. Títulos e capítulos (markdown)

- `# Texto` na primeira linha de nível 1: vira o título (quando não há front matter).
- **Todo** `## Capítulo`, `### Seção`, `#### Proposição` funciona como marcador de
  seção: identifica o trecho no índice interno (*trecho 3 da Parte III*) e ajuda o
  ranking, porque acertos no cabeçalho valem mais.
- Os `#` são removidos do texto enviado ao motor. Linhas de sumário, notas de
  rodapé e colofão não atrapalham, mas não ajudam — pode deixá-los.

## 4. Como o texto é cortado

- Um **parágrafo** = bloco separado por uma linha em branco.
- Parágrafos se juntam em **trechos de ~900 caracteres** (máximo 1.400, mínimo 30).
- Um trecho **nunca mistura seções diferentes** — então separe capítulos com `##`.
- Internamente cada trecho fica identificado com obra, seção e posição
  (`trecho 2/117`) — dado que **nunca** aparece na leitura final (ver seção 6).

## 5. Idiomas aceitos

- **Qualquer idioma.** Acentos são dobrados (`consciência` = `consciencia`),
  maiúsculas/minúsculas não importam. Vale para escrita alfabética; em ideogramas
  (chinês, japonês) a segmentação por palavra não existe, então a busca fica
  fraca — o material ainda é indexado, mas casa pouco.
- O glossário de sinônimos cobre ponte **pt ⇄ en ⇄ es ⇄ fr** para conceitos comuns
  (medo/fear/miedo/peur, morte/death/muerte/mort, liberdade/freedom/libertad/liberté,
  consciência/consciousness/consciencia/conscience, vazio/emptiness/vacío/vide, e ~45
  outros grupos). Ou seja: esferas em português conseguem encontrar passagens em inglês.
- A **resposta** ao usuário sai sempre no idioma da interface (pt/en/es/fr); só a
  biblioteca pode estar em outro idioma.
- Em alfabetos não-latinos (grego, cirílico, árabe, japonês) a busca funciona como
  correspondência literal de termos, sem sinônimos.

## 6. O que o motor faz com o material

1. Constrói o índice uma vez, na primeira leitura (custo único, roda em thread de I/O).
2. A cada leitura, usa os conceitos ativos do mapa como consulta.
3. Ranking BM25 sobre os trechos, com expansão por sinônimos e bônus para títulos/seções.
4. Seleciona até **4 passagens**, no máximo **2 por obra** (para um livro não dominar).

### Regra de não-reprodução (obrigatória)

**Nenhuma passagem, frase ou autor da biblioteca aparece na leitura final.**

- **Com LLM (BYOK):** as passagens entram no prompt como *material silencioso*,
  com instrução explícita de nunca transcrever, parafrasear em bloco, citar,
  atribuir, nomear autores/obras ou mencionar que houve consulta. O modelo
  aproveita só o sentido, reescrito na própria voz.
- **Motor interno (sem LLM):** a biblioteca contribui apenas com **vocabulário** —
  até 6 palavras isoladas por leitura, exibidas em *“Fios Conceituais deste Momento”*.
  Nenhuma passagem de texto é mostrada, copiada ou creditada. É por isso que a
  seção de citação no prompt (obra, seção, trecho) é **interna**: serve só para o
  modelo saber que aquilo tem fonte — nunca sai para o usuário.

Se nada casar, **nada é injetado** — melhor vazio do que ruído. Também não há
anexo obrigatório: sem arquivos aqui, o motor usa apenas Wikipedia/Crossref.

## 7. Antes de enviar

- Salve como UTF-8 sem BOM.
- Um arquivo por obra, com front matter (`title`, `author`) quando souber o autor
  (usado só para organizar o índice, nunca exibido).
- Marque capítulos/proposições com `##`/`###`.
- Pode mandar a obra inteira; nenhum corte é necessário.
- Copyright: podem ser obras protegidas. O conteúdo nunca é reproduzido, citado
  nem creditado na leitura entregue ao usuário — entra só como matéria-prima
  silenciosa (LLM) ou como vocabulário isolado (motor interno).
