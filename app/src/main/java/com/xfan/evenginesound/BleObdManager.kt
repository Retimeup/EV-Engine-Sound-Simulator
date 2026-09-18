package com.xfan.evenginesound

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

interface ObdCallback {
    fun onScanComplete(devices: List<BluetoothDevice>, names: List<String>)
    fun onConnected()
    fun onDisconnected()
    fun onData(frame: ObdFrame)
    fun onError(msg: String)

    /** 调试日志：AT 指令、原始响应、发现的 UUID 等，供 UI 展示以定位"到底卡在哪"。 */
    fun onLog(line: String)
}

/**
 * 蓝牙 ELM327 管理（BLE 为主）。
 *
 * 关键改进（相比初版）：
 *  1) **不再硬编码特征 UUID**：遍历所有服务/特征，按"可写/可通知"能力 + 已知 UUID 加权挑选。
 *     初版只认 ffe0/ffe1、fff0/fff2，很多 BLE ELM327（如 Vgate iCar 的 fff0/fff1）因此
 *     连上了却收不到任何数据 —— 这是"踩油门声浪没反应"最常见的原因。
 *  2) **原始日志**：把 AT 指令、每条原始响应、发现的 UUID 通过 onLog 抛给 UI，一眼看出
 *     是"没响应"、"NO DATA"，还是"解析失败"。
 *  3) **响应驱动轮询**：收到 '>' 提示符才发下一条，并带 800ms 超时重发；比固定间隔更快更稳。
 *  4) 所有回调 post 到主线程，避免在蓝牙线程里碰 UI 导致崩溃。
 *
 * 轮询 PID：0x0D 车速 / 0x11 油门 / 0x5A 踏板D / 0x5B 踏板E / 0x0C 转速
 */
