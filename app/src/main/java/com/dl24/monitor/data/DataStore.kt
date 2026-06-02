package com.dl24.monitor.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dl24.monitor.ble.MeterReport
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class DataSample(
    val relativeTimeSec: Double,
    val voltage: Double,
    val current: Double,
    val power: Double,
    val energyWh: Double,
    val energyAh: Double,
    val temperature: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
)

class DataStore(private val maxSamples: Int = 7200) {
    private val samples = ArrayDeque<DataSample>(maxSamples)
    private var startTimeMs: Long? = null

    val sampleCount: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()

    fun addSample(report: MeterReport) {
        val now = System.currentTimeMillis()
        if (startTimeMs == null) startTimeMs = now
        val rel = (now - startTimeMs!!) / 1000.0

        if (samples.size >= maxSamples) samples.pollFirst()
        samples.addLast(DataSample(
            relativeTimeSec = rel,
            voltage = report.voltage,
            current = report.current,
            power = report.power,
            energyWh = report.energyWh,
            energyAh = report.energyAh,
            temperature = report.temperature,
            hours = report.hours,
            minutes = report.minutes,
            seconds = report.seconds,
        ))
    }

    fun getAll(): List<DataSample> = samples.toList()

    fun getLast(n: Int): List<DataSample> {
        val list = samples.toList()
        return if (list.size <= n) list else list.subList(list.size - n, list.size)
    }

    fun clear() {
        samples.clear()
        startTimeMs = null
    }

    fun exportCsv(context: Context): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "dl24_$timestamp.csv"
        val csvContent = buildCsvString()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, fileName, csvContent)
        } else {
            exportToLegacyStorage(fileName, csvContent)
        }
    }

    private fun buildCsvString(): String = buildString {
        appendLine("Zeit (s);Spannung (V);Strom (A);Leistung (W);Energie (Wh);Kapazität (Ah);Temperatur (°C);Dauer")
        for (s in samples) {
            appendLine("%.1f;%.2f;%.3f;%.2f;%.2f;%.3f;%d;%02d:%02d:%02d".format(
                s.relativeTimeSec, s.voltage, s.current, s.power,
                s.energyWh, s.energyAh, s.temperature,
                s.hours, s.minutes, s.seconds
            ))
        }
    }

    private fun exportViaMediaStore(context: Context, fileName: String, content: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun exportToLegacyStorage(fileName: String, content: String): Uri? {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            FileWriter(file).use { it.write(content) }
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }
}
