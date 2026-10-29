package com.smartfk.keyboard

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Genuine fine-tuned Turkish ASR from Tinlera/whisper-turkish-sherpa-onnx,
 * derived from ysdede/whisper-base-turkish-1.1 (Apache-2.0).
 *
 * Decoder only runs in a background worker, NEVER on AudioRecord thread.
 * Audio recording continues when a speaker pauses or decoding is busy.
 */
internal class TurkishSherpa(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null

    private fun model(): OfflineRecognizer {
        recognizer?.let { return it }
        val dir = File(context.noBackupFilesDir, "turkish-sherpa")
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("No space for Turkish ASR")
        val filenames = listOf("base-tr-encoder.int8.onnx", "base-tr-decoder.int8.onnx", "tokens.txt")
        for (name in filenames) {
            val dest = File(dir, name)
            val src = "turkish-sherpa/$name"
            val size = context.assets.openFd(src).length
            if (dest.isFile && dest.length() == size) continue
            val temp = File(dir, "$name.partial")
            context.assets.open(src).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 32768) }
            }
            if (temp.length() != size || !temp.renameTo(dest)) {
                throw IllegalStateException("Could not materialize Turkish model: $name")
            }
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, filenames[0]).absolutePath,
                    decoder = File(dir, filenames[1]).absolutePath,
                    language = "tr",
                    task = "transcribe"
                ),
                tokens = File(dir, filenames[2]).absolutePath,
                numThreads = 2,
                debug = false,
                modelType = "whisper"
            )
        )
        val ready = OfflineRecognizer(config = config)
        recognizer = ready
        return ready
    }

    @Synchronized fun transcribe(pcm: ShortArray): String {
        if (pcm.size < 16000) return ""
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val recognizer = model()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            Log.i("SmartFkSherpa", "Turkish recognized length=0")
            return deduplicate(text)
        } finally {
            stream.release()
        }
    }

    @Synchronized fun close() {
        recognizer?.release()
        recognizer = null
    }

    private fun deduplicate(input: String): String {
        // Fine-tuned Whisper models may repeat the entire phrase at the end.
        val words=input.trim().split(Regex("\\s+"))
        if(words.size<6) return input.trim()
        for(size in (words.size/2) downTo 3) {
            if(words.take(size).joinToString(" ").equals(
                    words.drop(size).take(size).joinToString(" "), ignoreCase=true))
                return words.take(size).joinToString(" ")
        }
        return input.trim()
    }
}
