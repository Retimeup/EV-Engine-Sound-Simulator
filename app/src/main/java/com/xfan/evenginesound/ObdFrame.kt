package com.xfan.evenginesound

/**
 * 一次 OBD 轮询得到的全部可选字段 + 各 PID 可用性标记。
 * 可用性在连接后累计：只要某个 PID 成功解析过至少一次，available[pid] = true。
 *
 * 这是"取不到该数据不影响效果"的核心：UI 显示本车暴露了哪些数据，
 * 虚拟变速箱据此在 踏板 -> 油门 -> 速度估算 之间做回退，在 真实转速 -> 虚拟转速 之间做回退。
 */
data class ObdFrame(
    val speedKmh: Float?,   // PID 0x0D 车速
    val throttlePct: Float?,// PID 0x11 油门位置
    val pedalPct: Float?,   // PID 0x5A/0x5B 加速踏板位置（EV 上比油门更可靠）
    val rpm: Float?,        // PID 0x0C 发动机/电机转速（多数 EV 为 0 / 无）
    val available: Map<String, Boolean> // "0D"/"11"/"5A"/"5B"/"0C" -> 是否曾成功解析
)
