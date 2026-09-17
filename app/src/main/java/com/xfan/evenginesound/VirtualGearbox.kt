package com.xfan.evenginesound

/**
 * 虚拟变速箱：EV 没有真实挡位/转速，这里用 速度+负载 推算"虚拟 RPM"，
 * 并按阈值做升/降挡，挡位切换瞬间置 shifting=true 让音频引擎叠加补油 flare。
 *
 * 这是"燃油车风味"的核心——没有它，声浪就是平顺的电机音，没有换挡的"踢一脚"。
 *
 * 数据回退（取不到不影响效果）：
 *   负载源：  踏板(0x5A/5B) → 油门(0x11) → 由速度变化估算
 *   转速源：  真实转速(0x0C，若本车暴露且开关开启) → 虚拟转速
 * usePedalForLoad / useRealRpm 两个开关可由用户在 UI 里关掉（例如某车踏板数据抖动时）。
 */
class VirtualGearbox(
    var gearCount: Int = 6,
    var shiftUpRpm: Float = 6500f,
    var shiftDownRpm: Float = 2500f,
    var idleRpm: Float = 900f,
    var useRealRpm: Boolean = true,
    var usePedalForLoad: Boolean = true
) {
    var gear: Int = 1
        private set
    var shiftEvent: Boolean = false
        private set

    private var prevSpeed = 0f

    /** 挡位越低，相同车速下 RPM 越高（传动比更大） */
    private fun ratioFor(g: Int): Float = 3.2f / (1f + (g - 1) * 0.55f)

    fun update(frame: ObdFrame): EngineState {
        shiftEvent = false
        val speed = frame.speedKmh ?: 0f

        // ---- 负载源回退链 ----
        var loadSource = "speed"
        var load = 0.6f
        if (usePedalForLoad && frame.pedalPct != null) {
            load = 0.4f + (frame.pedalPct.coerceIn(0f, 100f) / 100f) * 1.4f
            loadSource = "pedal"
        } else if (frame.throttlePct != null) {
            load = 0.4f + (frame.throttlePct.coerceIn(0f, 100f) / 100f) * 1.4f
            loadSource = "throttle"
        } else {
            // 没有踏板/油门时，用速度变化（加速度）估算负载，保证声浪仍有起伏
            val accel = speed - prevSpeed
            load = (0.55f + accel * 0.05f).coerceIn(0.2f, 1.4f)
            loadSource = "speed"
        }
        prevSpeed = speed
        load = load.coerceIn(0.2f, 2.0f)

        // ---- 转速源回退链 ----
        var rpmSource = "virtual"
        var rpm = idleRpm + (speed * 38f * load) / ratioFor(gear)
        if (useRealRpm && frame.rpm != null && frame.rpm > 1f) {
            rpm = rpm * 0.3f + frame.rpm * 0.7f
            rpmSource = "real"
        }
        rpm = rpm.coerceIn(idleRpm, 9000f)

        if (gear < gearCount && rpm > shiftUpRpm && speed > 5f) {
            gear++
            shiftEvent = true
            rpm = idleRpm + (speed * 38f * load) / ratioFor(gear)
        } else if (gear > 1 && rpm < shiftDownRpm) {
            gear--
            shiftEvent = true
            rpm = idleRpm + (speed * 38f * load) / ratioFor(gear)
        }

        return EngineState(
            speedKmh = speed,
            throttlePct = frame.throttlePct ?: 0f,
            virtualRpm = rpm,
            gear = gear,
            shifting = shiftEvent,
            loadSource = loadSource,
            rpmSource = rpmSource
        )
    }

    fun reset() {
        gear = 1
        shiftEvent = false
        prevSpeed = 0f
    }
}
