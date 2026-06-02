package com.dl24.monitor.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dl24.monitor.ble.BleManager
import com.dl24.monitor.ble.BleState
import com.dl24.monitor.ble.DeviceState
import com.dl24.monitor.ble.MeterReport
import com.dl24.monitor.data.DataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app.applicationContext)
    val dataStore = DataStore()

    val bleState: StateFlow<BleState> = bleManager.state
    val scannedDevices: StateFlow<List<BluetoothDevice>> = bleManager.scannedDevices

    private val _latestReport = MutableStateFlow<MeterReport?>(null)
    val latestReport: StateFlow<MeterReport?> = _latestReport.asStateFlow()

    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _exportResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exportResult: SharedFlow<String> = _exportResult.asSharedFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            bleManager.reports.collect { report ->
                _latestReport.value = report
                dataStore.addSample(report)
            }
        }
        viewModelScope.launch {
            bleManager.state.collect { state ->
                if (state is BleState.Connected) {
                    queryDeviceState()
                    startOutputPolling()
                } else {
                    pollJob?.cancel()
                }
            }
        }
    }

    private fun startOutputPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (bleState.value !is BleState.Connected) break
                val on = bleManager.queryOutputState()
                if (on != null) _deviceState.value = _deviceState.value.copy(outputOn = on, outputKnown = true)
            }
        }
    }

    fun startScan() = bleManager.startScan()

    fun connect(device: BluetoothDevice) = bleManager.connect(device)

    fun disconnect() = bleManager.disconnect()

    fun queryDeviceState() {
        viewModelScope.launch {
            _deviceState.value = bleManager.queryDeviceState()
        }
    }

    fun outputOn() = bleManager.outputOn()
    fun outputOff() = bleManager.outputOff()

    fun outputOnAndRefresh() {
        viewModelScope.launch {
            bleManager.outputOn()
            kotlinx.coroutines.delay(400)
            val on = bleManager.queryOutputState()
            if (on != null) _deviceState.value = _deviceState.value.copy(outputOn = on, outputKnown = true)
        }
    }

    fun outputOffAndRefresh() {
        viewModelScope.launch {
            bleManager.outputOff()
            kotlinx.coroutines.delay(400)
            val on = bleManager.queryOutputState()
            if (on != null) _deviceState.value = _deviceState.value.copy(outputOn = on, outputKnown = true)
        }
    }
    fun setCurrent(amps: Double) = bleManager.setCurrent(amps)
    fun setVoltageCutoff(volts: Double) = bleManager.setVoltageCutoff(volts)

    fun setLipoProfile(cutoff: Double, current: Double) {
        viewModelScope.launch {
            bleManager.setVoltageCutoff(cutoff)
            kotlinx.coroutines.delay(200)
            bleManager.setCurrent(current)
            kotlinx.coroutines.delay(300)
            _deviceState.value = bleManager.queryDeviceState()
        }
    }

    fun setTimer(seconds: Int) = bleManager.setTimer(seconds)

    fun setTimerAndRefresh(seconds: Int) {
        viewModelScope.launch {
            bleManager.setTimer(seconds)
            kotlinx.coroutines.delay(300)
            _deviceState.value = bleManager.queryDeviceState()
        }
    }
    fun resetWh() = bleManager.resetWh()
    fun resetAh() = bleManager.resetAh()
    fun resetDuration() = bleManager.resetDuration()
    fun resetAll() = bleManager.resetAll()

    fun clearData() = dataStore.clear()

    fun exportCsv() {
        viewModelScope.launch {
            val uri = dataStore.exportCsv(getApplication())
            _exportResult.emit(
                if (uri != null) "Exportiert: $uri"
                else "Export fehlgeschlagen"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.release()
    }
}
