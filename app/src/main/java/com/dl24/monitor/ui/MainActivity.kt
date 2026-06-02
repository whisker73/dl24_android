package com.dl24.monitor.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dl24.monitor.R
import com.dl24.monitor.ble.BleState
import com.dl24.monitor.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* handled by state observer */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
        else Toast.makeText(this, "BLE Berechtigung verweigert", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupViewPager()
        observeState()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> getString(R.string.tab_dashboard)
                1 -> getString(R.string.tab_charts)
                2 -> getString(R.string.tab_controls)
                else -> ""
            }
        }.attach()
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.bleState.collect { state ->
                invalidateOptionsMenu()
                binding.statusText.text = when (state) {
                    is BleState.Disconnected -> getString(R.string.status_disconnected)
                    is BleState.Scanning -> getString(R.string.status_scanning)
                    is BleState.Connecting -> getString(R.string.status_connecting, state.address)
                    is BleState.Connected -> getString(R.string.status_connected, state.address)
                    is BleState.Error -> state.message
                }
            }
        }
        lifecycleScope.launch {
            viewModel.scannedDevices.collect { devices ->
                if (viewModel.bleState.value is BleState.Scanning && devices.isNotEmpty()) {
                    // Update dialog if open
                }
            }
        }
        lifecycleScope.launch {
            viewModel.exportResult.collect { msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val connected = viewModel.bleState.value is BleState.Connected
        val scanning = viewModel.bleState.value is BleState.Scanning
        menu.findItem(R.id.action_scan)?.isVisible = !connected && !scanning
        menu.findItem(R.id.action_disconnect)?.isVisible = connected
        menu.findItem(R.id.action_refresh_state)?.isVisible = connected
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> { requestBleAndScan(); true }
            R.id.action_disconnect -> { viewModel.disconnect(); true }
            R.id.action_refresh_state -> { viewModel.queryDeviceState(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestBleAndScan() {
        val adapter = viewModel.bleManager.bluetoothAdapter
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startScan() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startScan() {
        viewModel.startScan()
        showScanDialog()
    }

    private fun showScanDialog() {
        val dialog = ScanDialogFragment()
        dialog.show(supportFragmentManager, "scan")
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
