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

/**
 * ClipForge single-best-clip AI engine.
 *
 * Uses:
 * - Local Whisper.cpp transcription
 * - YouTube Most Replayed heatmap data when available
 * - Transcript and replay-intensity ranking
 * - FFmpeg video export
 */
class OnDeviceClipEngine(private val context: Context) {

    interface Listener {
        fun onProgress(message: String)
        fun onComplete(files: List<File>)
        fun onError(message: String)
    }

    private val modelFile
        get() = File(context.filesDir, "ggml-tiny.bin")

    private data class Segment(
        val start: Double,
        val end: Double,
        val text: String
    )

    private data class HeatPoint(
        val time: Double,
        val intensity: Double
    )

    private data class Candidate(
        val start: Double,
        val end: Double,
        val score: Double,
        val transcript: String,
        val replayScore: Double
    )

    /**
     * Creates one best clip.
     * The source video must already be imported into ClipForge.
     */
    fun createBestClip(
        source: Uri,
        youtubeUrl: String?,
        listener: Listener
    ) {
        thread(name = "clipforge-best-clip") {

            val input = File(
                context.cacheDir,
                "clipforge_input_${System.currentTimeMillis()}.mp4"
            )

            val chunksDir = File(
                context.cacheDir,
                "clipforge_chunks_${System.currentTimeMillis()}"
            )

            var output: File? = null

            try {
                progress(listener, "Preparing your video…")

                context.contentResolver.openInputStream(source)?.use { stream ->
                    input.outputStream().use { stream.copyTo(it) }
                } ?: throw IllegalStateException(
                    "Cannot read selected video"
                )

                // Read video duration.
                val retriever = MediaMetadataRetriever()

                val durationMs = try {
                    retriever.setDataSource(input.absolutePath)

                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }

                if (durationMs < 8000L) {
                    throw IllegalArgumentException(
                        "Choose a video at least 8 seconds long"
                    )
                }

                val duration = durationMs / 1000.0

                // Retrieve YouTube Most Replayed data.
                var heatPoints = emptyList<HeatPoint>()

                if (!youtubeUrl.isNullOrBlank()) {
                    progress(
                        listener,
                        "Checking YouTube replay data…"
                    )

                    heatPoints = try {
                        YouTubeHeatmapExtractor
                            .fetch(youtubeUrl, duration)
                            .map {
                                HeatPoint(
                                    time = it.time,
                                    intensity = it.intensity
                                )
                            }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                if (heatPoints.isNotEmpty()) {
                    progress(
                        listener,
                        "Found ${heatPoints.size} YouTube replay markers!"
                    )
                } else {
                    progress(
                        listener,
                        "Replay data unavailable. Using local AI analysis…"
                    )
                }

                // Prepare Whisper model.
                progress(
                    listener,
                    "Preparing the free Whisper model…"
                )

                ensureModel()

                // Extract audio into 30-second WAV chunks.
                progress(
                    listener,
                    "Extracting audio locally…"
                )

                chunksDir.mkdirs()

                val chunkPattern = File(
                    chunksDir,
                    "audio_%03d.wav"
                )

                val audioCommand =
                    "-y -i ${q(input.absolutePath)} " +
                    "-vn -ac 1 -ar 16000 -c:a pcm_s16le " +
                    "-f segment -segment_time 30 -reset_timestamps 1 " +
                    q(chunkPattern.absolutePath)

                val audioSession = FFmpegKit.execute(audioCommand)

                if (!ReturnCode.isSuccess(audioSession.returnCode)) {
                    throw IllegalStateException(
                        "Audio extraction failed: " +
                            audioSession.failStackTrace
                    )
                }

                val wavChunks = chunksDir.listFiles {
                    it.extension.equals("wav", true)
                }?.sortedBy { it.name }.orEmpty()

                if (wavChunks.isEmpty()) {
                    throw IllegalStateException(
                        "No audio could be extracted from this video"
                    )
                }

                // Run local Whisper transcription.
                progress(
                    listener,
                    "Loading Whisper AI on this device…"
                )

                WhisperBridge.ensureLoaded()

                val segments = mutableListOf<Segment>()

                wavChunks.forEachIndexed { index, wav ->

                    progress(
                        listener,
                        "Transcribing audio ${index + 1}/${wavChunks.size}…"
                    )

                    val transcript =
                        WhisperBridge.transcribeNative(
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
                                offset +
                                (parts[0].toLongOrNull() ?: 0L) / 1000.0

                            val end =
                                offset +
                                (parts[1].toLongOrNull() ?: 0L) / 1000.0

                            if (end > start && parts[2].isNotBlank()) {
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

                if (segments.isEmpty()) {
                    throw IllegalStateException(
                        "Whisper could not detect any speech in this video"
                    )
                }

                // Generate and rank candidate moments.
                progress(
                    listener,
                    "Finding the strongest moment…"
                )

                val candidates = generateCandidates(
                    segments,
                    heatPoints,
                    duration
                )

                if (candidates.isEmpty()) {
                    throw IllegalStateException(
                        "Could not find a suitable clip"
                    )
                }

                val best = candidates.maxByOrNull { it.score }
                    ?: throw IllegalStateException(
                        "No suitable clip found"
                    )

                val clipDuration = best.end - best.start

                if (clipDuration < 8.0 || clipDuration > 60.0) {
                    throw IllegalStateException(
                        "Selected clip duration is outside the 8–60 second limit"
                    )
                }

                // Export one vertical clip.
                progress(
                    listener,
                    "Creating your best clip (${clipDuration.toInt()} seconds)…"
                )

                output = File(
                    context.cacheDir,
                    "clipforge_best_${System.currentTimeMillis()}.mp4"
                )

                val filter =
                    "scale=1080:1920:force_original_aspect_ratio=increase," +
                    "crop=1080:1920"

                val command =
                    "-y -ss ${fmt(best.start)} " +
                    "-i ${q(input.absolutePath)} " +
                    "-t ${fmt(clipDuration)} " +
                    "-vf \"$filter\" " +
                    "-c:v h264_mediacodec -b:v 5M " +
                    "-c:a aac -b:a 128k " +
                    "-movflags +faststart ${q(output.absolutePath)}"

                val exportSession = FFmpegKit.execute(command)

                if (
                    !ReturnCode.isSuccess(exportSession.returnCode) ||
                    !output.exists() ||
                    output.length() == 0L
                ) {
                    throw IllegalStateException(
                        "Video export failed: " +
                            exportSession.failStackTrace
                    )
                }

                progress(listener, "Your best clip is ready!")

                val finalOutput = output

                Handler(Looper.getMainLooper()).post {
                    listener.onComplete(listOf(finalOutput))
                }

            } catch (e: Throwable) {

                output?.delete()

                Handler(Looper.getMainLooper()).post {
                    listener.onError(
                        "${e.javaClass.simpleName}: " +
                            (e.message ?: "AI processing failed")
                    )
                }

            } finally {
                input.delete()
                chunksDir.deleteRecursively()
            }
        }
    }

    /**
     * Compatibility with the previous MainActivity.
     * This returns one clip, not five.
     */
    @Deprecated("Use createBestClip instead")
    fun createFiveClips(
        source: Uri,
        listener: Listener
    ) {
        createBestClip(source, null, listener)
    }

    /**
     * Generates possible clips using transcript boundaries,
     * replay peaks and different durations.
     */
    private fun generateCandidates(
        segments: List<Segment>,
        heatPoints: List<HeatPoint>,
        duration: Double
    ): List<Candidate> {

        val candidates = linkedMapOf<String, Candidate>()

        val minLength = min(8.0, duration)
        val maxLength = min(60.0, duration)

        val targetLengths = listOf(
            12.0,
            20.0,
            30.0,
            45.0,
            60.0
        )

        fun addCandidate(
            startValue: Double,
            endValue: Double
        ) {

            val start = startValue.coerceIn(0.0, duration)
            val end = endValue.coerceIn(0.0, duration)

            val clipLength = end - start

            if (clipLength < minLength || clipLength > maxLength) {
                return
            }

            val selectedSegments = segments.filter {
                it.end > start && it.start < end
            }

            val transcript = selectedSegments.joinToString(" ") {
                it.text
            }.trim()

            if (transcript.isBlank()) return

            val replayScore = calculateReplayScore(
                start,
                end,
                heatPoints
            )

            val contentScore = scoreTranscript(
                transcript,
                clipLength
            )

            val combinedScore =
                if (heatPoints.isNotEmpty()) {
                    contentScore * 0.65 + replayScore * 0.35
                } else {
                    contentScore
                }

            val candidate = Candidate(
                start,
                end,
                combinedScore,
                transcript,
                replayScore
            )

            val key =
                "${(start * 10).toInt()}_${(end * 10).toInt()}"

            candidates[key] = candidate
        }

        // Build candidates around transcript starts.
        for (segment in segments) {

            for (target in targetLengths) {

                val length = min(target, maxLength)
                val start = segment.start

                val possibleEnds = segments.filter {
                    it.end > start &&
                    it.end - start >= minLength &&
                    it.end - start <= length + 8.0
                }

                val endSegment = possibleEnds.minByOrNull {
                    abs((it.end - start) - length)
                }

                if (endSegment != null) {
                    addCandidate(start, endSegment.end)
                }
            }
        }

        // Build candidates around the strongest replay peaks.
        if (heatPoints.isNotEmpty()) {

            val strongestPeaks = heatPoints
                .sortedByDescending { it.intensity }
                .take(15)

            for (peak in strongestPeaks) {

                for (target in targetLengths) {

                    val length = min(target, maxLength)

                    val start = peak.time - length * 0.35
                    val end = start + length

                    addCandidate(start, end)
                }
            }
        }

        // Timeline fallback.
        val fallbackLength = min(20.0, maxLength)
        val step = max(3.0, fallbackLength / 3.0)

        var position = 0.0

        while (position + fallbackLength <= duration + 0.01) {
            addCandidate(
                position,
                min(duration, position + fallbackLength)
            )

            position += step
        }

        return candidates.values.toList()
    }

    /**
     * Measures replay intensity inside a candidate window.
     * These are normalized scores, not actual replay counts.
     */
    private fun calculateReplayScore(
        start: Double,
        end: Double,
        points: List<HeatPoint>
    ): Double {

        if (points.isEmpty()) return 0.0

        val inside = points.filter {
            it.time >= start && it.time <= end
        }

        if (inside.isEmpty()) return 0.0

        val average = inside.map { it.intensity }.average()
        val peak = inside.maxOf { it.intensity }

        return (average * 0.4 + peak * 0.6) * 10.0
    }

    /**
     * Ranks transcript content using hooks, curiosity,
     * emotion, useful information and speech density.
     */
    private fun scoreTranscript(
        text: String,
        duration: Double
    ): Double {

        val t = text.lowercase(Locale.ROOT)

        if (t.isBlank()) return 0.0

        val hooks = listOf(
            "you won't believe",
            "did you know",
            "here's why",
            "the truth",
            "the secret",
            "what happened",
            "never do this",
            "most people",
            "the biggest mistake",
            "imagine",
            "how to",
            "why you",
            "the problem",
            "the reason",
            "this is why"
        )

        val emotion = listOf(
            "amazing",
            "incredible",
            "crazy",
            "terrible",
            "excited",
            "afraid",
            "love",
            "hate",
            "dangerous",
            "beautiful",
            "unbelievable",
            "shocking"
        )

        val payoff = listOf(
            "finally",
            "that's why",
            "the answer",
            "the result",
            "in the end",
            "which means",
            "the solution",
            "remember this",
            "so that's"
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

        val words = t.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val wordCount = words.size

        val informationScore = min(
            wordCount / 25.0,
            3.0
        )

        val questionScore =
            t.count { it == '?' } * 0.7

        val excitementScore =
            t.count { it == '!' } * 0.4

        val wordsPerSecond =
            wordCount / duration.coerceAtLeast(1.0)

        val speechScore = when {
            wordsPerSecond in 1.5..4.0 -> 2.0
            wordsPerSecond in 0.8..5.0 -> 1.0
            else -> 0.0
        }

        val structureScore =
            if (
                t.contains("because") ||
                t.contains("but") ||
                t.contains("therefore") ||
                t.contains("however")
            ) 1.5 else 0.0

        val endingScore =
            if (
                t.endsWith(".") ||
                t.endsWith("!") ||
                t.endsWith("?")
            ) 1.5 else 0.0

        val beginningScore =
            if (
                t.startsWith("but ") ||
                t.startsWith("because ") ||
                t.startsWith("and then ")
            ) -1.5 else 0.0

        return hookScore +
            emotionScore +
            payoffScore +
            informationScore +
            questionScore +
            excitementScore +
            speechScore +
            structureScore +
            endingScore +
            beginningScore
    }

    /**
     * Downloads the free Whisper tiny model when necessary.
     */
    private fun ensureModel() {

        if (
            modelFile.exists() &&
            modelFile.length() > 60_000_000L
        ) {
            return
        }

        val temporary = File(
            context.filesDir,
            "ggml-tiny.bin.part"
        )

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
                "Whisper model download was incomplete"
            )
        }

        if (!temporary.renameTo(modelFile)) {
            temporary.delete()

            throw IllegalStateException(
                "Could not install the Whisper model"
            )
        }
    }

    private fun progress(
        listener: Listener,
        message: String
    ) {
        Handler(Looper.getMainLooper()).post {
            listener.onProgress(message)
        }
    }

    private fun q(path: String): String =
        "'${path.replace("'", "'\\''")}'"

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.3f", value)
}
