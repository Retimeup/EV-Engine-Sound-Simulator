package com.xfan.evenginesound

/**
 * 解析 ELM327 返回的 OBD-II PID 文本。
 *
 * 设计要点（健壮性）：
 *  - 先把响应压成"紧凑十六进制串"：去掉 '>'、所有空白、统一大写。
 *    这样无论车/适配器是否回显命令、是否带 header、是否带空格，都不影响解析。
 *  - 再在紧凑串里定位 "41" + PID，取其后字节。
 *    例：回显 "010D" + 正常 "41 0D 5C" → 紧凑 "010D410D5C" → 命中 "410D" → 数据 5C。
 *    例：带 header "7E8 06 41 0D 5C"   → 紧凑 "7E806410D5C" → 命中 "410D" → 数据 5C。
 *    例："NO DATA" / "?" / "SEARCHING..." → 定位不到 → 返回 null（UI 显示 NA）。
 */
object ObdParser {

    /** 去掉 '>' 与所有空白，统一大写，得到紧凑十六进制串。 */
    fun compact(s: String): String = s
        .replace(">", "")
        .replace(Regex("\\s+"), "")
        .uppercase()

    /** 在紧凑串中定位 "41"+PID(如 "0D")，返回其后的数据字节列表；找不到返回 null。 */
    private fun dataAfter(raw: String, pid: String): List<Int>? {
        val c = compact(raw)
        val key = "41$pid"
        val idx = c.indexOf(key)
        if (idx < 0) return null
        val rest = c.substring(idx + key.length)
        val bytes = ArrayList<Int>(4)
        var i = 0
        while (i + 1 < rest.length) {
            val b = rest.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(b)
            i += 2
        }
        return bytes.ifEmpty { null }
    }

    /** PID 0x0D 车速，单位 km/h（A 字节即车速） */
    fun parseSpeed(response: String): Float? =
        dataAfter(response, "0D")?.getOrNull(0)?.toFloat()

    /** PID 0x11 油门位置，百分比 0-100（A*100/255） */
    fun parseThrottle(response: String): Float? =
        dataAfter(response, "11")?.getOrNull(0)?.let { it * 100f / 255f }

    /** PID 0x0C 发动机/电机转速（(A*256+B)/4）。多数电车为 0 / NO DATA。 */
    fun parseRpm(response: String): Float? =
        dataAfter(response, "0C")?.let {
            if (it.size < 2) null else (it[0] * 256 + it[1]) / 4f
        }

    /** PID 0x5A 加速踏板位置 D（A*100/255）。EV 上通常比 0x11 更直接。 */
    fun parsePedalD(response: String): Float? =
        dataAfter(response, "5A")?.getOrNull(0)?.let { it * 100f / 255f }

    /** PID 0x5B 加速踏板位置 E（A*100/255）。冗余踏板信号。 */
    fun parsePedalE(response: String): Float? =
        dataAfter(response, "5B")?.getOrNull(0)?.let { it * 100f / 255f }

    /** 判断响应是否为"明确无此数据"（便于 UI 区分 NO DATA 与 完全无响应）。 */
    fun isNoData(response: String): Boolean {
        val c = compact(response)
        return c.contains("NODATA") || c.contains("UNABLETOCONNECT")
    }

    /** 判断响应是否为 OBD 层的错误应答（'?' / '7F...'）。 */
    fun isError(response: String): Boolean {
        val c = compact(response)
        return c == "?" || c.startsWith("7F") || c.contains("ERROR") || c.contains("STOPPED")
    }
}
