package com.dl24.monitor.ui

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dl24.monitor.ble.BleState
import com.dl24.monitor.databinding.DialogScanBinding
import com.dl24.monitor.databinding.ItemDeviceBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class ScanDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogScanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = DeviceAdapter { device ->
            viewModel.connect(device)
            dismiss()
        }

        binding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDevices.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scannedDevices.collect { devices ->
                adapter.submitList(devices)
                binding.emptyText.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bleState.collect { state ->
                binding.progressBar.visibility = if (state is BleState.Scanning) View.VISIBLE else View.GONE
                binding.scanStatusText.text = when (state) {
                    is BleState.Scanning -> "Suche läuft…"
                    is BleState.Connected -> { dismiss(); "" }
                    else -> "Suche beendet"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class DeviceAdapter(
    private val onClick: (BluetoothDevice) -> Unit
) : ListAdapter<BluetoothDevice, DeviceAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        @Suppress("MissingPermission")
        fun bind(device: BluetoothDevice) {
            b.deviceName.text = device.name ?: "Unbekannt"
            b.deviceAddress.text = device.address
            b.root.setOnClickListener { onClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BluetoothDevice>() {
            override fun areItemsTheSame(a: BluetoothDevice, b: BluetoothDevice) = a.address == b.address
            override fun areContentsTheSame(a: BluetoothDevice, b: BluetoothDevice) = a.address == b.address
        }
    }
}
