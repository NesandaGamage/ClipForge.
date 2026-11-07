
package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Local transcription, content-based clip ranking and video export. */
class OnDeviceClipEngine(private val context: Context) {

    interface Listener {
        fun onProgress(message: String)
        fun onComplete(files: List<File>)
        fun onError(message: String)
    }

    private val modelFile
        get() = File(context.filesDir, "ggml-tiny.bin")

    fun createFiveClips(source: Uri, listener: Listener) {
        thread(name = "clipforge-local-ai") {
            val input = File(
                context.cacheDir,
                "clipforge_input_${System.currentTimeMillis()}.mp4"
            )
            val chunksDir = File(
                context.cacheDir,
                "clipforge_chunks_${System.currentTimeMillis()}"
            )
            val outputs = mutableListOf<File>()

            try {
                context.contentResolver.openInputStream(source)?.use { stream ->
                    input.outputStream().use { stream.copyTo(it) }
                } ?: throw IllegalStateException("Cannot read selected video")

                val retriever = MediaMetadataRetriever()
                val durationMs = try {
                    retriever.setDataSource(input.absolutePath)
                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }

                if (durationMs < 5000L) {
                    throw IllegalArgumentException(
                        "Choose a video at least 5 seconds long"
                    )
                }

                val duration = durationMs / 1000.0

                progress(listener, "Preparing the free Whisper model…")
                ensureModel()

                progress(listener, "Extracting audio locally…")
                chunksDir.mkdirs()

                val chunkPattern = File(chunksDir, "audio_%03d.wav")
                val audioCommand =
                    "-y -i ${q(input.absolutePath)} " +
                    "-vn -ac 1 -ar 16000 -c:a pcm_s16le " +
                    "-f segment -segment_time 30 -reset_timestamps 1 " +
                    q(chunkPattern.absolutePath)

                val audioSession = FFmpegKit.execute(audioCommand)

                if (!ReturnCode.isSuccess(audioSession.returnCode)) {
                    throw IllegalStateException(
                        "Could not extract audio: ${audioSession.failStackTrace}"
                    )
                }

                val wavChunks = chunksDir.listFiles {
                    file -> file.extension.equals("wav", true)
                }?.sortedBy { it.name }.orEmpty()

                if (wavChunks.isEmpty()) {
                    throw IllegalStateException(
                        "No audio could be extracted from this video"
                    )
                }

                progress(listener, "Loading Whisper.cpp on this device…")
                WhisperBridge.ensureLoaded()

                val segments = mutableListOf<Segment>()

                wavChunks.forEachIndexed { index, wav ->
                    progress(
                        listener,
                        "Transcribing audio ${index + 1}/${wavChunks.size}…"
                    )

                    val transcript = WhisperBridge.transcribeNative(
                        modelFile.absolutePath,
                        wav.absolutePath
                    )

                    if (transcript.startsWith("ERROR")) {
                        throw IllegalStateException(
                            transcript.substringAfter('\t')
                        )
                    }

                    val offset = index * 30.0

                    transcript.lineSequence().forEach { line ->
                        val parts = line.split('\t', limit = 3)

                        if (parts.size == 3) {
                            val start =
                                offset + (parts[0].toLongOrNull() ?: 0L) / 1000.0
                            val end =
                                offset + (parts[1].toLongOrNull() ?: 0L) / 1000.0

                            if (end > start) {
                                segments.add(
                                    Segment(
                                        start.coerceIn(0.0, duration),
                                        end.coerceIn(0.0, duration),
                                        parts[2].trim()
                                    )
                                )
                            }
                        }
                    }

                    wav.delete()
                }

                chunksDir.deleteRecursively()

                progress(listener, "Finding the strongest moments…")

                val candidates = generateCandidates(segments, duration)
                val selected = selectFive(candidates, duration)

                progress(listener, "Rendering your five AI-selected clips…")

                for ((index, candidate) in selected.withIndex()) {
                    val output = File(
                        context.cacheDir,
                        "clipforge_${System.currentTimeMillis()}_${index + 1}.mp4"
                    )

                    val clipDuration = candidate.end - candidate.start

                    if (clipDuration <= 0.0) {
                        throw IllegalStateException("Invalid clip duration")
                    }

                    val filter =
                        "scale=1080:1920:force_original_aspect_ratio=increase," +
                        "crop=1080:1920"

                    val command =
                        "-y -ss ${fmt(candidate.start)} " +
                        "-i ${q(input.absolutePath)} " +
                        "-t ${fmt(clipDuration)} " +
                        "-vf \"$filter\" " +
                        "-c:v h264_mediacodec -b:v 5M " +
                        "-c:a aac -b:a 128k " +
                        "-movflags +faststart ${q(output.absolutePath)}"

                    val session = FFmpegKit.execute(command)

                    if (!ReturnCode.isSuccess(session.returnCode) ||
                        !output.exists() || output.length() == 0L
                    ) {
                        throw IllegalStateException(
                            "Export failed for clip ${index + 1}: " +
                            session.failStackTrace
                        )
                    }

                    outputs.add(output)
                }

                Handler(Looper.getMainLooper()).post {
                    listener.onComplete(outputs)
                }

            } catch (e: Throwable) {
                outputs.forEach { it.delete() }

                Handler(Looper.getMainLooper()).post {
                    listener.onError(
                        "${e.javaClass.simpleName}: " +
                        (e.message ?: "On-device processing failed")
                    )
                }
            } finally {
                input.delete()
                chunksDir.deleteRecursively()
            }
        }
    }

