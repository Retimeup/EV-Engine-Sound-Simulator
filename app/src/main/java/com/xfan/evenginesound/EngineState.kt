package com.xfan.evenginesound

/**
 * 一帧的车辆/引擎状态。EV 没有真实发动机转速，virtualRpm 由速度+负载推算。
 * loadSource / rpmSource 记录本帧实际使用了哪个数据源，UI 可据此显示回退情况。
 */
data class EngineState(
    val speedKmh: Float = 0f,
    val throttlePct: Float = 0f,
    val virtualRpm: Float = 0f,
    val gear: Int = 1,
    val shifting: Boolean = false,
    val load: Float = 0.6f,            // 归一化负载 0..1，驱动音色（负载越高越"用力"、沙哑）
    val loadSource: String = "none",   // "pedal" | "throttle" | "speed"
    val rpmSource: String = "virtual"  // "real" | "virtual"
)
