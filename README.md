# ClipForge AI — on-device Android video clipper

ClipForge is a native Kotlin Android app. The AI workflow runs on the phone: it does not send video or transcripts to a cloud AI service and does not require an API key.

## AI pipeline

1. Import a local video (or an authorized MP4 copy of your own video).
2. On first use, download the multilingual Whisper tiny model (`ggml-tiny.bin`, roughly 75 MB) from the upstream whisper.cpp model repository. This is the only network-dependent AI setup step.
3. FFmpeg extracts mono 16 kHz audio locally.
4. whisper.cpp transcribes the audio on-device through JNI/NDK.
5. A local highlight scorer ranks transcript windows using hook terms, questions/exclamations and speech density. It selects one best window from each of five timeline regions so the outputs are distinct and exactly five.
6. FFmpeg crops and exports five vertical 1080x1920 MP4 files to `Movies/ClipForge`.

The current scorer is a lightweight heuristic over transcript text, not a trained virality predictor. It estimates potentially engaging moments; it cannot guarantee views or engagement. The first version does not yet perform visual semantic understanding, face/emotion detection, or automatic burned-in captions. The clips are vertical crops and may crop subjects near the edges.

## Open-source components

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) — MIT-licensed inference engine; built from source by CMake during the Android build. The model is downloaded separately to app-private storage.
- [FFmpegKit maintained](https://github.com/ffmpegkit-maintained/ffmpeg) — LGPL-3.0 Android package used for local audio extraction and video encoding.

Review the upstream license files and obligations before redistributing the app. Model weights are not bundled in this source ZIP; the app downloads them after installation.

## Build with GitHub Actions

1. Upload the **contents** of this ZIP to the root of your GitHub repository, including the hidden `.github` folder. Do not upload only the ZIP file.
2. Open **Actions** in GitHub and select **Build ClipForge APK**.
3. Choose **Run workflow**. The first build compiles whisper.cpp for Android and can take longer than the old build.
4. When complete, download the `ClipForge-AI-debug-apk` artifact and extract `app-debug.apk`.
5. Install the APK on your Pixel 7a. Allow installation from the app used to open the APK if Android asks.

The workflow targets `arm64-v8a`, suitable for the Pixel 7a, and uses Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.

## Use

1. Import a video from the phone.
2. Tap **Find my 5 best clips**.
3. On first run, allow time and network access for the Whisper model download. Later transcription and editing can run offline.
4. Keep ClipForge open while processing. The five exports are saved to `Movies/ClipForge`.

For a video shorter than five seconds, the app asks for a longer source. The five output segments are selected from five separate timeline regions; clip duration is at most 35 seconds and may be shorter for short videos.

## YouTube

The YouTube field opens the source video in YouTube. ClipForge does not scrape YouTube streams or bypass download restrictions. Import a video you own or are authorized to download/use, such as your own video exported from YouTube Studio.

## Manual editing

The original manual start/end trim workflow is retained alongside the AI workflow.
