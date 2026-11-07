#include <jni.h>
#include <android/log.h>
#include <whisper.h>
#include <fstream>
#include <vector>
#include <cstdint>
#include <cstring>
#include <string>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ClipForgeWhisper", __VA_ARGS__)

static bool read_wav(const char *path, std::vector<float> &samples) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    char header[12]; f.read(header, 12);
    if (f.gcount() != 12 || std::memcmp(header, "RIFF", 4) || std::memcmp(header + 8, "WAVE", 4)) return false;
    uint16_t format = 0, channels = 0, bits = 0; uint32_t rate = 0; bool found = false;
    while (f && !found) {
        char id[4]; uint32_t size = 0; f.read(id, 4); f.read(reinterpret_cast<char*>(&size), 4);
        if (!f) break;
        if (!std::memcmp(id, "fmt ", 4)) {
            f.read(reinterpret_cast<char*>(&format), 2); f.read(reinterpret_cast<char*>(&channels), 2);
            f.read(reinterpret_cast<char*>(&rate), 4); f.seekg(6, std::ios::cur);
            f.read(reinterpret_cast<char*>(&bits), 2);
            if (size > 16) f.seekg(size - 16, std::ios::cur);
        } else if (!std::memcmp(id, "data", 4)) {
            if (format != 1 || channels != 1 || bits != 16 || rate != 16000) return false;
            std::vector<int16_t> pcm(size / 2); f.read(reinterpret_cast<char*>(pcm.data()), size);
            samples.resize(pcm.size());
            for (size_t i = 0; i < pcm.size(); ++i) samples[i] = pcm[i] / 32768.0f;
            found = true;
        } else f.seekg(size, std::ios::cur);
        if (size & 1) f.seekg(1, std::ios::cur);
    }
    return found && !samples.empty();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_clipforge_app_WhisperBridge_transcribeNative(JNIEnv *env, jobject, jstring modelPath, jstring wavPath) {
    const char *model = env->GetStringUTFChars(modelPath, nullptr);
    const char *wav = env->GetStringUTFChars(wavPath, nullptr);
    std::vector<float> samples;
    if (!read_wav(wav, samples)) {
        env->ReleaseStringUTFChars(modelPath, model); env->ReleaseStringUTFChars(wavPath, wav);
        return env->NewStringUTF("ERROR\tCould not read 16 kHz mono PCM WAV");
    }
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model, cparams);
    env->ReleaseStringUTFChars(modelPath, model); env->ReleaseStringUTFChars(wavPath, wav);
    if (!ctx) return env->NewStringUTF("ERROR\tCould not load Whisper model");
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
    params.translate = false; params.n_threads = 4; params.no_context = true;
    const int rc = whisper_full(ctx, params, samples.data(), (int)samples.size());
    std::string result;
    if (rc == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const int64_t start = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t end = whisper_full_get_segment_t1(ctx, i) * 10;
            const char *txt = whisper_full_get_segment_text(ctx, i);
            result += std::to_string(start) + "\t" + std::to_string(end) + "\t" + (txt ? txt : "") + "\n";
        }
    } else result = "ERROR\tWhisper transcription failed";
    whisper_free(ctx);
    return env->NewStringUTF(result.c_str());
}
