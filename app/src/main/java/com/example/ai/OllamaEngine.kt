package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Cliente do servidor Ollama (endpoints nativos /api/tags, /api/pull e /api/chat).
 *
 * O Ollama é um programa de SERVIDOR (PC/Mac/Linux ou Termux) — ele não roda
 * dentro do app. O app conversa com ele por HTTP:
 * - GET  /api/tags  → verifica se o servidor está no ar e lista modelos
 * - POST /api/pull  → baixa o modelo NO SERVIDOR com progresso (NDJSON)
 * - POST /api/chat (stream:false) → gera a leitura (qwen2.5:1.5b por padrão)
 */
class OllamaEngine(
    var host: String = LlmPrefs.DEFAULT_HOST,
    var model: String = LlmPrefs.DEFAULT_MODEL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun base(): String = host.trim().trimEnd('/')

    /** true se o servidor responde (mesmo sem nenhum modelo instalado). */
    suspend fun checkReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${base()}/api/tags").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Nomes dos modelos já presentes no servidor (ex: "qwen2.5:1.5b"). */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${base()}/api/tags").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = JSONObject(resp.body?.string() ?: "").optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Baixa o modelo NO SERVIDOR (equivale a `ollama pull <modelo>`).
     * onProgress(progress 0..1 ou -1 quando indeterminado, status textual).
     */
    suspend fun pullModel(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("model", model).put("stream", true).toString()
                val req = Request.Builder().url("${base()}/api/pull")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.failure(RuntimeException("HTTP ${resp.code} no pull"))
                    }
                    val stream = resp.body?.byteStream()
                        ?: return@use Result.failure(RuntimeException("resposta vazia"))
                    val reader = BufferedReader(InputStreamReader(stream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val obj = runCatching { JSONObject(line!!) }.getOrNull() ?: continue
                        if (obj.has("error")) {
                            return@use Result.failure(RuntimeException(obj.optString("error")))
                        }
                        val status = obj.optString("status", "")
                        val completed = obj.optLong("completed", -1L)
                        val total = obj.optLong("total", -1L)
                        if (total > 0 && completed >= 0) {
                            onProgress(completed.toFloat() / total, status)
                        } else {
                            onProgress(-1f, status.ifBlank { "baixando..." })
                        }
                        if (status == "success") break
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Gera texto via /api/chat (system + user, sem streaming). */
    suspend fun generate(systemPrompt: String, userMessage: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userMessage))
                    })
                }.toString()
                val req = Request.Builder().url("${base()}/api/chat")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.failure(RuntimeException("HTTP ${resp.code}"))
                    }
                    val content = JSONObject(resp.body?.string() ?: "")
                        .optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim() ?: ""
                    if (content.length < 20) {
                        Result.failure(IllegalStateException("resposta curta do servidor"))
                    } else {
                        Result.success(content)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
