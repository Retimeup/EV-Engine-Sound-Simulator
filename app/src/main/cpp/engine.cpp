#include <oboe/Oboe.h>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define TAG "EVEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ================= 参数（Kotlin 侧实时推送） =================
static std::atomic<float> g_rpm(900.0f);
static std::atomic<float> g_speed(0.0f);
static std::atomic<int>   g_gear(1);
static std::atomic<float> g_load(0.3f);    // 0..1 归一化负载
static std::atomic<float> g_blend(0.4f);   // 0=纯燃油, 1=纯电机
static std::atomic<float> g_volume(0.6f);
static std::atomic<float> g_shiftReq(0.0f); // 换挡触发（一次性置 1，回调消费）

static oboe::AudioStream* g_stream = nullptr;

// ================= 工具 =================
// xorshift32 噪声，快且够用（比 rand() 好）
static inline float frand() {
    static uint32_t s = 0x9E3779B9u;
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return (float)((int32_t)s) * (1.0f / 2147483648.0f);   // -1..1
}

// RBJ 带通（0 dB 峰值增益），用于排气/进气的"谐振峰"塑形
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    void bandpass(float sr, float f0, float Q) {
        const float w0 = 2.0f * (float)M_PI * f0 / sr;
        const float cw = cosf(w0), sw = sinf(w0);
        const float alpha = sw / (2.0f * Q);
        const float a0 = 1.0f + alpha;
        b0 = alpha / a0; b1 = 0.0f; b2 = -alpha / a0;
        a1 = -2.0f * cw / a0; a2 = (1.0f - alpha) / a0;
    }
    inline float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

