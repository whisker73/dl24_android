package com.dl24.monitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dl24.monitor.R
import com.dl24.monitor.ble.MeterReport
import com.dl24.monitor.databinding.FragmentDashboardBinding
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestReport.collect { report ->
                if (report != null) updateValues(report) else resetValues()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceState.collect { state ->
                if (state.outputKnown) {
                    val color = if (state.outputOn)
                        ContextCompat.getColor(requireContext(), R.color.green_on)
                    else
                        ContextCompat.getColor(requireContext(), R.color.red_off)
                    binding.outputStatusDot.setTextColor(color)
                    binding.outputStatusLabel.text = if (state.outputOn) "EIN" else "AUS"
                    binding.outputStatusLabel.setTextColor(color)
                } else {
                    binding.outputStatusDot.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                    binding.outputStatusLabel.text = "Unbekannt"
                }
            }
        }
    }

    private fun updateValues(r: MeterReport) {
        binding.valueVoltage.text = "%.2f".format(r.voltage)
        binding.valueCurrent.text = "%.3f".format(r.current)
        binding.valuePower.text = "%.2f".format(r.power)
        binding.valueEnergy.text = "%.2f".format(r.energyWh)
        binding.valueCapacity.text = "%.0f".format(r.energyAh * 1000)
        binding.valueTemperature.text = "%d".format(r.temperature)
        binding.valueDuration.text = r.durationStr
        binding.valueDeviceType.text = r.deviceTypeName
    }

    private fun resetValues() {
        listOf(
            binding.valueVoltage, binding.valueCurrent, binding.valuePower,
            binding.valueEnergy, binding.valueCapacity, binding.valueTemperature
        ).forEach { it.text = "---" }
        binding.valueDuration.text = "00:00:00"
        binding.valueDeviceType.text = "—"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
