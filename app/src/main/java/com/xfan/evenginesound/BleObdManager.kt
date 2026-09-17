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
}

/**
 * 蓝牙 ELM327 管理（BLE 为主，兼容常见 ELM327 的 UUID；经典 SPP 兜底见 README）。
 *
 * 轮询的 PID：
 *   0x0D 车速       —— 几乎所有车都有，是虚拟转速的基座
 *   0x11 油门位置   —— 部分 EV 有，作为负载回退源
 *   0x5A 加速踏板D  —— EV 上通常比油门更直接（无节气门），优先负载源
 *   0x5B 加速踏板E  —— 冗余踏板信号（部分车）
 *   0x0C 转速       —— 多数 EV 为 0/NO DATA，若暴露则用于真实转速融合
 *
 * available 标记每个 PID 是否在本次连接中成功解析过，供 UI 显示与变速箱回退判断。
 * 注意：不同 ELM327 蓝牙版的 GATT 特征 UUID 不统一，这里尝试最常见的两组。
 * 若连上后收不到数据，多半是 UUID 不匹配——在 onServicesDiscovered 里打印 svc.uuid 即可定位。
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
    private val commands = listOf("010D\r", "0111\r", "015A\r", "015B\r", "010C\r")

    private val svcUuids = listOf(
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    )
    private val charUuids = listOf(
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    )
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun scan() {
        val a = adapter ?: run { cb.onError("设备不支持蓝牙"); return }
        scanner = a.bluetoothLeScanner ?: run { cb.onError("无法启动扫描"); return }
        devices.clear(); names.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(emptyList<ScanFilter>(), settings, scanCb)
        handler.postDelayed({ stopScan() }, 10000)
    }

    private fun stopScan() {
        scanner?.stopScan(scanCb)
        handler.removeCallbacksAndMessages(null)
        cb.onScanComplete(ArrayList(devices), ArrayList(names))
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = d.name ?: return
            if (name.contains("OBD", true) || name.contains("ELM", true) ||
                name.contains("Veepeak", true) || name.contains("OBDLink", true)
            ) {
                if (!devices.contains(d)) {
                    devices.add(d); names.add(name)
                }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        resetState()
        gatt = device.connectGatt(ctx, false, gattCb)
    }

    fun disconnect() {
        handler.removeCallbacks(pollRunnable)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun resetState() {
        latestSpeed = null; latestThrottle = null
        latestPedalD = null; latestPedalE = null; latestRpm = null
        available["0D"] = false; available["11"] = false
        available["5A"] = false; available["5B"] = false; available["0C"] = false
        cmdIndex = 0
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                handler.post { cb.onDisconnected() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (svc in g.services) {
                if (!svcUuids.contains(svc.uuid)) continue
                for (ch in svc.characteristics) {
                    if (charUuids.contains(ch.uuid)) {
                        val props = ch.properties
                        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                        ) {
                            writeChar = ch
                        }
                        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            notifyChar = ch
                        }
                    }
                }
            }
            if (writeChar == null) {
                cb.onError("未找到 ELM327 写入特征，可能需调整 UUID")
                return
            }
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val desc = notifyChar!!.getDescriptor(cccdUuid)
                desc?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            }
            cb.onConnected()
            sendCommand("ATZ\r")
            handler.postDelayed({ sendCommand("ATE0\r") }, 500)
            handler.postDelayed({ sendCommand("ATL0\r") }, 1000)
            handler.postDelayed({ sendCommand("ATSP0\r") }, 1500)
            handler.postDelayed({ handler.post(pollRunnable) }, 2000)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val s = ch.getStringValue(0) ?: return
            buffer.append(s)
            if (buffer.contains('>')) {
                val text = buffer.toString()
                buffer.setLength(0)
                parseAndDispatch(text)
            }
        }
    }

    private val pollRunnable = Runnable {
        if (gatt == null || writeChar == null) return@Runnable
        sendCommand(commands[cmdIndex % commands.size])
        cmdIndex++
        handler.postDelayed(pollRunnable, 220)
    }

    private fun sendCommand(cmd: String) {
        val ch = writeChar ?: return
        ch.setValue(cmd)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
    }

    private fun parseAndDispatch(text: String) {
        ObdParser.parseSpeed(text)?.let { latestSpeed = it; available["0D"] = true }
        ObdParser.parseThrottle(text)?.let { latestThrottle = it; available["11"] = true }
        ObdParser.parsePedalD(text)?.let { latestPedalD = it; available["5A"] = true }
        ObdParser.parsePedalE(text)?.let { latestPedalE = it; available["5B"] = true }
        ObdParser.parseRpm(text)?.let {
            latestRpm = it
            if (it > 1f) available["0C"] = true  // 仅当转速有效(>1)才视为可用，避免恒为0被误判
        }

        // 踏板优先取 D，缺失时取 E
        val pedal = latestPedalD ?: latestPedalE
        val frame = ObdFrame(
            speedKmh = latestSpeed,
            throttlePct = latestThrottle,
            pedalPct = pedal,
            rpm = if (latestRpm != null && latestRpm!! > 1f) latestRpm else null,
            available = HashMap(available)
        )
        cb.onData(frame)
    }
}
