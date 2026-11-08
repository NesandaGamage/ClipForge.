package com.clipforge.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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
    private lateinit var progressBar: ProgressBar

    private val pageColor = Color.rgb(247, 248, 252)
    private val cardColor = Color.WHITE
    private val primaryColor = Color.rgb(91, 83, 220)
    private val textColor = Color.rgb(32, 35, 48)
    private val mutedColor = Color.rgb(112, 117, 132)
    private val borderColor = Color.rgb(229, 231, 239)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(18), dp(22), dp(18), dp(20))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(22))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "CF"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(primaryColor, 14)
        }

        brandRow.addView(
            logo,
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        brandText.addView(title("ClipForge", 21f))

        brandText.addView(
            smallText("AI VIDEO CLIP EDITOR")
        )

        brandRow.addView(
            brandText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val version = TextView(this).apply {
            text = "BETA"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(
                Color.rgb(238, 237, 255),
                20
            )
        }

        brandRow.addView(version)
        header.addView(brandRow)

        val heading = title(
            "Create your next\nbest moment.",
            29f
        ).apply {
            setPadding(0, dp(25), 0, dp(8))
        }

        header.addView(heading)

        header.addView(
            bodyText(
                "Turn long videos into short, shareable clips with on-device AI."
            )
        )

        content.addView(header)

        // YouTube source
        val ytCard = sectionCard(
            content,
            "YOUTUBE SOURCE",
            "Use the original video link to look for replay data."
        )

        ytInput = EditText(this).apply {
            hint = "Paste YouTube video URL"
            setSingleLine(true)
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = inputBackground()
        }

        ytCard.addView(ytInput, matchWrap())

        val ytButton = secondaryButton("Open YouTube video")
        ytCard.addView(ytButton, matchWrap())

        ytButton.setOnClickListener {

            val url = ytInput.text.toString().trim()

            if (
                !url.contains("youtube.com/") &&
                !url.contains("youtu.be/")
            ) {
                toast("Enter a valid YouTube URL")
            } else {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    )

                    status.text =
                        "Import an authorized video file before editing."

                } catch (_: Exception) {
                    toast("Could not open this YouTube link")
                }
            }
        }

        // Import video
        val sourceCard = sectionCard(
            content,
            "01  |  YOUR VIDEO",
            "Choose a video stored on your phone."
        )

        val chooseButton = primaryButton("＋  Import video")
        sourceCard.addView(chooseButton, matchWrap())

        val videoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(
                Color.rgb(247, 248, 252),
                12,
                borderColor
            )
        }

        val videoIcon = TextView(this).apply {
            text = "▶"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
            background = rounded(
                Color.rgb(238, 237, 255),
                10
            )
        }

        videoPanel.addView(
            videoIcon,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        videoLabel = bodyText("No video selected").apply {
            setPadding(dp(12), 0, 0, 0)
        }

        videoPanel.addView(
            videoLabel,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val videoParams = matchWrap().apply {
            topMargin = dp(12)
        }

        sourceCard.addView(videoPanel, videoParams)

        chooseButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }

            startActivityForResult(intent, pickVideo)
        }

        // AI selection
        val aiCard = sectionCard(
            content,
            "02  |  AI CLIP FINDER",
            "Let ClipForge identify an interesting moment."
        )

        aiCard.addView(
            bodyText(
                "Whisper analyses speech locally on your phone. " +
                "YouTube Most Replayed data can also help rank " +
                "the strongest moments when available."
            ),
            matchWrap()
        )

        val aiInfo = TextView(this).apply {
            text = "ONE BEST CLIP  •  8–60 SECONDS"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
            setPadding(0, dp(14), 0, dp(4))
        }

        aiCard.addView(aiInfo)

        aiButton = primaryButton("Find My Best Clip")
        aiCard.addView(aiButton, matchWrap())

        aiButton.setOnClickListener {
            createAiClip()
        }

        // Manual editing
        val editCard = sectionCard(
            content,
            "03  |  MANUAL TRIMMING",
            "Choose exactly where your clip should begin and end."
        )

        editCard.addView(label("Start time (seconds)"))

        startInput = editText("0")
        editCard.addView(startInput, matchWrap())

        editCard.addView(label("End time (seconds)"))

        endInput = editText("")
        editCard.addView(endInput, matchWrap())

        editCard.addView(
            bodyText(
                "Manual export keeps the source video's resolution " +
                "and original aspect ratio."
            ).apply {
                setPadding(0, dp(12), 0, 0)
            }
        )

        // Export
        val exportCard = sectionCard(
            content,
            "04  |  EXPORT",
            "Save your finished clip to your phone."
        )

        exportButton = secondaryButton("Export MP4")
        exportCard.addView(exportButton, matchWrap())

        exportButton.setOnClickListener {
            exportClip()
        }

        // Processing status
        val statusCard = sectionCard(
            content,
            "PROCESSING STATUS",
            "Your current ClipForge activity."
        )

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        statusCard.addView(
            progressBar,
            LinearLayout.LayoutParams(
                dp(28),
                dp(28)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        status = bodyText(
            "Ready. Import a video to get started."
        )

        statusCard.addView(status)

        // Footer
        content.addView(
            TextView(this).apply {
                text =
                    "Your source video stays on your device.\n" +
                    "Exported clips are saved in Movies/ClipForge."

                textSize = 11f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(12))
            }
        )

        setContentView(root)
    }

    // AI clip generation
    private fun createAiClip() {

        val uri = selectedUri ?: run {
            toast("Import a video first")
            return
        }

        aiButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

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

                        runOnUiThread {

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
                                    "Your best AI clip is saved to Movies/ClipForge."

                                toast("Best clip saved successfully!")

                            } catch (e: Exception) {

                                status.text =
                                    "Could not save clip: ${e.message}"

                            } finally {
                                aiButton.isEnabled = true
                                progressBar.visibility = View.GONE
                            }
                        }
                    }

                    override fun onError(message: String) {

                        runOnUiThread {

                            status.text =
                                "AI processing failed: $message"

                            aiButton.isEnabled = true
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            )

        } catch (t: Throwable) {

            status.text =
                "Could not start AI: ${t.javaClass.simpleName}: ${t.message}"

            aiButton.isEnabled = true
            progressBar.visibility = View.GONE
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
                        progressBar.visibility = View.GONE
                        transformer = null

                        toast("MP4 saved to Movies/ClipForge")

                    } catch (e: Exception) {

                        contentResolver.delete(outputUri, null, null)
                        tempFile.delete()

                        status.text =
                            "Could not save exported MP4: ${e.message}"

                        exportButton.isEnabled = true
                        progressBar.visibility = View.GONE
                        transformer = null
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {

                    tempFile.delete()
                    contentResolver.delete(outputUri, null, null)

                    status.text =
                        "Export failed: ${exportException.message}"

                    exportButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    transformer = null
                }
            })
            .build()

        try {

            exportButton.isEnabled = false
            progressBar.visibility = View.VISIBLE

            status.text =
                "Exporting clip… Keep ClipForge open until it finishes."

            transformer!!.start(edited, tempFile.absolutePath)

        } catch (e: Exception) {

            tempFile.delete()
            contentResolver.delete(outputUri, null, null)

            exportButton.isEnabled = true
            progressBar.visibility = View.GONE

            status.text =
                "Export could not start: ${e.message}"

            transformer = null
        }
    }

    // Video picker result
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode == pickVideo &&
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

                videoLabel.text = "Video selected successfully"

                status.text =
                    "Video ready. Find your best clip or use manual trimming."
            }
        }
    }

    // UI components
    private fun sectionCard(
        parent: LinearLayout,
        heading: String,
        description: String
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = rounded(cardColor, 18, borderColor)
            elevation = dp(2).toFloat()
        }

        parent.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(15)
            }
        )

        card.addView(
            TextView(this).apply {
                text = heading
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
        )

        card.addView(
            TextView(this).apply {
                text = description
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(0, dp(5), 0, dp(9))
            }
        )

        return card
    }

    private fun title(value: String, size: Float) =
        TextView(this).apply {
            text = value
            textSize = size
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }

    private fun smallText(value: String) =
        TextView(this).apply {
            text = value
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(mutedColor)
            setPadding(0, dp(3), 0, 0)
        }

    private fun bodyText(value: String) =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(mutedColor)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun label(value: String) =
        TextView(this).apply {
            text = value
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dp(13), 0, dp(4))
        }

    private fun editText(value: String) =
        EditText(this).apply {
            setText(value)
            singleLine = true

            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = inputBackground()
        }

    private fun primaryButton(value: String) =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(primaryColor, 13)
            minimumHeight = dp(50)
            stateListAnimator = null
        }

    private fun secondaryButton(value: String) =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
            background = rounded(
                Color.rgb(238, 237, 255),
                13
            )
            minimumHeight = dp(48)
            stateListAnimator = null
        }

    private fun inputBackground() =
        rounded(Color.rgb(249, 250, 253), 12, borderColor)

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()

        if (strokeColor != null) {
            setStroke(dp(1), strokeColor)
        }
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
