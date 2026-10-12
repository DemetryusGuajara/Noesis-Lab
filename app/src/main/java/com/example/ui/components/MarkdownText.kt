package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Renders Markdown cleanly in Jetpack Compose so raw symbols like `###`, `##`, `**bold**`,
 * `*italic*`, and `- ` bullets never appear raw to the user (Item 11).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val lines = markdown.lines()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                continue
            }

            when {
                line.startsWith("### ") -> {
                    val headerText = line.removePrefix("### ").trim()
                    Text(
                        text = parseInlineMarkdown(headerText, accentColor),
                        style = MaterialTheme.typography.titleLarge,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                line.startsWith("## ") -> {
                    val headerText = line.removePrefix("## ").trim()
                    Text(
                        text = parseInlineMarkdown(headerText, accentColor),
                        style = MaterialTheme.typography.headlineSmall,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                line.startsWith("# ") -> {
                    val headerText = line.removePrefix("# ").trim()
                    Text(
                        text = parseInlineMarkdown(headerText, accentColor),
                        style = MaterialTheme.typography.headlineMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    val bulletBody = line.drop(2).trim()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = style,
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(bulletBody, accentColor),
                            style = style,
                            color = color,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(line, accentColor),
                        style = style,
                        color = color
                    )
                }
            }
        }
    }
}

fun parseInlineMarkdown(text: String, boldHighlightColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val endBold = text.indexOf("**", startIndex = i + 2)
                if (endBold != -1) {
                    val content = text.substring(i + 2, endBold)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldHighlightColor))
                    append(content)
                    pop()
                    i = endBold + 2
                    continue
                }
            }
            if (text[i] == '*') {
                val endItalic = text.indexOf('*', startIndex = i + 1)
                if (endItalic != -1) {
                    val content = text.substring(i + 1, endItalic)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(content)
                    pop()
                    i = endItalic + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}
