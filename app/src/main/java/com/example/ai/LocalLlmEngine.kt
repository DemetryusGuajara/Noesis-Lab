package com.example.ai

import android.content.Context
import android.util.Log
import com.example.util.AppLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Motor neural no aparelho via LiteRT-LM (sucessor do MediaPipe LLM Inference).
 * Padrão de uso copiado do app oficial Google AI Edge Gallery:
 * Engine(EngineConfig) -> initialize() -> createConversation() -> sendMessage().
 *
 * - Arquivos .litertlm (vários podem coexistir; o slot ativo é escolhido pelo usuário).
 * - Backend CPU/GPU selecionável em runtime (padrão CPU = seguro em Mali antigas).
 * - SmolLM2 usa ChatML; o runtime aplica o template a partir de texto simples.
 */
class LocalLlmEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

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
        LlmPrefs.SLOT_135 -> LlmPrefs.SMOL135_URL
        else -> LlmPrefs.SMOL360_URL
    }

    private fun slotFileName(): String = when (slot()) {
        LlmPrefs.SLOT_135 -> LlmPrefs.SMOL135_FILE
        else -> LlmPrefs.SMOL360_FILE
    }

    private fun slotMinBytes(): Long = when (slot()) {
        LlmPrefs.SLOT_135 -> LlmPrefs.SMOL135_MIN_BYTES
        else -> LlmPrefs.SMOL360_MIN_BYTES
    }

    fun slotLabel(): String = when (slot()) {
        LlmPrefs.SLOT_135 -> "SmolLM2 135M"
        else -> "SmolLM2 360M"
    }

    fun backendLabel(): String =
        if (LlmPrefs.getBackend(appContext) == LlmPrefs.BACKEND_GPU) "GPU" else "CPU"

    /** Há cartão SD com diretório app-specific disponível? */
    fun hasSdCard(): Boolean {
        val dirs = appContext.getExternalFilesDirs(null)
        return dirs.size > 1 && dirs[1] != null
    }

    fun getStorageLabel(): String =
        if (LlmPrefs.getStorage(appContext) == LlmPrefs.STORAGE_SD && hasSdCard()) "SD" else "Interno"

    private fun modelDirs(): List<File> {
        val internal = File(appContext.filesDir, "models")
        val sd = appContext.getExternalFilesDirs(null).getOrNull(1)?.let { File(it, "models") }
        return listOfNotNull(internal, sd)
    }

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

    /** Lista todos os modelos .litertlm presentes (qualquer slot/storage). */
    fun listDownloadedModels(): List<String> {
        return try {
            modelDirs().flatMap { dir ->
                dir.listFiles { f -> f.name.endsWith(".litertlm") }?.map { it.name } ?: emptyList()
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Remove arquivos legados da era MediaPipe (.task) que só ocupam espaço. */
    fun cleanupLegacyTaskFiles(): Long {
        var freed = 0L
        try {
            modelDirs().forEach { dir ->
                LlmPrefs.LEGACY_TASK_FILES.forEach { name ->
                    val f = File(dir, name)
                    if (f.exists()) {
                        freed += f.length()
                        f.delete()
                    }
                }
            }
            if (freed > 0) AppLog.i("llm", "legacy cleanup freed ${freed / (1024 * 1024)} MB")
        } catch (e: Exception) {
            Log.w(TAG, "legacy cleanup failed", e)
        }
        return freed
    }

    /**
     * Importa um .litertlm a partir de um stream (seletor de arquivos),
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
            AppLog.i("llm", "init slot=${slotLabel()} backend=${backendLabel()} storage=${getStorageLabel()}")
            AppLog.markGenStart("init slot=${slotLabel()}")
            emit(State.Initializing(0f))
            cleanupLegacyTaskFiles()
            val modelFile = getModelFile()
            if (!modelFile.exists() || modelFile.length() < slotMinBytes()) {
                emit(State.Downloading(0f, 0, -1L))
                downloadModel(modelFile, slotUrl())
            }
            emit(State.Initializing(0.5f))

            releaseQuiet()
            val backend = if (LlmPrefs.getBackend(appContext) == LlmPrefs.BACKEND_GPU) {
                Backend.GPU()
            } else {
                Backend.CPU()
            }
            val eng = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxNumTokens = 2048
                )
            )
            eng.initialize()
            val conv = eng.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                )
            )
            engine = eng
            conversation = conv

            emit(State.Ready)
            AppLog.i("llm", "ready ${getModelSizeMb()} MB backend=${backendLabel()}")
            AppLog.clearGenMarker()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar LLM local", e)
            val errorMsg = when (e) {
                is OutOfMemoryError -> "Memória insuficiente para carregar o modelo"
                is java.io.IOException -> "Erro de rede/armazenamento: ${e.message}"
                else -> "Erro ao inicializar: ${e.message ?: e::class.simpleName}"
            }
            emit(State.Error(errorMsg))
            AppLog.e("llm", "init failed: $errorMsg")
            AppLog.clearGenMarker()
            releaseQuiet()
            Result.failure(e)
        }
    }

    /** Gera resposta (texto simples; o runtime aplica o template de conversa). */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        onPartial: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conv = conversation
                ?: throw IllegalStateException("LLM não inicializado. Chame initialize() primeiro.")
            val fullPrompt = buildPrompt(systemPrompt, userMessage)
            val generated = conv.sendMessage(fullPrompt).toString().trim()
            onPartial(generated)
            if (generated.length < 20) {
                return@withContext Result.failure(IllegalStateException("Resposta muito curta: $generated"))
            }
            Result.success(generated)
        } catch (e: Exception) {
            Log.e(TAG, "Erro na geração", e)
            AppLog.e("llm", "generate failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun buildPrompt(system: String, user: String): String {
        return "Instruções do sistema:\n" + system +
            "\n\nUsuário:\n" + user + "\n\nResposta:"
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
        releaseQuiet()
        emit(State.NotStarted)
    }

    private fun releaseQuiet() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        conversation = null
        engine = null
    }
}
