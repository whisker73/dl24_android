package com.dl24.monitor.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dl24.monitor.databinding.FragmentChartsBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch

class ChartsFragment : Fragment() {

    private var _binding: FragmentChartsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val COLOR_VOLTAGE = Color.parseColor("#60a5fa")
    private val COLOR_CURRENT = Color.parseColor("#34d399")
    private val COLOR_POWER = Color.parseColor("#f59e0b")
    private val COLOR_TEMP = Color.parseColor("#f87171")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChartsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart(binding.chartVoltage, "Spannung [V]", COLOR_VOLTAGE)
        setupChart(binding.chartCurrent, "Strom [A]", COLOR_CURRENT)
        setupChart(binding.chartPower, "Leistung [W]", COLOR_POWER)
        setupChart(binding.chartTemperature, "Temperatur [°C]", COLOR_TEMP)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestReport.collect { report ->
                if (report != null) refreshCharts()
            }
        }
    }

    private fun setupChart(chart: LineChart, label: String, color: Int) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.parseColor("#1e1e2e"))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#6c7086")
                gridColor = Color.parseColor("#313244")
                axisLineColor = Color.parseColor("#313244")
            }
            axisLeft.apply {
                textColor = color
                gridColor = Color.parseColor("#313244")
                axisLineColor = Color.parseColor("#313244")
            }
            axisRight.isEnabled = false
            legend.isEnabled = false
        }
    }

    private fun refreshCharts() {
        val samples = viewModel.dataStore.getAll()
        if (samples.isEmpty()) return

        fun makeSet(values: List<Entry>, color: Int): LineDataSet = LineDataSet(values, "").apply {
            this.color = color
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.LINEAR
        }

        val voltageEntries = samples.map { Entry(it.relativeTimeSec.toFloat(), it.voltage.toFloat()) }
        val currentEntries = samples.map { Entry(it.relativeTimeSec.toFloat(), it.current.toFloat()) }
        val powerEntries = samples.map { Entry(it.relativeTimeSec.toFloat(), it.power.toFloat()) }
        val tempEntries = samples.map { Entry(it.relativeTimeSec.toFloat(), it.temperature.toFloat()) }

        binding.chartVoltage.data = LineData(makeSet(voltageEntries, COLOR_VOLTAGE))
        binding.chartCurrent.data = LineData(makeSet(currentEntries, COLOR_CURRENT))
        binding.chartPower.data = LineData(makeSet(powerEntries, COLOR_POWER))
        binding.chartTemperature.data = LineData(makeSet(tempEntries, COLOR_TEMP))

        binding.chartVoltage.notifyDataSetChanged(); binding.chartVoltage.invalidate()
        binding.chartCurrent.notifyDataSetChanged(); binding.chartCurrent.invalidate()
        binding.chartPower.notifyDataSetChanged(); binding.chartPower.invalidate()
        binding.chartTemperature.notifyDataSetChanged(); binding.chartTemperature.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
