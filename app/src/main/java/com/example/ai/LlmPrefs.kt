package com.example.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências do motor neural (SharedPreferences dedicado — sem migração de banco).
 * Motor LiteRT-LM (sucessor do MediaPipe): arquivos .litertlm, backend CPU/GPU
 * selecionável em runtime, vários modelos simultâneos (cada um no seu arquivo).
 */
object LlmPrefs {
    private const val FILE = "noesis_llm_prefs"
    const val KEY_STORAGE = "storage" // "internal" | "sd"
    const val KEY_SLOT = "model_slot" // slot ativo
    const val KEY_BACKEND = "backend" // "cpu" | "gpu"
    const val KEY_NOTICE = "model_notice_dismissed"

    const val STORAGE_INTERNAL = "internal"
    const val STORAGE_SD = "sd"

    const val SLOT_360 = "smol360"
    const val SLOT_135 = "smol135"

    const val BACKEND_CPU = "cpu"
    const val BACKEND_GPU = "gpu"

    // Catálogo verificado (litert-community, Apache 2.0, download anônimo OK).
    const val SMOL360_URL =
        "https://huggingface.co/litert-community/SmolLM2-360M-Instruct/resolve/main/SmolLM2_360M_instruct.litertlm"
    const val SMOL360_FILE = "smolm2-360m.litertlm"
    const val SMOL360_MIN_BYTES = 150_000_000L

    const val SMOL135_URL =
        "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm"
    const val SMOL135_FILE = "smolm2-135m.litertlm"
    const val SMOL135_MIN_BYTES = 50_000_000L

    // Arquivos legados da era MediaPipe (.task): removidos automaticamente
    // na primeira inicialização (podem ocupar GBs parados).
    val LEGACY_TASK_FILES = listOf(
        "qwen25-15b-q8.task",
        "qwen25-15b-q8-seq128.task",
        "qwen25-05b-q8.task",
        "smollm-135m-q8.task",
        "gemma3-1b-q4.task",
        "model.task"
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getStorage(context: Context): String =
        prefs(context).getString(KEY_STORAGE, STORAGE_INTERNAL) ?: STORAGE_INTERNAL

    fun setStorage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STORAGE, value).apply()
    }

    fun getSlot(context: Context): String =
        prefs(context).getString(KEY_SLOT, SLOT_360) ?: SLOT_360

    fun setSlot(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SLOT, value).apply()
    }

    fun getBackend(context: Context): String =
        prefs(context).getString(KEY_BACKEND, BACKEND_CPU) ?: BACKEND_CPU

    fun setBackend(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BACKEND, value).apply()
    }

    fun isNoticeDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTICE, false)

    fun setNoticeDismissed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTICE, value).apply()
    }
}
