# Biblioteca Interna do Tier 2

Coloque aqui os textos que alimentam o motor interno (sem BYOK).

## Formato

- Arquivos `.md` ou `.txt` em UTF-8, um por obra/excerto.
- A primeira linha com `# Título` vira o título do documento.
- O restante do texto é usado como base conceitual da leitura.
- Qualquer idioma é aceito; o app responde sempre no idioma da interface.

## Como é usado

O `GroundingAndAiService` lê esta pasta na primeira leitura, pontua os
documentos pelo conceito ativo (sobreposição de termos) e injeta os mais
relevantes no prompt da leitura e na síntese do motor interno.

Exemplo de nome: `spinoza-etica-parte-iii.md`.

Nada aqui é obrigatório: sem arquivos, o motor usa apenas Wikipedia/Crossref.
