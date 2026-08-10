/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.triplex.speech.tts.qwen3

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Qwen3-TTS on LiteRT: the host-orchestrated Compiled Model decode loop.
 *
 * Runs the optimized graph set: talker LM (prefill_32/decode signatures,
 * KV 1024), folded dynamic-int8 MTP (one invocation per audio frame), and the
 * split codec decoder (64-frame chunks -> 24 kHz PCM) — plus host-side BPE
 * tokenization, embedding-table lookups, prompt assembly, and sampling. It is
 * a Kotlin port of the Python reference pipeline in the sibling `python/`
 * directory, which reproduces the PyTorch implementation token-for-token
 * under greedy decoding.
 *
 * All model files live in [dir] (pushed by `install_to_device.sh`).
 */
class Qwen3TtsEngine(private val dir: File) {

    companion object {
        private const val TAG = "Qwen3Tts"
        private const val HIDDEN = 1024
        private const val CODEC_VOCAB = 3072
        private const val CACHE = 1024
        private const val NEG = -1e9f
        private const val EOS = 2150
        private const val PAD_ID = 2148
        private const val BOS_ID = 2149
        private const val THINK = 2154
        private const val THINK_BOS = 2156
        private const val THINK_EOS = 2157
        private const val NOTHINK = 2155
        private const val TTS_BOS = 151672
        private const val TTS_EOS = 151673
        private const val TTS_PAD = 151671
        private const val MTP_LAYERS = 5
        private const val MTP_CACHE = 17
        private const val MTP_KV_FLOATS = MTP_LAYERS * 8 * MTP_CACHE * 128
        private const val MTP_VOCAB = 2048
        private const val CODEC_CHUNK = 64
        private const val CODEC_CTX = 25
        private const val UPSAMPLE = 1920
        private const val XNNPACK_FORCE_FP16 = 4
        const val SAMPLE_RATE = 24000
        const val MAX_FRAMES = 512
        /** Emit PCM to the streaming sink about every 64 codec frames (~5.1 s @ 12.5 Hz). */
        const val STREAM_EMIT_FRAMES = 64

        // <|im_start|>assistant\n   and   <|im_end|>\n<|im_start|>assistant\n
        private val PROMPT_PREFIX = intArrayOf(151644, 77091, 198)
        private val PROMPT_SUFFIX = intArrayOf(151645, 198, 151644, 77091, 198)

        val LANGUAGE_IDS = mapOf(
            "chinese" to 2055, "english" to 2050, "german" to 2053,
            "italian" to 2070, "portuguese" to 2071, "spanish" to 2054,
            "japanese" to 2058, "korean" to 2064, "french" to 2061,
            "russian" to 2069,
        )
    }

    private val tokenizer =
        QwenBpeTokenizer(File(dir, "vocab.json"), File(dir, "merges.txt"))

    /** Exposes plain-text tokenization for the startup self-test. */
    fun encodeText(text: String): IntArray = tokenizer.encode(text)

    // Host tables. The two big ones stay memory-mapped fp16.
    private val codecEmb = Npy.loadFloats(File(dir, "codec_embedding_fp32.npy"))
    private val mtpEmb: ShortBuffer = Npy.mmapHalf(File(dir, "mtp_embeddings_fp16.npy"))
    private val textEmb: ShortBuffer = Npy.mmapHalf(File(dir, "text_embedding_fp16.npy"))
    private val proj = Npy.loadNpz(
        File(dir, "text_projection_fp32.npz"), listOf("w1", "b1", "w2", "b2"))
    val speaker: FloatArray by lazy {
        val f = File(dir, "demo_speaker.npy")
        if (f.isFile) Npy.loadFloats(f) else FloatArray(HIDDEN)
    }

    private fun load(
        name: String,
        threads: Int,
        xnnPackFlags: Int? = null,
    ): CompiledModel {
        val f = File(dir, name)
        check(f.exists()) { "Model not found: $name; stage the optimized bundle first" }
        val options = CompiledModel.Options(Accelerator.CPU)
        options.cpuOptions = CompiledModel.CpuOptions(threads, xnnPackFlags, null)
        return CompiledModel.create(f.absolutePath, options, null)
    }