// ================= 合成引擎 =================
//
// 思路（比初版「锯齿+低通」真实得多）：
//   1) 用**每缸点火脉冲**作为激励源（脉冲越窄频谱越丰富），这是发动机声的物理来源；
//   2) 脉冲经过 3 个随转速移动的谐振峰（低频咆哮 / 中频厚度 / 高频嘶吼）→ 排气音色；
//   3) 噪声经 进气峰 + 沙哑峰，并被点火脉冲门控 → 进气声与"沙沙"质感；
//   4) **负载**驱动 脉冲锐度、高频能量、噪声量、饱和驱动 → 踩下去"用力、变粗"；
//   5) 收油高转偶发"放炮"；换挡瞬间叠加 ~70ms 的补油 flare；
//   6) 电机层：3~4 层谐波 whine + 轻微 FM + 逆变器高频啸叫；
//   7) 末级 tanh 软限幅 + DC 阻断，保证不爆音。
class EngineCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        const float sr = (float)stream->getSampleRate();

        const float rpmT   = g_rpm.load(std::memory_order_relaxed);
        const float speedT = g_speed.load(std::memory_order_relaxed);
        const float loadT  = g_load.load(std::memory_order_relaxed);
        const float blend  = g_blend.load(std::memory_order_relaxed);
        const float vol    = g_volume.load(std::memory_order_relaxed);
        const float trig   = g_shiftReq.exchange(0.0f, std::memory_order_relaxed);

        // 参数平滑（~60ms）：OBD 是阶梯式的，不平滑会有"卡顿/爆音"
        const float aS = 1.0f - expf(-1.0f / (0.06f * sr));
        rpm_   += aS * (rpmT   - rpm_);
        speed_ += aS * (speedT - speed_);
        load_  += aS * (loadT  - load_);
        if (trig > 0.01f) shiftEnv_ = 1.0f;

        // 点火基频：四冲程 4 缸 = 每转点火 2 次 → rpm/60*2 = rpm/30
        float fFire = (rpm_ / 60.0f) * 2.0f;
        if (fFire < 10.0f) fFire = 10.0f;

        // 谐振峰随转速/负载移动 → 音色"往上扬"、踩下去更硬
        low_.bandpass(sr,   60.0f + fFire * 0.6f, 1.15f);
        mid_.bandpass(sr,  160.0f + fFire * 1.7f, 1.30f);
        bark_.bandpass(sr, 620.0f + fFire * 3.6f, 1.00f);
        intake_.bandpass(sr, 700.0f + rpm_ * 0.12f, 0.85f);
        rasp_.bandpass(sr, 1700.0f + rpm_ * 0.35f, 0.75f);

        const float drive = 1.0f + 2.4f * load_;

        for (int i = 0; i < numFrames; i++) {
            // ---------- 点火相位 ----------
            firePhase_ += fFire / sr;
            if (firePhase_ >= 1.0f) firePhase_ -= 1.0f;

            // 每缸点火脉冲：负载越高脉宽越窄（更"炸"）
            const float pw = 0.11f - 0.07f * load_;
            float pulse = 0.0f;
            if (firePhase_ < pw) {
                pulse = expf(-(firePhase_ / pw) * 5.0f);
            }
            // 缸间轻微不均匀，避免"电子感"
            const float cyc = (firePhase_ < 0.5f) ? 1.0f : 0.87f;

            const float noise = frand();

            // ---------- 激励 = 点火脉冲 + 少量宽带噪声 ----------
            const float exc = pulse * cyc * (0.75f + 0.5f * load_) + noise * 0.10f;

            // ---------- 排气 / 进气 谐振整形 ----------
            const float eLow  = low_.process(exc)   * 2.0f;
            const float eMid  = mid_.process(exc)   * 1.7f;
            const float eBark = bark_.process(exc)  * (0.35f + 1.5f * load_);
            const float eInt  = intake_.process(noise) * (0.08f + 0.55f * load_) * (0.55f + 0.55f * pulse);
            const float eRasp = rasp_.process(noise)   * (0.04f + 0.45f * load_) * (0.40f + 0.60f * pulse);

            // ---------- 收油回火 / 放炮 ----------
            if (load_ < 0.22f && rpm_ > 1500.0f && frand() > 0.99955f) popEnv_ = 1.0f;
            popEnv_ *= 0.99950f;                       // ~40ms
            const float pop = popEnv_ * (noise * 0.55f + eMid * 0.5f);

            // ---------- 换挡 flare（~70ms 补油"踢一脚"）----------
            float flare = 0.0f;
            if (shiftEnv_ > 0.001f) {
                flare = shiftEnv_ * (eMid * 0.9f + noise * 0.30f + pulse * 0.7f);
                shiftEnv_ *= 0.99970f;                 // τ≈70ms，可听见
            } else {
                shiftEnv_ = 0.0f;
            }

            // ---------- 燃油层总成 ----------
            float fuel = exc * 0.75f + eLow + eMid + eBark + eInt + eRasp + pop + flare;
            fuel = tanhf(fuel * drive) * 0.92f;

            // ---------- 电机层：多层 whine + 逆变器啸叫 ----------
            const float w1 = 260.0f + speed_ * 7.0f;
            const float w2 = w1 * 2.03f;
            const float w3 = w1 * 3.11f;
            const float inv = 820.0f + speed_ * 24.0f + rpm_ * 0.16f;   // 高频"电流"啸叫
            phE1_ += w1 / sr;  if (phE1_ >= 1.0) phE1_ -= 1.0;
            phE2_ += w2 / sr;  if (phE2_ >= 1.0) phE2_ -= 1.0;
            phE3_ += w3 / sr;  if (phE3_ >= 1.0) phE3_ -= 1.0;
            phE4_ += inv / sr; if (phE4_ >= 1.0) phE4_ -= 1.0;

            const float fm  = 0.08f * sinf(2.0f * (float)M_PI * phE4_);          // 轻 FM，更"通电"
            const float e1  = sinf(2.0f * (float)M_PI * (phE1_ + fm));
            const float e2  = 0.34f * sinf(2.0f * (float)M_PI * phE2_);
            const float e3  = 0.15f * sinf(2.0f * (float)M_PI * phE3_);
            const float e4  = 0.07f * sinf(2.0f * (float)M_PI * phE4_);
            const float spool = spool_.process(noise, 0.0025f) * (speed_ * 0.012f);
            const float elec = (e1 + e2 + e3 + e4) * 0.55f + spool;

            // ---------- 混合 + 软限幅 + DC 阻断 ----------
            float mix = fuel * (1.0f - blend) + elec * blend;
            mix *= vol;
            mix = tanhf(mix * 1.1f) * 0.85f;
            dcx_ = mix - dcX1_ + 0.9995f * dcx_;
            dcX1_ = mix;
            out[i] = dcx_;
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    float rpm_ = 900.0f, speed_ = 0.0f, load_ = 0.3f;
    float firePhase_ = 0.0f, shiftEnv_ = 0.0f, popEnv_ = 0.0f;
    double phE1_ = 0.0, phE2_ = 0.0, phE3_ = 0.0, phE4_ = 0.0;
    float dcx_ = 0.0f, dcX1_ = 0.0f;

    struct OnePole {
        float v = 0.0f;
        inline float process(float x, float a) { v += a * (x - v); return v; }
    } spool_;

    Biquad low_, mid_, bark_, intake_, rasp_;
};

static EngineCallback g_callback;

// ================= JNI =================
extern "C" JNIEXPORT jint JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeStart(JNIEnv*, jobject) {
    if (g_stream != nullptr) return 0;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);   // 与音乐共享音频流
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
    LOGI("engine audio stream started");
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
Java_com_xfan_evenginesound_AudioEngine_nativeSetRpm(JNIEnv*, jobject, jfloat v)    { g_rpm.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetSpeed(JNIEnv*, jobject, jfloat v)  { g_speed.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetGear(JNIEnv*, jobject, jint v)     { g_gear.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetLoad(JNIEnv*, jobject, jfloat v)   { g_load.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetBlend(JNIEnv*, jobject, jfloat v)  { g_blend.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeSetVolume(JNIEnv*, jobject, jfloat v) { g_volume.store(v); }
extern "C" JNIEXPORT void JNICALL
Java_com_xfan_evenginesound_AudioEngine_nativeTriggerShift(JNIEnv*, jobject)        { g_shiftReq.store(1.0f); }
