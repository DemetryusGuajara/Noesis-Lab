package com.example.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Motor neural no aparelho via MediaPipe LLM Inference (.task).
 *
 * Dois slots verificados (litert-community, Apache 2.0, download anônimo OK):
 * - FULL: Qwen2.5-1.5B-Instruct q8 (~1,6 GB) — aparelhos potentes.
 * - LITE: Qwen2.5-0.5B-Instruct q8 (~550 MB) — aparelhos com menos RAM.
 * Destino: armazenamento interno ou cartão SD (app-specific dir).
 */
class LocalLlmEngine(private val context: Context) {

    private var llmInference: LlmInference? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(1800, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val appContext: Context
        get() = context.applicationContext ?: context

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

    private fun slot(): String = LlmPrefs.getSlot(appContext)

    private fun slotUrl(): String = when (slot()) {
        LlmPrefs.SLOT_LITE -> LlmPrefs.LITE_URL
        LlmPrefs.SLOT_ULTRA -> LlmPrefs.ULTRA_URL
        else -> LlmPrefs.FULL_URL
    }

    private fun slotFileName(): String = when (slot()) {
        LlmPrefs.SLOT_LITE -> LlmPrefs.LITE_FILE
        LlmPrefs.SLOT_ULTRA -> LlmPrefs.ULTRA_FILE
        else -> LlmPrefs.FULL_FILE
    }

    private fun slotMinBytes(): Long = when (slot()) {
        LlmPrefs.SLOT_LITE -> LlmPrefs.LITE_MIN_BYTES
        LlmPrefs.SLOT_ULTRA -> LlmPrefs.ULTRA_MIN_BYTES
        else -> LlmPrefs.FULL_MIN_BYTES
    }

    fun slotLabel(): String = when (slot()) {
        LlmPrefs.SLOT_LITE -> "Qwen 2.5 0.5B"
        LlmPrefs.SLOT_ULTRA -> "SmolLM 135M"
        else -> "Qwen 2.5 1.5B"
    }

    /** Há cartão SD com diretório app-specific disponível? */
    fun hasSdCard(): Boolean {
        val dirs = appContext.getExternalFilesDirs(null)
        return dirs.size > 1 && dirs[1] != null
    }

    fun getStorageLabel(): String =
        if (LlmPrefs.getStorage(appContext) == LlmPrefs.STORAGE_SD && hasSdCard()) "SD" else "Interno"

    private fun getModelDir(): File {
        val useSd = LlmPrefs.getStorage(appContext) == LlmPrefs.STORAGE_SD && hasSdCard()
        val base = if (useSd) {
            appContext.getExternalFilesDirs(null)[1] ?: appContext.filesDir
        } else {
            appContext.filesDir
        }
        return File(base, "models")
    }

    private fun getModelFile(): File = File(getModelDir(), slotFileName())

    /**
     * Importa um .task a partir de um stream (seletor de arquivos),
     * copiando para o armazenamento escolhido, com progresso.
     */
    suspend fun importFromStream(
        input: InputStream,
        sizeBytes: Long,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = getModelDir()
            dir.mkdirs()
            val dest = getModelFile()
            val tmp = File(dir, slotFileName() + ".tmp")
            var copied = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    out.write(buffer, 0, len)
                    copied += len
                    val p = if (sizeBytes > 0) copied.toFloat() / sizeBytes else -1f
                    emit(State.Downloading(p, copied / (1024 * 1024), sizeBytes / (1024 * 1024)))
                    onProgress(p, copied, sizeBytes)
                }
            }
            input.close()
            if (tmp.length() < slotMinBytes()) {
                tmp.delete()
                val msg = "Arquivo inválido ou incompleto (${tmp.length()} bytes)."
                emit(State.Error(msg))
                return@withContext Result.failure(IllegalStateException(msg))
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            emit(State.NotStarted)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao importar modelo", e)
            emit(State.Error("Falha ao importar: ${e.message}"))
            Result.failure(e)
        }
    }

    private fun downloadModel(dest: File, url: String) {
        dest.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NoesisLab/1.0 (Android)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} ao baixar o modelo")
            }
            val total = response.body?.contentLength() ?: -1L
            var downloaded = 0L
            val input = response.body?.byteStream() ?: throw RuntimeException("Corpo vazio na resposta")
            val tmp = File(dest.parent, dest.name + ".tmp")
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(65536)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    val p = if (total > 0) downloaded.toFloat() / total else -1f
                    emit(State.Downloading(p, downloaded / (1024 * 1024), total / (1024 * 1024)))
                }
            }
            input.close()
            if (tmp.length() < slotMinBytes()) {
                tmp.delete()
                throw RuntimeException("Download incompleto (${tmp.length()} bytes)")
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        }
    }

    /** Inicializa o modelo (baixa o slot escolhido se necessário, carrega na memória). */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            emit(State.Initializing(0f))
            val modelFile = getModelFile()
            if (!modelFile.exists() || modelFile.length() < slotMinBytes()) {
                emit(State.Downloading(0f, 0, -1L))
                downloadModel(modelFile, slotUrl())
            }
            emit(State.Initializing(0.5f))

            // API oficial MediaPipe. maxTokens=1024: os modelos ekv1280 têm
            // contexto de 1280 tokens — acima disso o processo morre em OOM
            // nativo (sem catch possível). Temperatura = default da lib.
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)

            emit(State.Ready)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar LLM local", e)
            val errorMsg = when (e) {
                is OutOfMemoryError -> "Memória insuficiente para carregar o modelo"
                is java.io.IOException -> "Erro de rede/armazenamento: ${e.message}"
                else -> "Erro ao inicializar: ${e.message ?: e::class.simpleName}"
            }
            emit(State.Error(errorMsg))
            Result.failure(e)
        }
    }

    /** Gera resposta (ChatML do Qwen Instruct). */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        onPartial: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val fullPrompt = buildPrompt(systemPrompt, userMessage)
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

    private fun buildPrompt(system: String, user: String): String {
        return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" +
            user + "<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Verifica se o modelo do slot atual está presente e válido. */
    fun isModelDownloaded(): Boolean {
        val f = getModelFile()
        return f.exists() && f.length() > slotMinBytes()
    }

    /** Tamanho do modelo em MB. */
    fun getModelSizeMb(): Long = getModelFile().length() / (1024 * 1024)

    /** Caminho do arquivo do modelo. */
    fun getModelPath(): String = getModelFile().absolutePath

    /** Remove o modelo do slot atual (libera espaço). */
    fun deleteModel(): Boolean = getModelFile().delete().also {
        if (it) emit(State.NotStarted)
    }

    /** Libera recursos do modelo da memória. */
    fun release() {
        llmInference?.close()
        llmInference = null
        emit(State.NotStarted)
    }

    private fun ensureInitialized() {
        if (llmInference == null) {
            throw IllegalStateException("LLM não inicializado. Chame initialize() primeiro.")
        }
    }
}
