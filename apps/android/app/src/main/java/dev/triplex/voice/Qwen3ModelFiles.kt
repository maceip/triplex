package dev.triplex.voice

/**
 * On-device Qwen3-TTS LiteRT + ECAPA bundle contract.
 *
 * Release ships these via the `qwen3_tts` Play Asset Delivery pack.
 * Debug downloads (or adb-pushes) them into `filesDir/models/qwen3-tts`.
 * Engines always read a flat directory of these basenames.
 */
object Qwen3ModelFiles {
    const val PACK_NAME = "qwen3_tts"
    const val RELATIVE_DIR = "models/qwen3-tts"
    const val DEFAULT_HF_BASE =
        "https://huggingface.co/litert-community/Qwen3-TTS-12Hz-0.6B-Base/resolve/main"

    /**
     * Basename written under [RELATIVE_DIR] → relative path inside the source
     * cache / Hugging Face repo (tables stay nested on the source side).
     */
    val ARTIFACTS: List<Pair<String, String>> = listOf(
        "talker_int4.tflite" to "talker_int4.tflite",
        "mtp_folded_int8.tflite" to "mtp_folded_int8.tflite",
        "codec_partA.tflite" to "codec_partA.tflite",
        "codec_partB.tflite" to "codec_partB.tflite",
        "vocab.json" to "vocab.json",
        "merges.txt" to "merges.txt",
        "codec_embedding_fp32.npy" to "tables/codec_embedding_fp32.npy",
        "mtp_embeddings_fp16.npy" to "tables/mtp_embeddings_fp16.npy",
        "text_embedding_fp16.npy" to "tables/text_embedding_fp16.npy",
        "text_projection_fp32.npz" to "tables/text_projection_fp32.npz",
        "speaker_encoder.tflite" to "speaker_encoder.tflite",
        "mel_basis_slaney_128.npy" to "mel_basis_slaney_128.npy",
    )

    val REQUIRED_BASENAMES: List<String> = ARTIFACTS.map { it.first }

    fun isComplete(dir: java.io.File): Boolean =
        dir.isDirectory && REQUIRED_BASENAMES.all { java.io.File(dir, it).isFile }
}
