package com.dl24.monitor.ble

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private const val TAG = "DL24BleManager"

sealed class BleState {
    object Disconnected : BleState()
    object Scanning : BleState()
    data class Connecting(val address: String) : BleState()
    data class Connected(val address: String, val deviceType: Int = BleConstants.DEVICE_DC) : BleState()
    data class Error(val message: String) : BleState()
}

class BleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter get() = btManager.adapter

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _reports = MutableSharedFlow<MeterReport>(extraBufferCapacity = 64)
    val reports: SharedFlow<MeterReport> = _reports.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private val parser = ProtocolParser()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var deviceType = BleConstants.DEVICE_DC

    // PX100 query: intercepts one notification and delivers it here
    private val px100Channel = Channel<ByteArray?>(Channel.CONFLATED)
    private var px100Pending = false

    // ──────────────── Scanning ────────────────

    @Suppress("MissingPermission")
    fun startScan(durationMs: Long = 5_000) {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            _state.value = BleState.Error("BLE Scanner nicht verfügbar")
            return
        }
        _scannedDevices.value = emptyList()
        _state.value = BleState.Scanning

        val found = mutableListOf<BluetoothDevice>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try { device.name } catch (_: SecurityException) { null } ?: return
                if (BleConstants.NAME_FILTERS.none { name.contains(it, ignoreCase = true) }) return
                if (found.none { it.address == device.address }) {
                    found.add(device)
                    _scannedDevices.value = found.toList()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = BleState.Error("Scan fehlgeschlagen (Code $errorCode)")
            }
        }

        scanner.startScan(null, settings, callback)
        scope.launch {
            delay(durationMs)
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            if (_state.value is BleState.Scanning) _state.value = BleState.Disconnected
        }
    }

    // ──────────────── Connection ────────────────

    @Suppress("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        _state.value = BleState.Connecting(device.address)
        parser.reset()

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected, discovering services…")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected (status=$status)")
                        cleanup()
                    }
                }
            }

            @Suppress("MissingPermission")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.value = BleState.Error("Service-Suche fehlgeschlagen")
                    return
                }
                val service = g.getService(BleConstants.SERVICE_UUID)
                val char = service?.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)
                if (char == null) {
                    _state.value = BleState.Error("DL24 Service/Characteristic nicht gefunden")
                    return
                }
                writeChar = char
                gatt = g
                enableNotifications(g, char)
            }

            // API < 33
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    handleData(c.value)
                }
            }

            // API >= 33
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                handleData(value)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "onCharacteristicWrite failed status=$status")
                else Log.d(TAG, "Write OK: ${c.uuid}")
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Notifications aktiv")
                    _state.value = BleState.Connected(device.address, deviceType)
                }
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, cb)
        }
    }

    @Suppress("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(BleConstants.CLIENT_CHAR_CONFIG_UUID)
        if (descriptor == null) {
            // Some devices omit the descriptor — try anyway
            _state.value = BleState.Connected(g.device.address, deviceType)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    // ──────────────── Data handling ────────────────

    private fun handleData(data: ByteArray) {
        // PX100 reply: CA CB d1 d2 d3 CE CF
        if (px100Pending && data.size >= 7
            && data[0] == 0xCA.toByte() && data[1] == 0xCB.toByte()
            && data[data.size - 2] == 0xCE.toByte() && data[data.size - 1] == 0xCF.toByte()
        ) {
            px100Pending = false
            px100Channel.trySend(data.copyOfRange(2, 5))
            return
        }
        if (px100Pending && data.size == 1 && data[0] == 0x6F.toByte()) {
            px100Pending = false
            px100Channel.trySend(null)
            return
        }

        for (msg in parser.feed(data)) {
            when (msg) {
                is ParsedMessage.Report -> {
                    if (msg.report.deviceType != 0) {
                        deviceType = msg.report.deviceType
                        val cur = _state.value
                        if (cur is BleState.Connected) _state.value = cur.copy(deviceType = deviceType)
                    }
                    scope.launch { _reports.emit(msg.report) }
                }
                is ParsedMessage.Reply -> Log.d(TAG, "Reply: ok=${msg.reply.ok}")
            }
        }
    }

    // ──────────────── Commands ────────────────

    @Suppress("MissingPermission")
    fun sendCommand(bytes: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "writeCharacteristic status=$status")
        } else {
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    fun outputOn() = sendCommand(CommandBuilder.directOutput(true))
    fun outputOff() = sendCommand(CommandBuilder.directOutput(false))
    fun setCurrent(amps: Double) = sendCommand(CommandBuilder.directCurrent(amps))
    fun setVoltageCutoff(volts: Double) = sendCommand(CommandBuilder.directVoltageCutoff(volts))
    fun setTimer(seconds: Int) = sendCommand(CommandBuilder.directTimer(seconds))
    fun resetWh() = sendCommand(CommandBuilder.resetWh(deviceType))
    fun resetAh() = sendCommand(CommandBuilder.resetAh(deviceType))
    fun resetDuration() = sendCommand(CommandBuilder.resetDuration(deviceType))
    fun resetAll() = sendCommand(CommandBuilder.resetAll(deviceType))

    // ──────────────── PX100 State Query ────────────────

    suspend fun queryOutputState(): Boolean? {
        if (gatt == null) return null
        val raw = px100Query(BleConstants.PX100_QUERY_OUTPUT_STATE) ?: return null
        return raw[2] == 0x01.toByte()
    }

    suspend fun queryDeviceState(): DeviceState {
        if (gatt == null) return DeviceState()

        val rawOutput = px100Query(BleConstants.PX100_QUERY_OUTPUT_STATE)
        val rawCurrent = px100Query(BleConstants.PX100_QUERY_PRESET_CURRENT)
        val rawCutoff = px100Query(BleConstants.PX100_QUERY_PRESET_CUTOFF)
        val rawTimer = px100Query(BleConstants.PX100_QUERY_PRESET_TIMER)

        return DeviceState(
            outputOn = rawOutput?.let { it[2] == 0x01.toByte() } ?: false,
            outputKnown = rawOutput != null,
            presetCurrentA = rawCurrent?.let { (readU24(it, 0) * 10) / 1000.0 } ?: 0.0,
            presetCurrentKnown = rawCurrent != null,
            presetCutoffV = rawCutoff?.let { (readU24(it, 0) * 10) / 1000.0 } ?: 0.0,
            presetCutoffKnown = rawCutoff != null,
            // Timer returns hh:mm:ss in d1,d2,d3
            presetTimerSecs = rawTimer?.let {
                (it[0].toInt() and 0xFF) * 3600 + (it[1].toInt() and 0xFF) * 60 + (it[2].toInt() and 0xFF)
            } ?: 0,
            presetTimerKnown = rawTimer != null,
        )
    }

    private suspend fun px100Query(cmd: Int): ByteArray? {
        px100Pending = true
        sendCommand(CommandBuilder.buildPx100Query(cmd))
        return withTimeoutOrNull(2_000) { px100Channel.receive() }
    }

    private fun readU24(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)

    // ──────────────── Cleanup ────────────────

    @Suppress("MissingPermission")
    fun disconnect() {
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        cleanup()
    }

    private fun cleanup() {
        gatt = null
        writeChar = null
        px100Pending = false
        _state.value = BleState.Disconnected
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
