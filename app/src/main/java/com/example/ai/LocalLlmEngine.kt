package com.example.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Local LLM Engine usando MediaPipe LLM Inference com Qwen 2.5 1.5B Instruct (Q4_K_M).
 * 
 * Características:
 * - Modelo ~900 MB (.task file)
 * - Roda 100% offline no dispositivo
 * - Aceleração GPU/NPU via MediaPipe delegates
 * - Multilíngue nativo (PT, EN, ES, FR + mais)
 * - Baixa automática na primeira execução
 */
class LocalLlmEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    
    // Modelo Qwen 2.5 1.5B Instruct Q4_K_M (oficial MediaPipe/HuggingFace)
    private val modelFileName = "qwen2.5-1.5b-instruct-q4_k_m.task"
    private val modelUrl = "https://huggingface.co/second-state/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.task"
    private val expectedSizeBytes = 950_000_000L // ~900 MB
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val TAG = "LocalLlmEngine"
    }

    /** Estado atual do motor */
    sealed interface State {
        data class Downloading(val progress: Float, val downloadedMb: Long, val totalMb: Long) : State
        data class Initializing(val progress: Float) : State
        object Ready : State
        data class Error(val message: String) : State
        object NotStarted : State
    }

    /** Callback para updates de estado */
    var onStateChange: ((State) -> Unit)? = null

    private fun emit(state: State) {
        onStateChange?.invoke(state)
    }

    /** Inicializa o modelo (baixa se necessário, carrega na memória) */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            emit(State.Initializing(0f))
            
            val modelFile = getModelFile()
            
            // Baixa se não existe ou está incompleto
            if (!modelFile.exists() || modelFile.length() < expectedSizeBytes * 0.9) {
                emit(State.Downloading(0f, 0, expectedSizeBytes / (1024 * 1024)))
                downloadModel(modelFile)
            }
            
            emit(State.Initializing(0.5f))
            
            // Carrega no MediaPipe via createFromOptions (API oficial).
            // Nota: setTemperature nao existe no LlmInferenceOptions desta versao
            // (temperatura e configurada por sessao); usa o default da lib.
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            
            emit(State.Initializing(0.9f))
            
            // Warm-up rápido (primeira inferência mais rápida depois)
            try {
                llmInference?.generateResponse("Olá")
            } catch (e: Exception) {
                Log.w(TAG, "Warm-up falhou (não crítico)", e)
            }
            
            emit(State.Ready)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar LLM local", e)
            val errorMsg = when (e) {
                is java.lang.OutOfMemoryError -> "Memória insuficiente para carregar o modelo (precisa ~1.2 GB RAM livre)"
                is java.io.IOException -> "Erro de I/O: ${e.message}"
                else -> "Erro ao inicializar: ${e.message ?: e::class.simpleName}"
            }
            emit(State.Error(errorMsg))
            Result.failure(e)
        }
    }

    /** Gera resposta completa (system prompt + user message) */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        onPartial: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            
            val fullPrompt = buildChatPrompt(systemPrompt, userMessage)
            
            // MediaPipe generateResponse retorna String diretamente (não é streaming callback)
            val generated = llmInference?.generateResponse(fullPrompt) ?: ""
            
            onPartial(generated)
            
            val finalText = generated.trim()
            if (finalText.length < 20) {
                return@withContext Result.failure(IllegalStateException("Resposta muito curta: $finalText"))
            }
            
            Result.success(finalText)
        } catch (e: Exception) {
            Log.e(TAG, "Erro na geração", e)
            Result.failure(e)
        }
    }

    /** Constrói prompt no formato ChatML compatível com Qwen */
    private fun buildChatPrompt(system: String, user: String): String {
        return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Verifica se modelo já foi baixado */
    fun isModelDownloaded(): Boolean = getModelFile().exists() && getModelFile().length() > expectedSizeBytes * 0.9

    /** Tamanho do modelo em MB */
    fun getModelSizeMb(): Long = getModelFile().length() / (1024 * 1024)

    /** Caminho do arquivo do modelo */
    fun getModelPath(): String = getModelFile().absolutePath

    /** Remove o modelo baixado (libera espaço) */
    fun deleteModel(): Boolean = getModelFile().delete().also { 
        if (it) emit(State.NotStarted) 
    }

    /** Libera recursos do modelo da memória */
    fun release() {
        llmInference?.close()
        llmInference = null
        emit(State.NotStarted)
    }

    private fun getModelFile(): File = File(context.filesDir, "models/$modelFileName")

    private fun downloadModel(dest: File) {
        dest.parentFile?.mkdirs()
        
        val request = Request.Builder()
            .url(modelUrl)
            .header("User-Agent", "NoesisLab/1.0 (Android; LocalLLM)")
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${response.message}")
            }
            
            val total = response.body?.contentLength() ?: expectedSizeBytes
            var downloaded = 0L
            val input = response.body?.byteStream() ?: throw RuntimeException("Corpo vazio na resposta")
            val output = FileOutputStream(dest)
            val buffer = ByteArray(8192)
            
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                downloaded += len
                val progress = downloaded.toFloat() / total
                emit(State.Downloading(progress, downloaded / (1024 * 1024), total / (1024 * 1024)))
            }
            output.close()
            
            // Verifica integridade
            if (dest.length() < expectedSizeBytes * 0.9) {
                dest.delete()
                throw RuntimeException("Download incompleto (${dest.length()} bytes)")
            }
        }
    }

    private fun ensureInitialized() {
        if (llmInference == null) {
            throw IllegalStateException("LLM não inicializado. Chame initialize() primeiro.")
        }
    }
}