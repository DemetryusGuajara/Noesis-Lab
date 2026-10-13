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
    const val KEY_NOTICE = "model_notice_dismissed"

    const val STORAGE_INTERNAL = "internal"
    const val STORAGE_SD = "sd"

    // Slots do modelo no aparelho (.task MediaPipe, litert-community, Apache 2.0).
    // URLs verificadas: download anônimo liberado (gated:false).
    const val SLOT_FULL = "full"
    const val SLOT_LITE = "lite"
    const val SLOT_ULTRA = "ultra"
    const val KEY_SLOT = "model_slot"

    const val FULL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task"
    const val FULL_FILE = "qwen25-15b-q8-seq128.task"
    const val FULL_MIN_BYTES = 500_000_000L
    // Arquivo legado (variante GPU multi-prefill): removido automaticamente.
    const val LEGACY_FULL_FILE = "qwen25-15b-q8.task"

    const val LITE_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val LITE_FILE = "qwen25-05b-q8.task"
    const val LITE_MIN_BYTES = 150_000_000L

    const val ULTRA_URL =
        "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val ULTRA_FILE = "smollm-135m-q8.task"
    const val ULTRA_MIN_BYTES = 50_000_000L

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

    fun isNoticeDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTICE, false)

    fun setNoticeDismissed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTICE, value).apply()
    }
}
