package com.xfan.evenginesound

/**
 * 解析 ELM327 返回的 OBD-II PID 文本。
 * 常见响应形如："41 0D 5C"（速度）、"41 11 1F"（油门）、"41 0C 1A 90"（转速）。
 * '>' 是 ELM327 的提示符；响应里可能带空格/回车，先清洗。
 */
object ObdParser {

    fun clean(s: String): String = s
        .replace(">", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** PID 0x0D 车速，单位 km/h */
    fun parseSpeed(response: String): Float? {
        val parts = clean(response).split(" ")
        val idx = parts.indexOf("0D")
        if (idx >= 0 && idx + 1 < parts.size) {
            return parts[idx + 1].toIntOrNull(16)?.toFloat()
        }
        return null
    }

    /** PID 0x11 油门位置，百分比 0-100 */
    fun parseThrottle(response: String): Float? {
        val parts = clean(response).split(" ")
        val idx = parts.indexOf("11")
        if (idx >= 0 && idx + 1 < parts.size) {
            val v = parts[idx + 1].toIntOrNull(16) ?: return null
            return v * 100f / 255f
        }
        return null
    }

    /** PID 0x0C 发动机/电机转速（多数电车为 0 或 NO DATA，用于可选融合） */
    fun parseRpm(response: String): Float? {
        val parts = clean(response).split(" ")
        val idx = parts.indexOf("0C")
        if (idx >= 0 && idx + 2 < parts.size) {
            val a = parts[idx + 1].toIntOrNull(16) ?: return null
            val b = parts[idx + 2].toIntOrNull(16) ?: return null
            return (a * 256 + b) / 4f
        }
        return null
    }

    /** PID 0x5A 加速踏板位置 D（多数 EV 上比 0x11 油门更直接，强烈推荐作为负载源） */
    fun parsePedalD(response: String): Float? {
        val parts = clean(response).split(" ")
        val idx = parts.indexOf("5A")
        if (idx >= 0 && idx + 1 < parts.size) {
            val v = parts[idx + 1].toIntOrNull(16) ?: return null
            return v * 100f / 255f
        }
        return null
    }

    /** PID 0x5B 加速踏板位置 E（冗余踏板信号，部分车同时提供两个以提高可靠性） */
    fun parsePedalE(response: String): Float? {
        val parts = clean(response).split(" ")
        val idx = parts.indexOf("5B")
        if (idx >= 0 && idx + 1 < parts.size) {
            val v = parts[idx + 1].toIntOrNull(16) ?: return null
            return v * 100f / 255f
        }
        return null
    }
}
