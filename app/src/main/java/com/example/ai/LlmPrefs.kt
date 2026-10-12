package com.example.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências do motor neural (SharedPreferences dedicado — sem migração de banco).
 * Centraliza: escolha de armazenamento, host/modelo Ollama e flag do aviso inicial.
 */
object LlmPrefs {
    private const val FILE = "noesis_llm_prefs"
    const val KEY_STORAGE = "storage" // "internal" | "sd"
    const val KEY_HOST = "ollama_host"
    const val KEY_MODEL = "ollama_model"
    const val KEY_NOTICE = "model_notice_dismissed"

    const val STORAGE_INTERNAL = "internal"
    const val STORAGE_SD = "sd"

    const val DEFAULT_HOST = "http://localhost:11434"
    const val DEFAULT_MODEL = "qwen2.5:1.5b"

    // Slots do modelo no aparelho (.task MediaPipe, litert-community, Apache 2.0).
    // URLs verificadas: download anônimo liberado (gated:false).
    const val SLOT_FULL = "full"
    const val SLOT_LITE = "lite"
    const val KEY_SLOT = "model_slot"

    const val FULL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val FULL_FILE = "qwen25-15b-q8.task"
    const val FULL_MIN_BYTES = 500_000_000L

    const val LITE_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val LITE_FILE = "qwen25-05b-q8.task"
    const val LITE_MIN_BYTES = 150_000_000L

    fun getSlot(context: Context): String =
        prefs(context).getString(KEY_SLOT, SLOT_FULL) ?: SLOT_FULL

    fun setSlot(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SLOT, value).apply()
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getStorage(context: Context): String =
        prefs(context).getString(KEY_STORAGE, STORAGE_INTERNAL) ?: STORAGE_INTERNAL

    fun setStorage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STORAGE, value).apply()
    }

    fun getHost(context: Context): String =
        prefs(context).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun setHost(context: Context, value: String) {
        prefs(context).edit().putString(KEY_HOST, value.trim().ifBlank { DEFAULT_HOST }).apply()
    }

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()
    }

    fun isNoticeDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTICE, false)

    fun setNoticeDismissed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTICE, value).apply()
    }
}
