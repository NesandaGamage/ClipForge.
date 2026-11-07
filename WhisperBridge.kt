package com.clipforge.app

/** JNI bridge. Loading is explicit so missing native libraries can be reported in the UI. */
object WhisperBridge {
    @Volatile private var loaded = false
    @Synchronized fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("clipforge_whisper")
            loaded = true
        }
    }
    external fun transcribeNative(modelPath: String, wavPath: String): String
}