    private val talker = load("talker_int4.tflite", 4)
    private val mtp = load("mtp_folded_int8.tflite", 4)
    private val codecA = load("codec_partA.tflite", 4)
    private val codecB = load("codec_partB.tflite", 4, XNNPACK_FORCE_FP16)

    private val kvNames = (0 until 28).flatMap {
        listOf("kv_cache_k_$it", "kv_cache_v_$it")
    }

    data class Result(
        val audio: FloatArray, val frames: Int,
        val prefillMs: Long, val talkerMs: Long, val mtpMs: Long,
        val codecMs: Long,
    )

    interface Progress { fun onFrame(frame: Int) }

    fun interface PcmSink {
        /** Receives 24 kHz float PCM; return false to abort generation. */
        fun onPcm(samples: FloatArray): Boolean
    }

    /**
     * Synthesizes [text] in the voice of [spk] (1024-d x-vector).
     *
     * With [greedy], generation is deterministic. Otherwise the talker uses
     * top-50 sampling and folded MTP uses temperature-0.9 Gumbel sampling.
     *
     * When [pcmSink] is non-null, codec decode runs every [CODEC_CHUNK] frames
     * and PCM is delivered incrementally (streaming path).
     */
    fun synthesize(
        text: String, language: String = "english",
        spk: FloatArray = speaker, greedy: Boolean = false,
        seed: Long? = null, progress: Progress? = null,
        pcmSink: PcmSink? = null,
    ): Result {
        val rnd = if (seed != null) Random(seed) else Random()

        // ---- prompt assembly (host-side, plain lookups + the tiny MLP) ----
        val textIds = tokenizer.encode(text)
        require(textIds.isNotEmpty()) { "empty text" }
        val ttsBos = embedText(intArrayOf(TTS_BOS))[0]
        val ttsEos = embedText(intArrayOf(TTS_EOS))[0]
        val ttsPad = embedText(intArrayOf(TTS_PAD))[0]

        val control = if (language == "auto") {
            intArrayOf(NOTHINK, THINK_BOS, THINK_EOS)
        } else {
            val lang = LANGUAGE_IDS[language.lowercase()]
                ?: throw IllegalArgumentException("language: $language")
            intArrayOf(THINK, THINK_BOS, lang, THINK_EOS)
        }
        // control embeds | speaker | pad,bos  -> [C+3, 1024]
        val codecPre = ArrayList<FloatArray>()
        for (id in control) {
            codecPre.add(codecRow(id))
        }
        codecPre.add(spk.copyOf())
        codecPre.add(codecRow(PAD_ID))
        codecPre.add(codecRow(BOS_ID))

        val role = embedText(PROMPT_PREFIX)                      // [3,1024]
        val body = Array(codecPre.size - 1) { i ->              // pads+bos + codecPre[:-1]
            val cond = if (i < codecPre.size - 2) ttsPad else ttsBos
            add(cond, codecPre[i])
        }
        val firstText = add(embedText(intArrayOf(textIds[0]))[0], codecPre.last())
        val prefill = role + body + arrayOf(firstText)           // [P,1024]
        val trailing = ArrayList<FloatArray>()                   // streamed text cond
        for (i in 1 until textIds.size) {
            trailing.add(embedText(intArrayOf(textIds[i]))[0])
        }
        trailing.add(ttsEos)

        // ---- talker prefill + first decode ----
        var t0 = System.nanoTime()
        val decodeKv = TalkerState()
        decodeKv.prefill(prefill)
        var pos = prefill.size - 1
        var step = decodeKv.decode(prefill.last(), pos)
        val prefillMs = (System.nanoTime() - t0) / 1_000_000

        // ---- frame loop ----
        val suppress = FloatArray(CODEC_VOCAB)
        for (i in 2048 until CODEC_VOCAB) {
            suppress[i] = NEG
        }
        suppress[EOS] = 0f

        val frames = ArrayList<IntArray>()
        val history = HashSet<Int>()
        var talkerNs = 0L
        var mtpNs = 0L
        var codecNs = 0L
        var decodedThrough = 0
        var aborted = false
        while (frames.size < MAX_FRAMES) {
            val scores = FloatArray(CODEC_VOCAB) { step.logits[it] + suppress[it] }
            if (frames.size < 2) {
                scores[EOS] = NEG // min_new_tokens = 2
            }
            for (t in history) {
                scores[t] = if (scores[t] > 0) scores[t] / 1.05f else scores[t] * 1.05f
            }
            val cb0 = pick(scores, greedy, rnd)
            history.add(cb0)
            if (cb0 == EOS) break

            t0 = System.nanoTime()
            val residual = mtpFrame(step.hidden, cb0, greedy, rnd)
            mtpNs += System.nanoTime() - t0

            val frame = IntArray(16)
            frame[0] = cb0
            for (i in 0 until 15) {
                frame[i + 1] = residual[i]
            }
            frames.add(frame)
            progress?.onFrame(frames.size)

            if (pcmSink != null && frames.size - decodedThrough >= STREAM_EMIT_FRAMES) {
                t0 = System.nanoTime()
                val chunk = decodeCodesRange(frames, decodedThrough, frames.size)
                codecNs += System.nanoTime() - t0
                decodedThrough = frames.size
                if (!pcmSink.onPcm(chunk)) {
                    aborted = true
                    break
                }
            }

            // next input embed = sum of 16 codebook embeds + text conditioning
            val embed = codecRow(cb0)
            for (i in 0 until 15) {
                addMtpRow(embed, i, residual[i])
            }
            val stepIdx = frames.size - 1
            val cond = if (stepIdx < trailing.size) trailing[stepIdx] else ttsPad
            for (i in 0 until HIDDEN) {
                embed[i] += cond[i]
            }

            pos += 1
            t0 = System.nanoTime()
            step = decodeKv.decode(embed, pos)
            talkerNs += System.nanoTime() - t0
        }

        // ---- codec decode (remaining / full) ----
        t0 = System.nanoTime()
        val audio = if (pcmSink != null) {
            if (!aborted && decodedThrough < frames.size) {
                val tail = decodeCodesRange(frames, decodedThrough, frames.size)
                pcmSink.onPcm(tail)
            }
            FloatArray(0)
        } else {
            decodeCodes(frames)
        }
        codecNs += System.nanoTime() - t0

        return Result(audio, frames.size, prefillMs,
            talkerNs / 1_000_000, mtpNs / 1_000_000, codecNs / 1_000_000)
    }

