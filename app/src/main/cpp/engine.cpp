#include <oboe/Oboe.h>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdlib>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

#define TAG "EVEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- 引擎参数（由 Kotlin 侧实时推送） ----
static std::atomic<float> g_rpm(900.0f);
static std::atomic<float> g_speed(0.0f);
static std::atomic<int>   g_gear(1);
static std::atomic<float> g_blend(0.4f);   // 0=纯燃油, 1=纯电机
static std::atomic<float> g_volume(0.6f);
static std::atomic<float> g_shiftEnv(0.0f); // 换挡 flare 包络，自动衰减

// ---- 振荡器相位 / 滤波状态 ----
static double g_phaseComb(0.0);
static double g_phaseElec(0.0);
static double g_phaseHarm(0.0);
static float  g_lpComb(0.0f);

static oboe::AudioStream* g_stream = nullptr;

class EngineCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        const int32_t sr = stream->getSampleRate();
        for (int i = 0; i < numFrames; i++) {
            const float rpm    = g_rpm.load(std::memory_order_relaxed);
            const float speed  = g_speed.load(std::memory_order_relaxed);
            const float blend  = g_blend.load(std::memory_order_relaxed);
            const float vol    = g_volume.load(std::memory_order_relaxed);

            // ===== 燃油层：锯齿振荡 + 点火脉冲 + 排气共振低通 =====
            const float firingFreq = (rpm / 60.0f) * 2.0f; // 四冲程直列四缸，每转点火2次
            const float combFreq = firingFreq * 0.5f;
            g_phaseComb += combFreq / sr;
            if (g_phaseComb >= 1.0) g_phaseComb -= 1.0;
            const float saw = 2.0f * (g_phaseComb - std::floor(g_phaseComb + 0.5f));
            const float noise = ((float)std::rand() / (float)RAND_MAX) * 2.0f - 1.0f;
            const float cutoff = 200.0f + (rpm / 7000.0f) * 2200.0f;
            const float a = 1.0f - std::exp(-2.0f * M_PI * cutoff / (float)sr);
            g_lpComb += a * (noise - g_lpComb);
            const float impulse = (g_phaseComb < (combFreq / sr) * 2.0f) ? 1.0f : 0.0f;
            const float comb = saw * 0.5f + g_lpComb * 0.25f + impulse * 0.15f;

            // ===== 电机层：随速上升的正弦 whine + 谐波 + 科技感 LFO =====
            const float whine = 800.0f + speed * 8.0f;
            g_phaseElec += whine / sr;
            if (g_phaseElec >= 1.0) g_phaseElec -= 1.0;
            const float sinv = std::sin(2.0f * M_PI * g_phaseElec);
            g_phaseHarm += (whine * 2.0f) / sr;
            if (g_phaseHarm >= 1.0) g_phaseHarm -= 1.0;
            const float sinv2 = std::sin(2.0f * M_PI * g_phaseHarm) * 0.4f;
            float elec = (sinv + sinv2) * 0.5f;
            const float lfo = 0.85f + 0.15f * std::sin(2.0f * M_PI * ((float)i / (float)sr) * 0.7f);
            elec *= lfo;

            // ===== 换挡 flare：齿轮切换瞬间的补油"踢一脚" =====
            float se = g_shiftEnv.load(std::memory_order_relaxed);
            if (se > 0.001f) {
                se *= 0.95f;
                g_shiftEnv.store(se, std::memory_order_relaxed);
            }
            const float flare = se * (0.5f * sinv + 0.2f * noise + 0.3f);

            // ===== 混合 + 软限幅 =====
            float mix = (comb * (1.0f - blend) + elec * blend + flare) * vol;
            mix = std::tanh(mix * 1.2f);
            out[i] = mix * 0.8f;
        }
        return oboe::DataCallbackResult::Continue;
    }
};

static EngineCallback g_callback;

extern "C" JNIEXPORT jint JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeStart(JNIEnv*, jobject) {
    if (g_stream != nullptr) return 0;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared); // 与音乐共享音频流
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(1);
    builder.setSampleRate(48000);
    builder.setCallback(&g_callback);
    oboe::Result result = builder.openStream(&g_stream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        g_stream = nullptr;
        return static_cast<jint>(result);
    }
    result = g_stream->start();
    if (result != oboe::Result::OK) {
        LOGE("start failed: %s", oboe::convertToText(result));
        g_stream->close();
        g_stream = nullptr;
        return static_cast<jint>(result);
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeStop(JNIEnv*, jobject) {
    if (g_stream == nullptr) return 0;
    g_stream->stop();
    g_stream->close();
    g_stream = nullptr;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetRpm(JNIEnv*, jobject, jfloat v)   { g_rpm.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetSpeed(JNIEnv*, jobject, jfloat v) { g_speed.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetGear(JNIEnv*, jobject, jint v)    { g_gear.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetBlend(JNIEnv*, jobject, jfloat v) { g_blend.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetVolume(JNIEnv*, jobject, jfloat v){ g_volume.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeTriggerShift(JNIEnv*, jobject)       { g_shiftEnv.store(1.0f); }
