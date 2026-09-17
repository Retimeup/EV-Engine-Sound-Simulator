package com.xfan.evenginesound

/**
 * JNI 桥：把 Kotlin 侧的引擎状态/参数推给 C++ 的 Oboe 实时合成引擎。
 * 所有 native 方法由 src/main/cpp/engine.cpp 实现。
 */
object AudioEngine {
    init {
        System.loadLibrary("engine")
    }

    external fun nativeStart(): Int
    external fun nativeStop(): Int
    external fun nativeSetRpm(rpm: Float)
    external fun nativeSetSpeed(speed: Float)
    external fun nativeSetGear(gear: Int)
    external fun nativeSetBlend(blend: Float)
    external fun nativeSetVolume(volume: Float)
    external fun nativeTriggerShift()

    /** 0 = 成功 */
    fun start(): Boolean = nativeStart() == 0

    fun stop() = nativeStop()

    fun pushState(state: EngineState) {
        nativeSetRpm(state.virtualRpm)
        nativeSetSpeed(state.speedKmh)
        nativeSetGear(state.gear)
        if (state.shifting) nativeTriggerShift()
    }

    fun setBlend(blend: Float) = nativeSetBlend(blend.coerceIn(0f, 1f))
    fun setVolume(volume: Float) = nativeSetVolume(volume.coerceIn(0f, 1f))
}