    /** Decode frames[from, to) with left context from earlier frames. */
    private fun decodeCodesRange(
        frames: List<IntArray>,
        from: Int,
        to: Int,
    ): FloatArray {
        if (from >= to) return FloatArray(0)
        // Reuse full decoder but only keep the newly generated samples.
        val full = decodeCodes(frames.subList(0, to))
        // Approximate: decodeCodes already strips left context per chunk.
        // For streaming we re-decode the prefix window; trim previously emitted
        // samples by counting UPSAMPLE * from.
        val skip = from * UPSAMPLE
        return if (skip >= full.size) FloatArray(0) else full.copyOfRange(skip, full.size)
    }

    // ------------------------------------------------------------------
    // Talker: prefill_32 + decode signatures with ping-pong KV buffers.
    // ------------------------------------------------------------------
    private inner class TalkerState {
        // Two full KV sets; run() alternates them as inputs/outputs to avoid
        // copying ~235 MB of cache per step.
        val setA = kvNames.associateWith { talker.createOutputBuffer(it, "decode") }
        val setB = kvNames.associateWith { talker.createOutputBuffer(it, "decode") }
        var current = setA // holds the cache AFTER the latest step

        val embIn = talker.createInputBuffer("embeddings", "decode")
        val posIn = talker.createInputBuffer("input_pos", "decode")
        val maskIn = talker.createInputBuffer("mask", "decode")
        val logitsOut = talker.createOutputBuffer("logits", "decode")
        val mask = FloatArray(CACHE) { NEG }

        fun prefill(embeds: Array<FloatArray>) {
            val p = embeds.size
            check(p <= 32) { "prompt too long for prefill_32: $p" }
            val flat = FloatArray(32 * HIDDEN)
            for (t in embeds.indices) {
                System.arraycopy(embeds[t], 0, flat, t * HIDDEN, HIDDEN)
            }
            val maskFlat = FloatArray(32 * CACHE) { NEG }
            for (row in 0 until 32) {
                val allowed = min(row, p - 1) + 1
                for (c in 0 until allowed) {
                    maskFlat[row * CACHE + c] = 0f
                }
            }
            val inputs = HashMap<String, TensorBuffer>()
            inputs["embeddings"] = talker.createInputBuffer("embeddings", "prefill_32")
                .also { it.writeFloat(flat) }
            inputs["input_pos"] = talker.createInputBuffer("input_pos", "prefill_32")
                .also { it.writeInt(IntArray(32) { i -> i }) }
            inputs["mask"] = talker.createInputBuffer("mask", "prefill_32")
                .also { it.writeFloat(maskFlat) }
            val zero = FloatArray(8 * CACHE * 128)
            for (name in kvNames) {
                inputs[name] = talker.createInputBuffer(name, "prefill_32")
                    .also { it.writeFloat(zero) }
            }
            talker.run(inputs, setA.mapValues { it.value }, "prefill_32")
            current = setA
            for (buffer in inputs.values) {
                buffer.close()
            }
        }

        fun decode(embed: FloatArray, pos: Int): Step {
            embIn.writeFloat(embed)
            posIn.writeInt(intArrayOf(pos))
            mask.fill(NEG)
            for (c in 0..pos) {
                mask[c] = 0f
            }
            maskIn.writeFloat(mask)
            val next = if (current === setA) setB else setA
            val inputs = HashMap<String, TensorBuffer>(64)
            inputs["embeddings"] = embIn
            inputs["input_pos"] = posIn
            inputs["mask"] = maskIn
            for (name in kvNames) {
                inputs[name] = current.getValue(name)
            }
            val outputs = HashMap<String, TensorBuffer>(64)
            outputs["logits"] = logitsOut
            for (name in kvNames) {
                outputs[name] = next.getValue(name)
            }
            talker.run(inputs, outputs, "decode")
            current = next
            val logits = logitsOut.readFloat() // [4096] = codec logits | hidden
            return Step(
                logits.copyOfRange(0, CODEC_VOCAB),
                logits.copyOfRange(CODEC_VOCAB, CODEC_VOCAB + HIDDEN))
        }
    }

