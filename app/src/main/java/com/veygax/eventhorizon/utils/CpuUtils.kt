package com.veygax.eventhorizon.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Data class to hold all the monitor info together
data class CpuMonitorInfo(
    val tempCelsius: Int = 0,
    val littleCoreMinFreqMhz: Int = 0,
    val littleCoreMaxFreqMhz: Int = 0,
    val bigCoreMinFreqMhz: Int = 0,
    val bigCoreMaxFreqMhz: Int = 0,
    val littleCoreUsagePercent: Int = 0,
    val bigCoreUsagePercent: Int = 0
)

object CpuUtils {
    const val SCRIPT_NAME = "min_freq_lock.sh"
    private const val TAG = "CpuUtils"

    // Cores 0-3 are LITTLE, Cores 4-6 are big/Prime
    private val LITTLE_CORE_PATHS = (0..3).map { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_min_freq" }
    private val BIG_CORE_PATHS = (4..6).map { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_min_freq" }
    
    const val DEFAULT_LITTLE_FREQ = "691200"
    const val DEFAULT_BIG_FREQ = "691200"

    // --- CPU Usage Calculation Properties ---
    private data class CoreStat(val idle: Long, val total: Long)
    private var previousCoreStats: Map<String, CoreStat>? = null

    // --- Function to get all monitoring data at once ---
    suspend fun getCpuMonitorInfo(): CpuMonitorInfo {
        // Temperature: Find the correct CPU thermal zone dynamically
        val tempCelsius = findCpuTemperature()

        // Frequencies
        val littleMinRaw = RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq").trim()
        val littleMaxRaw = RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq").trim()
        val bigMinRaw = RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq").trim()
        val bigMaxRaw = RootUtils.runAsRoot("cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq").trim()

        // Usage
        val usageMap = getCpuUsage()
        val littleUsage = (0..3).mapNotNull { usageMap["cpu$it"] }.average().toInt()
        val bigUsage = (4..6).mapNotNull { usageMap["cpu$it"] }.average().toInt()

        return CpuMonitorInfo(
            tempCelsius = tempCelsius,
            littleCoreMinFreqMhz = littleMinRaw.toIntOrNull()?.div(1000) ?: 0,
            littleCoreMaxFreqMhz = littleMaxRaw.toIntOrNull()?.div(1000) ?: 0,
            bigCoreMinFreqMhz = bigMinRaw.toIntOrNull()?.div(1000) ?: 0,
            bigCoreMaxFreqMhz = bigMaxRaw.toIntOrNull()?.div(1000) ?: 0,
            littleCoreUsagePercent = littleUsage,
            bigCoreUsagePercent = bigUsage
        )
    }
    
    private suspend fun findCpuTemperature(): Int {
        for (i in 0..19) {
            val type = RootUtils.runAsRoot("cat /sys/class/thermal/thermal_zone$i/type").trim()
            if (type.contains("cpu", ignoreCase = true)) {
                val tempRaw = RootUtils.runAsRoot("cat /sys/class/thermal/thermal_zone$i/temp").trim()
                return tempRaw.toIntOrNull()?.div(1000) ?: 0
            }
        }
        return 0
    }

    private suspend fun getCpuUsage(): Map<String, Int> = withContext(Dispatchers.IO) {
        val currentStats = mutableMapOf<String, CoreStat>()
        val usageMap = mutableMapOf<String, Int>()

        try {
            val statLines = RootUtils.runAsRoot("cat /proc/stat").lines()
            for (line in statLines) {
                if (line.startsWith("cpu") && line.firstOrNull { it.isDigit() } != null) {
                    val parts = line.split(" ").filter { it.isNotEmpty() }
                    val cpuName = parts[0]
                    val user = parts[1].toLong()
                    val nice = parts[2].toLong()
                    val system = parts[3].toLong()
                    val idle = parts[4].toLong()
                    val iowait = parts[5].toLong()
                    val irq = parts[6].toLong()
                    val softirq = parts[7].toLong()

                    val totalTime = user + nice + system + idle + iowait + irq + softirq
                    currentStats[cpuName] = CoreStat(idle, totalTime)
                }
            }

            previousCoreStats?.let { prevStats ->
                for (cpuName in currentStats.keys) {
                    val current = currentStats[cpuName]
                    val prev = prevStats[cpuName]
                    if (current != null && prev != null) {
                        val totalDiff = current.total - prev.total
                        val idleDiff = current.idle - prev.idle
                        val usage = if (totalDiff > 0) {
                            100 * (totalDiff - idleDiff) / totalDiff
                        } else {
                            0
                        }
                        usageMap[cpuName] = usage.toInt()
                    }
                }
            }
            previousCoreStats = currentStats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate CPU usage", e)
        }
        usageMap
    }

    private const val LITTLE_GOV_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
    private const val BIG_GOV_PATH = "/sys/devices/system/cpu/cpu4/cpufreq/scaling_governor"

    suspend fun getGovernor(): Pair<String, String> = withContext(Dispatchers.IO) {
        val littleGov = RootUtils.runAsRoot("cat $LITTLE_GOV_PATH").trim()
        val bigGov = RootUtils.runAsRoot("cat $BIG_GOV_PATH").trim()
        littleGov to bigGov
    }

    suspend fun setGovernor(governor: String) = withContext(Dispatchers.IO) {
        val cmd = """
            echo "$governor" > $LITTLE_GOV_PATH
            echo "$governor" > $BIG_GOV_PATH
        """.trimIndent()
        RootUtils.runAsRoot(cmd)
    }

    suspend fun isPerformanceMode(): Boolean = withContext(Dispatchers.IO) {
        val (littleGov, bigGov) = getGovernor()
        littleGov == "performance" && bigGov == "performance"
    }

    fun getMinFreqScript(littleFreq: String, bigFreq: String): String {
        val littleCoreCommands = LITTLE_CORE_PATHS.joinToString("\n") { "echo \"$littleFreq\" > $it" }
        val bigCoreCommands = BIG_CORE_PATHS.joinToString("\n") { "echo \"$bigFreq\" > $it" }

        return """
            #!/system/bin/sh
            while true; do
                $littleCoreCommands
                $bigCoreCommands
                sleep 2
            done
        """.trimIndent()
    }
}