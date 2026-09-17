package com.xfan.evenginesound

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xfan.evenginesound.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var obd: BleObdManager
    private val gearbox = VirtualGearbox()

    private val deviceList = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private var connectedDevice: BluetoothDevice? = null

    private var service: AudioService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, ib: IBinder?) {
            service = (ib as AudioService.AudioBinder).getService()
            bound = true
            service?.setBlend(binding.seekBlend.progress / 100f)
            service?.setVolume(binding.seekVolume.progress / 100f)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val obdCallback = object : ObdCallback {
        override fun onScanComplete(devices: List<BluetoothDevice>, names: List<String>) {
            deviceList.clear(); deviceList.addAll(devices)
            deviceNames.clear(); deviceNames.addAll(names)
            val adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_item, deviceNames
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerDevices.adapter = adapter
            setStatus("找到 ${names.size} 个设备")
        }

        override fun onConnected() { setStatus("已连接") }
        override fun onDisconnected() { setStatus("已断开") }
        override fun onError(msg: String) { setStatus(msg) }

        override fun onData(frame: ObdFrame) {
            val state = gearbox.update(frame)
            service?.updateState(state)
            updateDataStatus(frame, state)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) obd.scan() else setStatus(getString(R.string.perm_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        obd = BleObdManager(this, obdCallback)

        binding.btnScan.setOnClickListener {
            if (hasPerms()) obd.scan() else requestPerms()
        }
        binding.btnConnect.setOnClickListener {
            val pos = binding.spinnerDevices.selectedItemPosition
            if (pos in deviceList.indices) {
                connectedDevice = deviceList[pos]
                obd.connect(connectedDevice!!)
            }
        }
        binding.btnStart.setOnClickListener {
            applyGearboxUi()
            service?.startEngine(
                binding.seekBlend.progress / 100f,
                binding.seekVolume.progress / 100f
            )
            setStatus("声浪引擎运行中")
        }
        binding.btnStop.setOnClickListener { service?.stopEngine() }

        binding.seekBlend.setOnSeekBarChangeListener(simple { service?.setBlend(it / 100f) })
        binding.seekVolume.setOnSeekBarChangeListener(simple { service?.setVolume(it / 100f) })
        binding.seekGears.setOnSeekBarChangeListener(simple {
            gearbox.gearCount = it.coerceAtLeast(1)
        })
        binding.seekShiftRpm.setOnSeekBarChangeListener(simple {
            gearbox.shiftUpRpm = it.toFloat()
        })

        // 数据源开关：与布局默认值同步，用户可随时关掉异常的数据源（自动回退，不影响声浪）
        gearbox.usePedalForLoad = binding.switchPedal.isChecked
        gearbox.useRealRpm = binding.switchRealRpm.isChecked
        binding.switchPedal.setOnCheckedChangeListener { _, checked ->
            gearbox.usePedalForLoad = checked
        }
        binding.switchRealRpm.setOnCheckedChangeListener { _, checked ->
            gearbox.useRealRpm = checked
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AudioService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(conn); bound = false }
    }

    private fun applyGearboxUi() {
        gearbox.gearCount = binding.seekGears.progress.coerceAtLeast(1)
        gearbox.shiftUpRpm = binding.seekShiftRpm.progress.toFloat()
    }

    private fun setStatus(s: String) { binding.tvStatus.text = s }

    /** 实时显示本车暴露了哪些 PID、当前实际使用的负载源/转速源（取不到时能看到回退到哪一档）。 */
    private fun updateDataStatus(frame: ObdFrame, state: EngineState) {
        fun flag(pid: String) = if (frame.available[pid] == true) "有" else "无"
        val loadSrc = when (state.loadSource) {
            "pedal" -> "踏板"
            "throttle" -> "油门"
            else -> "速度估算"
        }
        val rpmSrc = if (state.rpmSource == "real") "真实转速" else "虚拟转速"
        binding.tvDataStatus.text = buildString {
            append("数据 速度:${flag("0D")} 油门:${flag("11")} 踏板:${flag("5A")} 转速:${flag("0C")}")
            append(" | 负载源:$loadSrc 转速源:$rpmSrc")
            append(" | ${state.gear}挡 ${state.virtualRpm.toInt()}rpm")
        }
    }

    private fun hasPerms(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPerms() {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            mutableListOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ).apply {
                if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun simple(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    }
}
