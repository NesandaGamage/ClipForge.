package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Fully local inference and export. Network is used only once to fetch the open model. */
class OnDeviceClipEngine(private val context: Context) {
    interface Listener { fun onProgress(message: String); fun onComplete(files: List<File>); fun onError(message: String) }
    private val modelFile get() = File(context.filesDir, "ggml-tiny.bin")

    fun createFiveClips(source: Uri, listener: Listener) {
        thread(name = "clipforge-local-ai") {
            try {
                val input = File(context.cacheDir, "clipforge_input_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(source)?.use { stream -> input.outputStream().use { stream.copyTo(it) } }
                    ?: throw IllegalStateException("Cannot read selected video")
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(input.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                if (durationMs < 5_000L) throw IllegalArgumentException("Choose a video at least 5 seconds long")

                progress(listener, "Preparing the free Whisper model…")
                ensureModel()
                progress(listener, "Preparing audio in small chunks…")
                // Split audio into 30-second WAV files. This bounds peak PCM memory on phones.
                val chunksDir = File(context.cacheDir, "clipforge_chunks_${System.currentTimeMillis()}").apply { mkdirs() }
                val chunkPattern = File(chunksDir, "audio_%03d.wav")
                val audioCmd = "-y -i ${q(input.absolutePath)} -vn -ac 1 -ar 16000 -c:a pcm_s16le -f segment -segment_time 30 -reset_timestamps 1 ${q(chunkPattern.absolutePath)}"
                val audioSession = FFmpegKit.execute(audioCmd)
                if (!ReturnCode.isSuccess(audioSession.returnCode)) throw IllegalStateException("Could not extract audio: ${audioSession.failStackTrace}")
                val wavChunks = chunksDir.listFiles { f -> f.extension.equals("wav", true) }?.sortedBy { it.name }.orEmpty()
                if (wavChunks.isEmpty()) throw IllegalStateException("No audio could be extracted from this video")

                progress(listener, "Loading Whisper.cpp on this device…")
                WhisperBridge.ensureLoaded()
                val segments = mutableListOf<Segment>()
                wavChunks.forEachIndexed { chunkIndex, wav ->
                    progress(listener, "Transcribing audio ${chunkIndex + 1}/${wavChunks.size}…")
                    val transcript = WhisperBridge.transcribeNative(modelFile.absolutePath, wav.absolutePath)
                    if (transcript.startsWith("ERROR")) throw IllegalStateException(transcript.substringAfter('\t'))
                    val offset = chunkIndex * 30.0
                    transcript.lineSequence().mapNotNull { line ->
                        val p = line.split('\t', limit = 3)
                        if (p.size < 3) null else Segment(offset + (p[0].toLongOrNull() ?: 0L) / 1000.0,
                            offset + (p[1].toLongOrNull() ?: 0L) / 1000.0, p[2])
                    }.forEach { segments += it }
                    wav.delete()
                }
                chunksDir.delete()

                progress(listener, "Ranking moments and rendering five clips…")
                val duration = durationMs / 1000.0
                val binLength = duration / 5.0
                val clipLength = min(35.0, binLength)
                val finalClips = (0 until 5).map { bin ->
                    val binStart = bin * binLength
                    val latestStart = max(binStart, (bin + 1) * binLength - clipLength)
                    val step = max(1.0, clipLength / 4.0)
                    var best = Candidate(binStart, binStart + clipLength, -1.0)
                    var candidateStart = binStart
                    while (candidateStart <= latestStart + 0.001) {
                        val candidateEnd = min(duration, candidateStart + clipLength)
                        val text = segments.filter { it.end >= candidateStart && it.start <= candidateEnd }
                            .joinToString(" ") { it.text }
                        val candidate = Candidate(candidateStart, candidateEnd, score(text))
                        if (candidate.score > best.score) best = candidate
                        candidateStart += step
                    }
                    best
                }

                val outputs = mutableListOf<File>()
                for ((index, c) in finalClips.withIndex()) {
                    val out = File(context.cacheDir, "clipforge_${System.currentTimeMillis()}_${index + 1}.mp4")
                    val seconds = max(1.0, c.end - c.start)
                    val vf = "crop='min(iw,ih*9/16)':'min(ih,iw*16/9)',scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"
                    val cmd = "-y -ss ${fmt(c.start)} -i ${q(input.absolutePath)} -t ${fmt(seconds)} -vf \"$vf\" -c:v h264_mediacodec -b:v 5M -c:a aac -b:a 128k -movflags +faststart ${q(out.absolutePath)}"
                    val session = FFmpegKit.execute(cmd)
                    if (!ReturnCode.isSuccess(session.returnCode) || !out.exists() || out.length() == 0L)
                        throw IllegalStateException("Export failed for clip ${index + 1}: ${session.failStackTrace}")
                    outputs += out
                }
                input.delete()
                Handler(Looper.getMainLooper()).post { listener.onComplete(outputs) }
            } catch (e: Throwable) {
                // Includes linkage errors such as UnsatisfiedLinkError, which are not Exceptions.
                Handler(Looper.getMainLooper()).post { listener.onError("${e.javaClass.simpleName}: ${e.message ?: "On-device processing failed"}") }
            }
        }
    }

    private fun progress(listener: Listener, message: String) {
        Handler(Looper.getMainLooper()).post { listener.onProgress(message) }
    }

    private fun ensureModel() {
        if (modelFile.exists() && modelFile.length() > 60_000_000L) return
        val tmp = File(context.filesDir, "ggml-tiny.bin.part")
        URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin").openConnection().apply {
            connectTimeout = 20_000; readTimeout = 60_000
        }.getInputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        if (tmp.length() < 60_000_000L) { tmp.delete(); throw IllegalStateException("Whisper model download was incomplete. Check your connection and retry.") }
        if (!tmp.renameTo(modelFile)) throw IllegalStateException("Could not install the Whisper model")
    }

    private data class Segment(val start: Double, val end: Double, val text: String)
    private data class Candidate(val start: Double, val end: Double, val score: Double)
    private fun score(text: String): Double {
        val t = text.lowercase(Locale.ROOT)
        if (t.isBlank()) return 0.05
        val hooks = listOf("secret", "never", "always", "why", "how", "mistake", "truth", "shocking", "important", "best", "worst", "because", "discover", "imagine", "surprising", "problem", "solution", "tip", "first", "finally")
        val hookScore = hooks.sumOf { word -> if (t.contains(word)) 1.4 else 0.0 }
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lengthScore = min(words.size / 24.0, 2.0)
        val punctuation = listOf("?", "!", ":").sumOf { t.count { ch -> ch.toString() == it }.toDouble() } * 0.25
        return hookScore + lengthScore + punctuation
    }
    private fun overlap(a: Candidate, b: Candidate): Double {
        val shared = max(0.0, min(a.end, b.end) - max(a.start, b.start))
        return shared / min(a.end - a.start, b.end - b.start).coerceAtLeast(0.001)
    }
    private fun q(path: String) = "'${path.replace("'", "'\\''")}'"
    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
}
