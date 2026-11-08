
package com.clipforge.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * Best-effort YouTube Most Replayed extractor.
 * Primary: ytInitialData JSON.
 * Fallback: SVG heatmap path.
 */
internal object YouTubeHeatmapExtractor {

    data class HeatPoint(val time: Double, val intensity: Double)

    fun fetch(videoUrl: String, duration: Double): List<HeatPoint> {
        val id = extractVideoId(videoUrl)
            ?: throw IllegalArgumentException("Invalid YouTube URL")

        val url = "https://www.youtube.com/watch?v=" +
            URLEncoder.encode(id, "UTF-8")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        )
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

        val html = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        if (html.isBlank()) return emptyList()

        val jsonPoints = extractInitialData(html, duration)
        if (jsonPoints.isNotEmpty()) return jsonPoints

        return extractSvg(html, duration)
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/(?:watch\?(?:[^#]*&)?v=|embed/|shorts/|live/)([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
        )
        return patterns.firstNotNullOfOrNull {
            it.find(url.trim())?.groupValues?.get(1)
        }
    }

    private fun extractInitialData(
        html: String,
        duration: Double
    ): List<HeatPoint> {
        val marker = Regex(""""ytInitialData"\s*:\s*""").find(html)
            ?: return emptyList()

        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return emptyList()

        val json = extractBalancedObject(html, start) ?: return emptyList()

        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyList()
        }

        val points = mutableListOf<HeatPoint>()
        walkJson(root, points, duration)
        return normalize(points, duration)
    }

    private fun extractBalancedObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]

            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun walkJson(
        value: Any?,
        output: MutableList<HeatPoint>,
        duration: Double
    ) {
        when (value) {
            is JSONObject -> {
                val renderer = value.optJSONObject("heatMarkerRenderer")
                if (renderer != null) {
                    val timeMs = renderer.optLong("timeRangeStartMillis", -1L)
                    val intensity = renderer.optDouble(
                        "heatMarkerIntensityScoreNormalized",
                        Double.NaN
                    )

                    if (timeMs >= 0 && intensity.isFinite()) {
                        val time = timeMs / 1000.0
                        if (time in 0.0..duration) {
                            output.add(
                                HeatPoint(time, intensity.coerceIn(0.0, 1.0))
                            )
                        }
                    }
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    walkJson(value.opt(keys.next()), output, duration)
                }
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJson(value.opt(i), output, duration)
                }
            }
        }
    }

    private fun extractSvg(
        html: String,
        duration: Double
    ): List<HeatPoint> {
        val pathRegex = Regex(
            """<path\b(?=[^>]*class=["'][^"']*ytp-heat-map-path[^"']*["'])[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        val points = mutableListOf<HeatPoint>()

        for (match in pathRegex.findAll(html)) {
            val tag = match.value
            val d = Regex("""\bd=["']([^"']+)["']""")
                .find(tag)?.groupValues?.get(1) ?: continue

            val nearby = html.substring(
                max(0, match.range.first - 1800),
                match.range.first
            )

            val viewBox = Regex(
                """viewBox=["']\s*[-0-9.]+[ ,]+[-0-9.]+[ ,]+([0-9.]+)[ ,]+([0-9.]+)"""
            ).find(nearby)

            val width = viewBox?.groupValues?.get(1)?.toDoubleOrNull()
                ?: continue
            val height = viewBox.groupValues[2].toDoubleOrNull()
                ?: continue

            val numbers = Regex(
                """-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?"""
            ).findAll(d).mapNotNull {
                it.value.toDoubleOrNull()
            }.toList()

            if (numbers.size < 4) continue

            val samples = mutableListOf<Pair<Double, Double>>()
            var i = 0

            while (i + 1 < numbers.size) {
                val x = numbers[i]
                val y = numbers[i + 1]

                if (x in 0.0..(width * 1.2) && y.isFinite()) {
                    samples.add(
                        x.coerceIn(0.0, width) to
                            y.coerceIn(0.0, height)
                    )
                }
                i += 2
            }

            if (samples.size < 2) continue

            val buckets = min(240, max(30, duration.toInt()))

            for (bucket in 0 until buckets) {
                val x0 = width * bucket / buckets
                val x1 = width * (bucket + 1) / buckets

                val ys = samples.filter {
                    it.first >= x0 && it.first <= x1
                }.map { it.second }

                if (ys.isNotEmpty()) {
                    val y = ys.minOrNull() ?: height
                    val intensity =
                        (1.0 - y / height).coerceIn(0.0, 1.0)

                    val time =
                        duration * (bucket + 0.5) / buckets

                    points.add(HeatPoint(time, intensity))
                }
            }
        }

        return normalize(points, duration)
    }

    private fun normalize(
        points: List<HeatPoint>,
        duration: Double
    ): List<HeatPoint> {
        if (duration <= 0.0) return emptyList()

        return points.filter {
            it.time.isFinite() &&
                it.intensity.isFinite() &&
                it.time in 0.0..duration
        }.map {
            HeatPoint(it.time, it.intensity.coerceIn(0.0, 1.0))
        }.distinctBy {
            (it.time * 1000).toLong()
        }.sortedBy {
            it.time
        }
    }
}