    private fun generateCandidates(
        segments: List<Segment>,
        duration: Double
    ): List<Candidate> {

        val candidates = linkedMapOf<String, Candidate>()
        val minimumLength = min(8.0, duration)
        val maximumLength = min(60.0, duration)

        fun addCandidate(startValue: Double, endValue: Double) {
            val start = startValue.coerceIn(0.0, duration)
            val end = endValue.coerceIn(0.0, duration)

            if (end - start < minimumLength - 0.01) return

            val text = segments
                .filter { it.end > start && it.start < end }
                .joinToString(" ") { it.text }

            val candidate = Candidate(
                start,
                end,
                score(text, end - start)
            )

            val key = "${(start * 10).toInt()}_${(end * 10).toInt()}"
            candidates[key] = candidate
        }

        // Generate different content-dependent durations.
        val targetLengths = listOf(12.0, 20.0, 30.0, 45.0, 60.0)

        for (i in segments.indices) {
            val start = segments[i].start.coerceIn(0.0, duration)

            for (target in targetLengths) {
                val desired = min(target, maximumLength)

                val possibleEnds = segments
                    .drop(i)
                    .filter {
                        it.end - start >= minimumLength * 0.75 &&
                        it.end - start <= maximumLength + 1.0
                    }

                val bestEnd = possibleEnds.minByOrNull {
                    abs((it.end - start) - desired)
                }

                if (bestEnd != null) {
                    val end = min(bestEnd.end, duration)
                    addCandidate(start, end)
                }
            }
        }

        // Also scan the timeline so quiet but meaningful sections
        // are not ignored just because they have fewer transcript segments.
        val fallbackLength = min(20.0, maximumLength)
        val maxStart = max(0.0, duration - fallbackLength)
        val step = max(2.0, fallbackLength / 3.0)

        var position = 0.0
        while (position <= maxStart + 0.01) {
            addCandidate(position, min(duration, position + fallbackLength))
            position += step
        }

        if (candidates.isEmpty()) {
            addCandidate(0.0, duration)
        }

        return candidates.values.toList()
    }

