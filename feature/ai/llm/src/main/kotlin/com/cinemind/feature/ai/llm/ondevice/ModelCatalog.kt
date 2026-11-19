package com.cinemind.feature.ai.llm.ondevice

/** A downloadable on-device model. Files come straight from the publisher's Hugging Face repo. */
data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** SHA-256 of the file as published (Hugging Face LFS oid); checked after download. */
    val sha256: String,
    val license: String,
    val summary: String,
    val supportsImages: Boolean,
    /** Accepts speech audio (Gemma 4 E2B/E4B have an audio encoder). Used for speech-to-text. */
    val supportsAudio: Boolean = false,
    /**
     * Runtime file format. Bump only when a runtime upgrade can no longer read files verified by an
     * older app; installed models of another format are kept until a replacement is verified.
     */
    val format: String = "litertlm-1",
    /**
     * A text-only reasoning model (thinks before answering) used for planning and decisions; it
     * never replaces the picture / speech model, it is chosen separately.
     */
    val reasoning: Boolean = false,
    /** Typical decode speed on a flagship phone GPU (from the publisher's measurements). */
    val tokensPerSecond: Float = 25f,
)

/**
 * Models offered in the app. Chosen because they run on Google's LiteRT-LM runtime with GPU
 * acceleration, accept images, are Apache-2.0 and need no account to download.
 * The master spec's preferred Qwen2.5-VL-7B has no LiteRT-LM build and is too heavy for a phone at
 * present; the benchmark screen exists so the choice is made from real numbers on the device.
 */
object ModelCatalog {

    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b",
        name = "Gemma 4 E2B (recommended)",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        license = "Apache-2.0 (Google Gemma 4)",
        summary = "Understands images, speech and text. Fast on phone GPUs; about 2.6 GB.",
        supportsImages = true,
        supportsAudio = true,
    )

    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b",
        name = "Gemma 4 E4B (higher quality)",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        license = "Apache-2.0 (Google Gemma 4)",
        summary = "Better descriptions, slower and warmer; about 3.7 GB. Benchmark before relying on it.",
        supportsImages = true,
        supportsAudio = true,
    )

    /**
     * Spark X2.6 1.7B (iFLYTEK SparkLLM, Apache-2.0), LiteRT-LM build by litert-community made for
     * LiteRT-LM 0.19 (our runtime is 0.19.1). Measured by the publisher: 27 tok/s on a Galaxy S26
     * GPU. Spark X2.6 **4B** has no LiteRT-LM build (GGUF / MLX only), so it is not offered.
     */
    val SPARK_X26_1_7B = ModelSpec(
        id = "spark-x2.6-1.7b",
        name = "Spark X2.6 1.7B (planning, recommended)",
        fileName = "Spark-X2.6-1.7B_int4-mlp.litertlm",
        url = "https://huggingface.co/litert-community/Spark-X2.6-1.7B/resolve/main/Spark-X2.6-1.7B_int4-mlp.litertlm",
        sizeBytes = 1_705_918_464L,
        sha256 = "c0ce1e9e80a7593c2492ff00d15ca49c182b95c7dfab5c805d4204fa07c14225",
        license = "Apache-2.0 (iFLYTEK SparkLLM)",
        summary = "Thinks through edit plans and decisions (text only). Fast on phone GPUs; about 1.7 GB.",
        supportsImages = false,
        reasoning = true,
        tokensPerSecond = 27f,
    )

    /** Spark X2.5 4B, LiteRT-LM build (tested by its publisher on LiteRT-LM 0.17). Stronger but ~5 tok/s on phones. */
    val SPARK_X25_4B = ModelSpec(
        id = "spark-x2.5-4b",
        name = "Spark X2.5 4B (planning, slow)",
        fileName = "Spark-X2.5-4B_int4.litertlm",
        url = "https://huggingface.co/litert-community/Spark-X2.5-4B/resolve/main/Spark-X2.5-4B_int4.litertlm",
        sizeBytes = 2_482_290_688L,
        sha256 = "0912969b1e7dc6de503d28884ee6611d2b10e1ab6a9c3682457e86b225072a48",
        license = "Apache-2.0 (iFLYTEK SparkLLM)",
        summary = "Stronger reasoning, but only about 5 words a second on phones; about 2.5 GB. For comparison in the benchmark.",
        supportsImages = false,
        reasoning = true,
        tokensPerSecond = 5f,
    )

    val ALL: List<ModelSpec> = listOf(GEMMA_4_E2B, GEMMA_4_E4B, SPARK_X26_1_7B, SPARK_X25_4B)

    /** Models that see and hear (Gemma). */
    val VISUAL: List<ModelSpec> get() = ALL.filter { !it.reasoning }

    /** Planning / reasoning models (Spark). */
    val PLANNERS: List<ModelSpec> get() = ALL.filter { it.reasoning }

    fun byId(id: String?): ModelSpec? = ALL.firstOrNull { it.id == id }
}

/** Pure helpers for downloads (unit-tested). */
object DownloadMath {

    /** Total size from a `Content-Range: bytes 100-199/1000` header, or null. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    fun fraction(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** Bytes needed free before starting: the rest of the file plus a 500 MB safety margin. */
    fun requiredFreeBytes(totalBytes: Long, alreadyDownloaded: Long): Long =
        (totalBytes - alreadyDownloaded).coerceAtLeast(0) + 500L * 1024 * 1024

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
