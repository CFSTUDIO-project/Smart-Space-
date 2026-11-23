package com.cfstudio.smartspace

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.roundToInt

data class DeviceSnapshot(
    val cpuPercent: Float?,
    val gpuPercent: Float?,
    val cpuMHz: Long?,
    val gpuMHz: Long?,
    val usedRamMb: Long,
    val totalRamMb: Long,
    val batteryPercent: Int?,
    val batteryTemperatureC: Float?,
    val thermalStatus: String
)

class DeviceMonitor(private val context: Context) {
    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null

    fun sample(): DeviceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val total = memory.totalMem / (1024L * 1024L)
        val available = memory.availMem / (1024L * 1024L)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else null
        val tempTenths = battery?.getIntExtra("temperature", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryTemp = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "Not supported"

        return DeviceSnapshot(
            cpuPercent = readCpuPercent(),
            gpuPercent = readGpuPercent(),
            cpuMHz = readCpuFrequencyMHz(),
            gpuMHz = readGpuFrequencyMHz(),
            usedRamMb = (total - available).coerceAtLeast(0),
            totalRamMb = total,
            batteryPercent = percent,
            batteryTemperatureC = batteryTemp,
            thermalStatus = thermal
        )
    }

    private fun readCpuPercent(): Float? = try {
        val line = File("/proc/stat").useLines { it.firstOrNull { row -> row.startsWith("cpu ") } } ?: return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        val total = values.sum()
        val previousTotal = lastCpuTotal
        val previousIdle = lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle
        if (previousTotal == null || previousIdle == null) return null
        val totalDelta = total - previousTotal
        val idleDelta = idle - previousIdle
        if (totalDelta <= 0L) null else (((totalDelta - idleDelta).toFloat() / totalDelta) * 100f).coerceIn(0f, 100f)
    } catch (_: Exception) {
        null
    }

    private fun readGpuPercent(): Float? {
        val busyFile = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
        try {
            if (busyFile.canRead()) {
                val numbers = busyFile.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                if (numbers.size >= 2 && numbers[1] > 0L) {
                    return (numbers[0].toFloat() * 100f / numbers[1]).coerceIn(0f, 100f)
                }
            }
        } catch (_: Exception) { }
        val candidates = listOf(
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/gpu.0/utilization"
        )
        for (path in candidates) {
            try {
                val file = File(path)
                if (file.canRead()) {
                    val raw = file.readText().trim().removeSuffix("%").toFloatOrNull()
                    if (raw != null && raw in 0f..100f) return raw
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun readCpuFrequencyMHz(): Long? {
        val candidates = listOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"
        )
        for (path in candidates) readFrequency(File(path))?.let { return it }
        return null
    }

    private fun readGpuFrequencyMHz(): Long? {
        val kgsl = listOf(
            File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"),
            File("/sys/class/kgsl/kgsl-3d0/gpuclk")
        )
        for (file in kgsl) readFrequency(file)?.let { return it }
        try {
            val devfreq = File("/sys/class/devfreq")
            devfreq.listFiles()?.filter {
                val name = it.name.lowercase()
                name.contains("gpu") || name.contains("mali") || name.contains("3d")
            }?.forEach { folder ->
                readFrequency(File(folder, "cur_freq"))?.let { return it }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun readFrequency(file: File): Long? = try {
        if (!file.canRead()) return null
        val raw = file.readText().trim().toLongOrNull() ?: return null
        if (raw <= 0L) return null
        // Most cpufreq/devfreq nodes report Hz or kHz. Values below 100,000 are already MHz on some vendor nodes.
        when {
            raw >= 100_000_000L -> raw / 1_000_000L
            raw >= 100_000L -> raw / 1_000L
            else -> raw
        }
    } catch (_: Exception) {
        null
    }
}
