package com.dl24.monitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dl24.monitor.R
import com.dl24.monitor.ble.BleState
import com.dl24.monitor.databinding.FragmentControlsBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ControlsFragment : Fragment() {

    private var _binding: FragmentControlsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    // LiPo cell presets: Pair(cutoffV, currentA)
    private val lipoProfiles = listOf(
        "— Preset wählen —" to null,
        "1S LiPo  3.0 V / 0.5 A" to Pair(3.0, 0.5),
        "2S LiPo  6.0 V / 1.0 A" to Pair(6.0, 1.0),
        "3S LiPo  9.0 V / 1.5 A" to Pair(9.0, 1.5),
        "4S LiPo  12.0 V / 2.0 A" to Pair(12.0, 2.0),
        "5S LiPo  15.0 V / 2.5 A" to Pair(15.0, 2.5),
        "6S LiPo  18.0 V / 3.0 A" to Pair(18.0, 3.0),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCurrentSlider()
        setupCutoffSlider()
        setupLipoSpinner()
        setupButtons()
        observeConnection()
        observeDeviceState()
    }

    private fun setupCurrentSlider() {
        // Range 0–2000 = 0.00–20.00 A (step 10 mA)
        binding.seekbarCurrent.max = 2000
        binding.seekbarCurrent.progress = 100
        binding.labelCurrentValue.text = "1.00 A"

        binding.seekbarCurrent.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.labelCurrentValue.text = "%.2f A".format(progress / 100.0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                viewModel.setCurrent(sb.progress / 100.0)
            }
        })
    }

    private fun setupCutoffSlider() {
        // Range 0–2000 = 0.0–200.0 V (step 0.1 V)
        binding.seekbarCutoff.max = 2000
        binding.seekbarCutoff.progress = 30
        binding.labelCutoffValue.text = "3.0 V"

        binding.seekbarCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.labelCutoffValue.text = "%.1f V".format(progress / 10.0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                viewModel.setVoltageCutoff(sb.progress / 10.0)
            }
        })
    }

    private fun setupLipoSpinner() {
        val names = lipoProfiles.map { it.first }
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLipoProfile.adapter = adapter
        binding.spinnerLipoProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val preset = lipoProfiles[pos].second ?: return
                val (cutoff, current) = preset
                binding.seekbarCutoff.progress = (cutoff * 10).roundToInt()
                binding.seekbarCurrent.progress = (current * 100).roundToInt()
                binding.labelCutoffValue.text = "%.1f V".format(cutoff)
                binding.labelCurrentValue.text = "%.2f A".format(current)
                viewModel.setVoltageCutoff(cutoff)
                viewModel.setCurrent(current)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun setupButtons() {
        binding.btnOutputOn.setOnClickListener { viewModel.outputOn() }
        binding.btnOutputOff.setOnClickListener { viewModel.outputOff() }

        binding.btnResetWh.setOnClickListener { viewModel.resetWh() }
        binding.btnResetAh.setOnClickListener { viewModel.resetAh() }
        binding.btnResetDuration.setOnClickListener { viewModel.resetDuration() }
        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_reset_title)
                .setMessage(R.string.confirm_reset_message)
                .setPositiveButton(R.string.yes) { _, _ -> viewModel.resetAll() }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        binding.btnTimerApply.setOnClickListener {
            val seconds = binding.editTimerSeconds.text.toString().toIntOrNull() ?: 0
            viewModel.setTimer(seconds.coerceIn(0, 65535))
        }

        binding.btnExportCsv.setOnClickListener { viewModel.exportCsv() }
        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_clear_title)
                .setMessage(R.string.confirm_clear_message)
                .setPositiveButton(R.string.yes) { _, _ -> viewModel.clearData() }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

    private fun observeConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bleState.collect { state ->
                val connected = state is BleState.Connected
                setControlsEnabled(connected)
            }
        }
    }

    private fun observeDeviceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceState.collect { state ->
                if (state.presetCurrentKnown) {
                    binding.seekbarCurrent.progress = (state.presetCurrentA * 100).roundToInt()
                }
                if (state.presetCutoffKnown) {
                    binding.seekbarCutoff.progress = (state.presetCutoffV * 10).roundToInt()
                }
                val parts = mutableListOf<String>()
                if (state.presetCurrentKnown) parts += "I=%.2f A".format(state.presetCurrentA)
                if (state.presetCutoffKnown) parts += "Cutoff=%.1f V".format(state.presetCutoffV)
                binding.labelDevicePreset.text = if (parts.isEmpty()) "Gerät: keine Antwort"
                    else "Gerät: ${parts.joinToString("  |  ")}"
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            binding.btnOutputOn, binding.btnOutputOff,
            binding.seekbarCurrent, binding.seekbarCutoff,
            binding.spinnerLipoProfile, binding.btnResetWh,
            binding.btnResetAh, binding.btnResetDuration, binding.btnResetAll,
            binding.btnTimerApply, binding.editTimerSeconds,
        ).forEach { it.isEnabled = enabled }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