    private fun selectFive(
        candidates: List<Candidate>,
        duration: Double
    ): List<Candidate> {

        if (candidates.isEmpty()) {
            val fallback = Candidate(0.0, duration, 0.1)
            return List(5) { fallback }
        }

        val ranked = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<Candidate>()

        // First prefer separate moments.
        for (limit in listOf(0.35, 0.70, 1.01)) {
            for (candidate in ranked) {
                if (selected.size == 5) break

                val alreadySelected = selected.any {
                    abs(it.start - candidate.start) < 0.1 &&
                    abs(it.end - candidate.end) < 0.1
                }

                if (alreadySelected) continue

                val overlapsTooMuch = selected.any {
                    overlap(it, candidate) > limit
                }

                if (!overlapsTooMuch) {
                    selected.add(candidate)
                }
            }

            if (selected.size == 5) break
        }

        // Very short videos may not contain five distinct moments.
        // Still return five exportable files rather than crashing.
        while (selected.size < 5) {
            selected.add(ranked[selected.size % ranked.size])
        }

        return selected.take(5)
    }

    private fun score(text: String, duration: Double): Double {
        val t = text.lowercase(Locale.ROOT)

        if (t.isBlank()) return 0.1

        val hooks = listOf(
            "you won't believe", "did you know", "the secret",
            "here's why", "what happened", "the truth",
            "never do this", "most people", "the biggest mistake",
            "imagine", "surprising", "shocking", "important",
            "how to", "why you", "the problem"
        )

        val emotion = listOf(
            "amazing", "incredible", "crazy", "terrible",
            "excited", "afraid", "love", "hate",
            "dangerous", "beautiful", "unbelievable"
        )

        val payoff = listOf(
            "finally", "that's why", "the answer",
            "the result", "in the end", "which means",
            "so that's", "the solution", "remember this"
        )

        val hookScore = hooks.sumOf {
            if (t.contains(it)) 2.0 else 0.0
        }

        val emotionScore = emotion.sumOf {
            if (t.contains(it)) 1.2 else 0.0
        }

        val payoffScore = payoff.sumOf {
            if (t.contains(it)) 1.8 else 0.0
        }

        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size

        val informationScore = min(wordCount / 30.0, 3.0)

        val questionScore = t.count { it == '?' } * 0.7
        val exclamationScore = t.count { it == '!' } * 0.4

        val wordsPerSecond = wordCount / duration.coerceAtLeast(1.0)

        val speechScore = when {
            wordsPerSecond in 1.5..4.0 -> 1.5
            wordsPerSecond in 0.8..5.0 -> 0.7
            else -> 0.0
        }

        val structureScore =
            if (t.contains("because") || t.contains("but") ||
                t.contains("therefore") || t.contains("however")
            ) 1.0 else 0.0

        return hookScore +
            emotionScore +
            payoffScore +
            informationScore +
            questionScore +
            exclamationScore +
            speechScore +
            structureScore
    }

    private fun overlap(a: Candidate, b: Candidate): Double {
        val shared = max(
            0.0,
            min(a.end, b.end) - max(a.start, b.start)
        )

        return shared /
            min(a.end - a.start, b.end - b.start).coerceAtLeast(0.001)
    }

    private fun ensureModel() {
        if (modelFile.exists() && modelFile.length() > 60_000_000L) {
            return
        }

        val temporary = File(context.filesDir, "ggml-tiny.bin.part")

        URL(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        ).openConnection().apply {
            connectTimeout = 20000
            readTimeout = 60000
        }.getInputStream().use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (temporary.length() < 60_000_000L) {
            temporary.delete()
            throw IllegalStateException(
                "Whisper model download was incomplete. Check your connection."
            )
        }

        if (!temporary.renameTo(modelFile)) {
            temporary.delete()
            throw IllegalStateException("Could not install the Whisper model")
        }
    }

    private fun progress(listener: Listener, message: String) {
        Handler(Looper.getMainLooper()).post {
            listener.onProgress(message)
        }
    }

    private data class Segment(
        val start: Double,
        val end: Double,
        val text: String
    )

    private data class Candidate(
        val start: Double,
        val end: Double,
        val score: Double
    )

    private fun q(path: String): String =
        "'${path.replace("'", "'\\''")}'"

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.3f", value)
}
