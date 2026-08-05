package dev.quad.shepherd.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import dev.quad.shepherd.util.DebugLog
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Neural TTS through sherpa-onnx, replacing the robotic system voice.
 * Prefers Supertonic 3 (66M, ~10x faster synthesis — latency matters more
 * than the last bit of richness on a walking companion) and falls back to
 * Kokoro-82M when only that is on disk. Synthesis runs on CPU worker
 * threads — the NPU belongs to the SLM and the GPU to vision.
 *
 * Model files live under `<external-files>/models/{supertonic,kokoro}`
 * ([VoiceFetcher] self-provisions Supertonic on fresh phones). When
 * nothing is on disk, [tryCreate] returns null and [SpeechFeedback] stays
 * on the system engine.
 */
class NeuralTts private constructor(
    private val tts: OfflineTts,
    private val speakerId: Int,
    private val engineName: String,
    sampleRate: Int,
) {

    companion object {
        private const val TAG = "NeuralTts"

        private const val NORMAL_SPEED = 1.05f
        private const val URGENT_SPEED = 1.25f

        /** sid 3 = af_heart — Kokoro v1.0's best-rated English voice. */
        private const val KOKORO_SPEAKER = 3

        /** Loads a model (~1-2 s); call off the main thread. */
        fun tryCreate(baseDir: File?): NeuralTts? {
            baseDir ?: return null
            supertonic(File(baseDir, "models/supertonic"))?.let { return it }
            kokoro(File(baseDir, "models/kokoro"))?.let { return it }
            Log.i(TAG, "no neural voice on disk — staying on system TTS")
            return null
        }

        private fun supertonic(dir: File): NeuralTts? {
            val files = listOf(
                "duration_predictor.int8.onnx", "text_encoder.int8.onnx",
                "vector_estimator.int8.onnx", "vocoder.int8.onnx",
                "tts.json", "unicode_indexer.bin", "voice.bin",
            ).map { File(dir, it) }
            if (files.any { !it.isFile }) return null
            return build("supertonic", speaker = 0) {
                OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = files[0].absolutePath,
                        textEncoder = files[1].absolutePath,
                        vectorEstimator = files[2].absolutePath,
                        vocoder = files[3].absolutePath,
                        ttsJson = files[4].absolutePath,
                        unicodeIndexer = files[5].absolutePath,
                        voiceStyle = files[6].absolutePath,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                )
            }
        }

        private fun kokoro(dir: File): NeuralTts? {
            val model = File(dir, "model.int8.onnx")
            val voices = File(dir, "voices.bin")
            val tokens = File(dir, "tokens.txt")
            val espeak = File(dir, "espeak-ng-data")
            if (!model.isFile || !voices.isFile || !tokens.isFile || !espeak.isDirectory) {
                return null
            }
            val lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                .map { File(dir, it) }
                .filter { it.isFile }
                .joinToString(",") { it.absolutePath }
            val dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath ?: ""
            return build("kokoro", speaker = KOKORO_SPEAKER) {
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model.absolutePath,
                        voices = voices.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = espeak.absolutePath,
                        lexicon = lexicon,
                        dictDir = dictDir,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                )
            }
        }

        private fun build(
            name: String,
            speaker: Int,
            model: () -> OfflineTtsModelConfig,
        ): NeuralTts? = try {
            val t = OfflineTts(config = OfflineTtsConfig(model = model()))
            Log.i(TAG, "$name ready: ${t.numSpeakers()} voices @ ${t.sampleRate()} Hz")
            NeuralTts(t, speaker, name, t.sampleRate())
        } catch (e: Throwable) {
            Log.e(TAG, "$name init failed", e)
            null
        }
    }

    private data class Item(val text: String, val urgent: Boolean, val chat: Boolean, val gen: Long)

    private val queue = LinkedBlockingQueue<Item>()

    /** Bumped to cancel the current utterance and everything queued. */
    private val generation = AtomicLong(0)

    private var chatPending = 0 // guarded by synchronized(this)

    @Volatile private var closed = false

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ) * 4
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val worker = Thread(::loop, "NeuralTts").apply {
        isDaemon = true
        start()
    }

    /** True while any companion-chat sentence is speaking or queued. */
    val chatActive: Boolean
        get() = synchronized(this) { chatPending > 0 }

    fun enqueue(text: String, urgent: Boolean, chat: Boolean, interrupt: Boolean) {
        if (closed) return
        if (interrupt) stopAll()
        if (chat) synchronized(this) { chatPending++ }
        Log.i(TAG, "enqueue chat=$chat urgent=$urgent interrupt=$interrupt: ${text.take(60)}")
        queue.put(Item(text, urgent, chat, generation.get()))
    }

    /** Drop everything queued and cut off the current utterance. */
    fun stopAll() {
        generation.incrementAndGet()
        val dropped = mutableListOf<Item>()
        queue.drainTo(dropped)
        synchronized(this) {
            chatPending -= dropped.count { it.chat }
            if (chatPending < 0) chatPending = 0
        }
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        worker.interrupt()
        runCatching { worker.join(1500) }
        runCatching { track.stop() }
        runCatching { track.release() }
        runCatching { tts.release() }
    }

    private fun loop() {
        runCatching { track.play() }
            .onFailure { Log.e(TAG, "AudioTrack.play failed (state=${track.state})", it) }
        while (!closed) {
            val item = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            val gen = item.gen
            if (gen != generation.get()) {
                finish(item)
                continue
            }
            try {
                val speed = if (item.urgent) URGENT_SPEED else NORMAL_SPEED
                // generate(), NOT generateWithCallback(): the JNI callback
                // path fatally aborts under Kotlin 2.x invokedynamic
                // lambdas — D8's synthetic class lacks the specialized
                // invoke([F)Integer method the native side looks up.
                val t0 = System.nanoTime()
                val audio = tts.generate(item.text, speakerId, speed)
                val synthMs = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "$engineName synth $synthMs ms, ${audio.samples.size} samples")
                DebugLog.d("TTS", "$engineName ${synthMs}ms: ${item.text.take(36)}")
                if (!closed && gen == generation.get()) {
                    write(audio.samples, gen)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "synthesis failed for: ${item.text.take(60)}", e)
            } finally {
                finish(item)
            }
        }
    }

    private fun finish(item: Item) {
        if (item.chat) synchronized(this) { if (chatPending > 0) chatPending-- }
    }

    /** Blocking sliced playback; a generation bump cancels between slices. */
    private fun write(samples: FloatArray, gen: Long) {
        var off = 0
        var lastResult = 0
        val slice = 6000 // 0.25 s at 24 kHz
        while (off < samples.size && !closed && gen == generation.get()) {
            lastResult = track.write(samples, off, minOf(slice, samples.size - off), AudioTrack.WRITE_BLOCKING)
            if (lastResult <= 0) break
            off += lastResult
        }
        if (lastResult <= 0 && off < samples.size) {
            Log.w(TAG, "playback stalled at $off/${samples.size} (write=$lastResult)")
        }
    }
}
