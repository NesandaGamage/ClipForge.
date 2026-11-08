
package com.clipforge.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

class MainActivity : AppCompatActivity() {

    private val pickVideo = 101

    private var selectedUri: Uri? = null
    private var transformer: Transformer? = null

    private lateinit var status: TextView
    private lateinit var videoLabel: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var exportButton: Button
    private lateinit var aiButton: Button
    private lateinit var ytInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(0xFF101018.toInt())
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "ClipForge"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "Turn long videos into shareable clips"
            textSize = 14f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, dp(4), 0, dp(20))
        })

        // YouTube section
        val ytCard = section(content, "YouTube link")

        ytInput = EditText(this).apply {
            hint = "Paste a YouTube URL"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF898797.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF252432.toInt())
        }

        ytCard.addView(ytInput, matchWrap())

        val ytButton = button("Open YouTube video")
        ytCard.addView(ytButton, matchWrap())

        ytButton.setOnClickListener {
            val url = ytInput.text.toString().trim()

            if (!url.contains("youtube.com/") &&
                !url.contains("youtu.be/")
            ) {
                toast("Enter a valid YouTube URL")
            } else {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                )

                status.text =
                    "Import an authorized video file before editing."
            }
        }

        ytCard.addView(TextView(this).apply {
            text =
                "Paste a YouTube URL to help ClipForge look for replay " +
                "data. You must still import an authorized video file " +
                "below. ClipForge does not download YouTube videos."

            textSize = 12f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, dp(8), 0, 0)
        })

        // Video import
        val sourceCard = section(content, "1. Choose a video")

        val choose = button("＋  Import video from phone")
        sourceCard.addView(choose, matchWrap())

        videoLabel = TextView(this).apply {
            text = "No video selected"
            textSize = 13f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, dp(10), 0, 0)
        }

        sourceCard.addView(videoLabel)

        choose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }

            startActivityForResult(intent, pickVideo)
        }

        // AI clip selection
        val aiCard = section(content, "2. AI best clip selection")

        aiCard.addView(TextView(this).apply {
            text =
                "ClipForge analyses speech using the free Whisper AI " +
                "model on your phone. It looks for interesting moments " +
                "and, when available, YouTube Most Replayed data. " +
                "It selects one clip between 8 and 60 seconds."

            textSize = 12f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, 0, 0, dp(8))
        })

        aiButton = button("✨  Find my best clip")
        aiCard.addView(aiButton, matchWrap())

        aiButton.setOnClickListener {
            createAiClip()
        }

        // Manual trimming
        val editCard = section(content, "3. Manual clip trimming")

        editCard.addView(label("Start time (seconds)"))

        startInput = editText("0")
        editCard.addView(startInput, matchWrap())

        editCard.addView(label("End time (seconds)"))

        endInput = editText("")
        editCard.addView(endInput, matchWrap())

        editCard.addView(TextView(this).apply {
            text =
                "Output: MP4 (H.264 video + AAC audio), " +
                "source resolution and original aspect ratio."

            textSize = 12f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, dp(12), 0, 0)
        })

        // Manual export
        val exportCard = section(content, "4. Manual export")

        exportButton = button("Export MP4")
        exportCard.addView(exportButton, matchWrap())

        exportButton.setOnClickListener {
            exportClip()
        }

        // Status
        status = TextView(this).apply {
            text = "Ready. Select a video to begin."
            textSize = 13f
            setTextColor(0xFFB9B7C7.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }

        content.addView(status)

        content.addView(TextView(this).apply {
            text =
                "Your source video stays on your device. " +
                "Exported clips are saved to Movies/ClipForge."

            textSize = 11f
            setTextColor(0xFF898797.toInt())
            setPadding(0, dp(12), 0, dp(16))
        })

        setContentView(root)
    }

    // AI creates one best clip
    private fun createAiClip() {

        val uri = selectedUri ?: run {
            toast("Import a video first")
            return
        }

        aiButton.isEnabled = false

        status.text =
            "Starting AI analysis… Keep ClipForge open while it works."

        val youtubeUrl = ytInput.text.toString().trim()

        try {

            OnDeviceClipEngine(this).createBestClip(
                uri,
                youtubeUrl,
                object : OnDeviceClipEngine.Listener {

                    override fun onProgress(message: String) {
                        runOnUiThread {
                            status.text = message
                        }
                    }

                    override fun onComplete(files: List<File>) {

                        try {

                            val file = files.firstOrNull()
                                ?: throw IllegalStateException(
                                    "AI did not produce a clip"
                                )

                            val values = ContentValues().apply {
                                put(
                                    MediaStore.Video.Media.DISPLAY_NAME,
                                    "ClipForge_Best_${System.currentTimeMillis()}.mp4"
                                )

                                put(
                                    MediaStore.Video.Media.MIME_TYPE,
                                    "video/mp4"
                                )

                                put(
                                    MediaStore.Video.Media.RELATIVE_PATH,
                                    "Movies/ClipForge"
                                )
                            }

                            val outUri = contentResolver.insert(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                values
                            ) ?: throw IllegalStateException(
                                "Could not create Gallery entry"
                            )

                            contentResolver.openOutputStream(
                                outUri,
                                "w"
                            )!!.use { output ->

                                file.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }

                            file.delete()

                            status.text =
                                "Done! Your best AI clip is saved to Movies/ClipForge."

                            toast("Best clip saved successfully!")

                        } catch (e: Exception) {

                            status.text =
                                "Could not save clip: ${e.message}"

                        } finally {
                            aiButton.isEnabled = true
                        }
                    }

                    override fun onError(message: String) {

                        runOnUiThread {
                            status.text =
                                "AI processing failed: $message"

                            aiButton.isEnabled = true
                        }
                    }
                }
            )

        } catch (t: Throwable) {

            status.text =
                "Could not start AI: ${t.javaClass.simpleName}: ${t.message}"

            aiButton.isEnabled = true
        }
    }

    // Manual trimming and export
    private fun exportClip() {

        val uri = selectedUri ?: run {
            toast("Import a video first")
            return
        }

        val startMs =
            (startInput.text.toString().toDoubleOrNull() ?: 0.0)
                .coerceAtLeast(0.0) * 1000

        val endSeconds =
            endInput.text.toString().toDoubleOrNull()

        if (endSeconds == null || endSeconds * 1000 <= startMs) {
            toast("Enter an end time greater than the start time")
            return
        }

        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "ClipForge_${System.currentTimeMillis()}.mp4"
            )

            put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4"
            )

            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "Movies/ClipForge"
            )
        }

        val outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: run {
            status.text = "Could not create output file."
            return
        }

        val tempFile = File(
            cacheDir,
            "clip_${System.currentTimeMillis()}.mp4"
        )

        val clip = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs.toLong())
                    .setEndPositionMs(
                        (endSeconds * 1000).toLong()
                    )
                    .build()
            )
            .build()

        val edited = EditedMediaItem.Builder(clip).build()

        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {

                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult
                ) {

                    try {

                        contentResolver.openOutputStream(
                            outputUri,
                            "w"
                        )!!.use { out ->

                            tempFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }

                        tempFile.delete()

                        status.text =
                            "Export complete! Saved to Movies/ClipForge."

                        exportButton.isEnabled = true
                        toast("MP4 saved to Movies/ClipForge")

                    } catch (e: Exception) {

                        status.text =
                            "Could not save exported MP4: ${e.message}"

                        exportButton.isEnabled = true
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {

                    tempFile.delete()

                    status.text =
                        "Export failed: ${exportException.message}"

                    exportButton.isEnabled = true
                }
            })
            .build()

        try {

            exportButton.isEnabled = false

            status.text =
                "Exporting clip… keep ClipForge open until it finishes."

            transformer!!.start(edited, tempFile.absolutePath)

        } catch (e: Exception) {

            exportButton.isEnabled = true

            status.text =
                "Export could not start: ${e.message}"
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == pickVideo &&
            resultCode == Activity.RESULT_OK
        ) {

            data?.data?.let { uri ->

                selectedUri = uri

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Temporary permission may be provided.
                }

                videoLabel.text =
                    "Selected: ${uri.lastPathSegment ?: "Video"}"

                status.text =
                    "Video ready. Find your best clip or use manual trimming."
            }
        }
    }

    private fun section(
        parent: LinearLayout,
        title: String
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF1B1A25.toInt())
        }

        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        }

        parent.addView(card, lp)

        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })

        return card
    }

    private fun label(value: String) =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFCAC8D5.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }

    private fun editText(value: String) =
        EditText(this).apply {
            setText(value)

            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF898797.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF252432.toInt())
        }

    private fun button(textValue: String) =
        Button(this).apply {
            text = textValue
            isAllCaps = false
        }

    private fun matchWrap() =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