class BleObdManager(
    private val ctx: Context,
    private val cb: ObdCallback
) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val handler = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()
    private val devices = mutableListOf<BluetoothDevice>()
    private val names = mutableListOf<String>()

    private var latestSpeed: Float? = null
    private var latestThrottle: Float? = null
    private var latestPedalD: Float? = null
    private var latestPedalE: Float? = null
    private var latestRpm: Float? = null
    private val available = mutableMapOf("0D" to false, "11" to false, "5A" to false, "5B" to false, "0C" to false)

    private var cmdIndex = 0
    private val commands = listOf("010D\r", "015A\r", "0111\r", "010C\r", "015B\r")

    /** 当前在等响应的指令；收到 '>' 后清空，由轮询器发下一条。超时则重发。 */
    private var currentCmd: String? = null
    private var lastSentAt = 0L

    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 常见 ELM327 特征 UUID，仅作加权参考（非硬性要求）。 */
    private val knownCharUuids = setOf(
        "0000ffe1-0000-1000-8000-00805f9b34fb",
        "0000fff1-0000-1000-8000-00805f9b34fb",
        "0000fff2-0000-1000-8000-00805f9b34fb",
        "0000fff4-0000-1000-8000-00805f9b34fb"
    )

    // ---------------------------------------------------------------- log
    private fun log(line: String) = handler.post { cb.onLog(line) }

    // ---------------------------------------------------------------- scan
    fun scan() {
        val a = adapter ?: run { handler.post { cb.onError("设备不支持蓝牙") }; return }
        scanner = a.bluetoothLeScanner ?: run { handler.post { cb.onError("无法启动扫描") }; return }
        devices.clear(); names.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("— 开始扫描（10s，过滤 OBD/ELM/Veepeak/OBDLink 等命名）—")
        scanner?.startScan(emptyList<ScanFilter>(), settings, scanCb)
        handler.postDelayed({ stopScan() }, 10000)
    }

    private fun stopScan() {
        scanner?.stopScan(scanCb)
        handler.removeCallbacksAndMessages(null)
        handler.post { cb.onScanComplete(ArrayList(devices), ArrayList(names)) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = d.name ?: return
            if (name.contains("OBD", true) || name.contains("ELM", true) ||
                name.contains("Veepeak", true) || name.contains("OBDLink", true) ||
                name.contains("Vgate", true) || name.contains("iCar", true)
            ) {
                if (!devices.contains(d)) { devices.add(d); names.add(name) }
            }
        }
    }

    // ---------------------------------------------------------------- connect
    fun connect(device: BluetoothDevice) {
        resetState()
        log("— 连接 ${device.name ?: device.address} —")
        gatt = device.connectGatt(ctx, false, gattCb)
    }

    fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun resetState() {
        latestSpeed = null; latestThrottle = null
        latestPedalD = null; latestPedalE = null; latestRpm = null
        available.keys.forEach { available[it] = false }
        cmdIndex = 0
        currentCmd = null
        buffer.setLength(0)
        writeChar = null; notifyChar = null
    }

    private fun propsToString(p: Int): String {
        val sb = StringBuilder()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) sb.append("R")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) sb.append("W")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) sb.append("w")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) sb.append("N")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) sb.append("I")
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接 GATT，开始发现服务…")
                g.discoverServices()
            } else {
                log("连接断开 (state=$newState, status=$status)")
                handler.post { cb.onDisconnected() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { cb.onError("服务发现失败: $status") }
                return
            }
            log("— 服务发现：共 ${g.services.size} 个服务 —")

            var writeScore = -1
            var notifyScore = -1
            for (svc in g.services) {
                log("SVC ${svc.uuid}")
                for (ch in svc.characteristics) {
                    val p = ch.properties
                    val known = knownCharUuids.contains(ch.uuid.toString().lowercase())
                    log("   CHR ${ch.uuid} [${propsToString(p)}]${if (known) " *" else ""}")

                    val canWrite = (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                            (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

                    if (canWrite) {
                        var s = 1
                        if (known) s += 10
                        if (canNotify) s += 5          // 收发同特征（HM-10 类）优先
                        if (s > writeScore) { writeScore = s; writeChar = ch }
                    }
                    if (canNotify) {
                        var s = 1
                        if (known) s += 10
                        if (canWrite) s += 5
                        if (s > notifyScore) { notifyScore = s; notifyChar = ch }
                    }
                }
            }

            if (writeChar == null) {
                handler.post { cb.onError("未找到可写特征，无法与 ELM327 通信") }
                return
            }
            log("选用写特征: ${writeChar!!.uuid}")
            log("选用通知特征: ${notifyChar?.uuid ?: "(无，可能收不到数据)"}")

            if (notifyChar != null) {
                val nc = notifyChar!!
                g.setCharacteristicNotification(nc, true)
                val useIndicate = (nc.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) &&
                        (nc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0)
                (nc.getDescriptor(cccdUuid))?.let { d ->
                    d.value = if (useIndicate)
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }

            handler.post { cb.onConnected() }

            // AT 初始化序列（ATZ 复位后需要时间，故整体延后到 300ms 再开始）
            handler.postDelayed({ sendCommand("ATZ\r") }, 300)
            handler.postDelayed({ sendCommand("ATE0\r") }, 900)   // 关回显
            handler.postDelayed({ sendCommand("ATL0\r") }, 1400)  // 关换行
            handler.postDelayed({ sendCommand("ATSP0\r") }, 1900) // 自动协议
            handler.postDelayed({
                cmdIndex = 0
                currentCmd = null
                log("— 开始轮询 PID —")
                handler.post(pollRunnable)
            }, 2600)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val s = ch.getStringValue(0) ?: return
            log("<< " + s.replace("\r", "").replace("\n", " | "))
            buffer.append(s)
            if (buffer.contains('>')) {
                val text = buffer.toString()
                buffer.setLength(0)
                parseAndDispatch(text)
                currentCmd = null   // 收到响应，允许发下一条
            }
        }
    }

    /** 响应驱动轮询：空闲则发下一条；在等响应超过 800ms 则重发。 */
    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (gatt == null || writeChar == null) return
            val now = System.currentTimeMillis()
            val cmd = currentCmd
            if (cmd == null) {
                val next = commands[cmdIndex % commands.size]
                cmdIndex++
                sendCommand(next)
                currentCmd = next
                lastSentAt = now
            } else if (now - lastSentAt > 800) {
                log("(超时重发) ${cmd.trim()}")
                sendCommand(cmd)
                lastSentAt = now
            }
            handler.postDelayed(this, 40)
        }
    }

    private fun sendCommand(cmd: String) {
        val ch = writeChar ?: return
        log(">> ${cmd.trim()}")
        ch.setValue(cmd)
        ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt?.writeCharacteristic(ch)
    }

    private fun parseAndDispatch(text: String) {
        var got = false
        ObdParser.parseSpeed(text)?.let { latestSpeed = it; available["0D"] = true; got = true }
        ObdParser.parseThrottle(text)?.let { latestThrottle = it; available["11"] = true; got = true }
        ObdParser.parsePedalD(text)?.let { latestPedalD = it; available["5A"] = true; got = true }
        ObdParser.parsePedalE(text)?.let { latestPedalE = it; available["5B"] = true; got = true }
        ObdParser.parseRpm(text)?.let {
            latestRpm = it
            if (it > 1f) { available["0C"] = true; got = true }   // 恒为 0 不算可用
        }
        if (!got && ObdParser.isNoData(text)) log("   (该车未提供此 PID)")
        else if (!got && ObdParser.isError(text)) log("   (指令错误/不支持)")

        val pedal = latestPedalD ?: latestPedalE
        val frame = ObdFrame(
            speedKmh = latestSpeed,
            throttlePct = latestThrottle,
            pedalPct = pedal,
            rpm = if (latestRpm != null && latestRpm!! > 1f) latestRpm else null,
            available = HashMap(available)
        )
        handler.post { cb.onData(frame) }
    }
}