    class Step(val logits: FloatArray, val hidden: FloatArray)

    // ------------------------------------------------------------------
    // Folded MTP: all 16 autoregressive positions execute in one graph call.
    // Noise is zero for greedy generation or T*Gumbel for softmax sampling.
    // ------------------------------------------------------------------
    private val mtpIn = mtp.createInputBuffers()
    private val mtpOut = mtp.createOutputBuffers()
    private val mtpNoise = FloatArray(15 * MTP_VOCAB)

    private fun mtpFrame(
        hidden: FloatArray, cb0: Int, greedy: Boolean, rnd: Random,
    ): IntArray {
        if (greedy) {
            mtpNoise.fill(0f)
        } else {
            for (i in mtpNoise.indices) {
                val uniform = rnd.nextDouble().coerceIn(1e-7, 1.0 - 1e-7)
                mtpNoise[i] = (-ln(-ln(uniform)) * 0.9).toFloat()
            }
        }
        mtpIn[0].writeFloat(hidden)
        mtpIn[1].writeFloat(codecRow(cb0))
        mtpIn[2].writeFloat(mtpNoise)
        mtp.run(mtpIn, mtpOut)
        return mtpOut[0].readInt()
    }

    // ------------------------------------------------------------------
    // Codec decode: fixed 64-frame chunks, 25 frames of left context.
    // ------------------------------------------------------------------
    private fun decodeCodes(frames: List<IntArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val codecAIn = codecA.createInputBuffers()
        val codecAOut = codecA.createOutputBuffers()
        val codecBOut = codecB.createOutputBuffers()
        val pieces = ArrayList<FloatArray>()
        var i = 0
        while (i < frames.size) {
            val ctx = min(CODEC_CTX, i)
            // Window = left context + new frames must fit the fixed-T graph, so
            // advance by at most CODEC_CHUNK - ctx new frames per chunk.
            val j = min(i + CODEC_CHUNK - ctx, frames.size)
            val n = j - (i - ctx) // = new frames + ctx, always <= CODEC_CHUNK
            val buf = IntArray(16 * CODEC_CHUNK)
            for (t in 0 until n) {
                val frame = frames[i - ctx + t]
                for (q in 0 until 16) {
                    buf[q * CODEC_CHUNK + t] = frame[q]
                }
            }
            codecAIn[0].writeInt(buf)
            codecA.run(codecAIn, codecAOut)
            codecB.run(listOf(codecAOut[0]), codecBOut)
            val wav = codecBOut[0].readFloat()
            pieces.add(wav.copyOfRange(ctx * UPSAMPLE, n * UPSAMPLE))
            i = j
        }
        for (buffer in codecAIn + codecAOut + codecBOut) {
            buffer.close()
        }
        var total = 0
        for (p in pieces) {
            total += p.size
        }
        val out = FloatArray(total)
        var off = 0
        for (p in pieces) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    // ------------------------------------------------------------------
    // Host math helpers.
    // ------------------------------------------------------------------
    private fun codecRow(id: Int): FloatArray {
        val out = FloatArray(HIDDEN)
        System.arraycopy(codecEmb, id * HIDDEN, out, 0, HIDDEN)
        return out
    }

    private fun mtpRow(table: Int, id: Int): FloatArray {
        val out = FloatArray(HIDDEN)
        val base = (table * MTP_VOCAB + id) * HIDDEN
        for (i in 0 until HIDDEN) {
            out[i] = Npy.halfToFloat(mtpEmb.get(base + i))
        }
        return out
    }

    private fun addMtpRow(acc: FloatArray, table: Int, id: Int) {
        val base = (table * MTP_VOCAB + id) * HIDDEN
        for (i in 0 until HIDDEN) {
            acc[i] += Npy.halfToFloat(mtpEmb.get(base + i))
        }
    }

    private fun add(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(HIDDEN) { a[it] + b[it] }

    /** text_embedding lookup + the 2048->1024 SiLU projection MLP. */
    private fun embedText(ids: IntArray): Array<FloatArray> {
        val w1 = proj.getValue("w1")
        val b1 = proj.getValue("b1")
        val w2 = proj.getValue("w2")
        val b2 = proj.getValue("b2")
        return Array(ids.size) { n ->
            val x = FloatArray(2048)
            val base = ids[n] * 2048
            for (i in 0 until 2048) {
                x[i] = Npy.halfToFloat(textEmb.get(base + i))
            }
            val h = FloatArray(2048)
            for (r in 0 until 2048) {
                var acc = b1[r]
                val wBase = r * 2048
                for (c in 0 until 2048) {
                    acc += w1[wBase + c] * x[c]
                }
                h[r] = acc / (1f + exp(-acc)) // SiLU
            }
            val y = FloatArray(HIDDEN)
            for (r in 0 until HIDDEN) {
                var acc = b2[r]
                val wBase = r * 2048
                for (c in 0 until 2048) {
                    acc += w2[wBase + c] * h[c]
                }
                y[r] = acc
            }
            y
        }
    }

    /** Greedy argmax or top-50/temperature-0.9 sampling. */
    private fun pick(logits: FloatArray, greedy: Boolean, rnd: Random): Int {
        if (greedy) {
            var best = 0
            for (i in logits.indices) {
                if (logits[i] > logits[best]) {
                    best = i
                }
            }
            return best
        }
        val k = 50
        val idx = logits.indices.sortedByDescending { logits[it] }.take(k)
        val probs = DoubleArray(k)
        val maxLogit = logits[idx[0]] / 0.9
        var sum = 0.0
        for (i in 0 until k) {
            probs[i] = exp(logits[idx[i]] / 0.9 - maxLogit)
            sum += probs[i]
        }
        var r = rnd.nextDouble() * sum
        for (i in 0 until k) {
            r -= probs[i]
            if (r <= 0) return idx[i]
        }
        return idx[k - 1]
    }

    fun close() {
        for (buffer in mtpIn + mtpOut) {
            buffer.close()
        }
        talker.close()
        mtp.close()
        codecA.close()
        codecB.close()
    }
}
