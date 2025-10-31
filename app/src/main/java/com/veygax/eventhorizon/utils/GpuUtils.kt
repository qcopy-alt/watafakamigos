package com.veygax.eventhorizon.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GpuMonitorInfo(
    val tempCelsius: Int = 0,
    val freqMhz: Int = 0,
    val maxFreqMhz: Int = 0,
    val minFreqMhz: Int = 0,
    val usagePercent: Int = 0
)

object GpuUtils {
    private const val TAG = "GpuUtils"

    // Base GPU path
    private const val GPU_PATH = "/sys/class/kgsl/kgsl-3d0"
    private const val GPU_DEVFREQ_PATH = "$GPU_PATH/devfreq"

    // Scripting constants
    const val GPU_MIN_FREQ_SCRIPT_NAME = "gpu_min_freq_lock.sh"
    const val DEFAULT_GPU_MIN_FREQ = "285"
    const val GPU_MAX_FREQ_SCRIPT_NAME = "gpu_max_freq_lock.sh"
    const val DEFAULT_GPU_MAX_FREQ = "492"

    // --- Node Locations ---
    // Paths for Current Freq (Read in Hz or MHz)
    private val FREQ_PATHS = listOf(
        "$GPU_PATH/gpuclk",
        "$GPU_DEVFREQ_PATH/cur_freq"
    )

    // Paths for Max Freq (Read)
    private val MAX_FREQ_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/max_clock_mhz",
        "$GPU_DEVFREQ_PATH/max_freq",
        "$GPU_PATH/max_gpuclk"
    )
    // Paths for Min Freq (Read)
    private val MIN_FREQ_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/min_clock_mhz",
        "$GPU_DEVFREQ_PATH/min_freq"
    )

    // --- Devfreq paths for WRITING ---
    private val GPU_SET_MAX_FREQ_PATH_HZ = "$GPU_DEVFREQ_PATH/max_freq"
    private const val GPU_SET_MIN_FREQ_PATH_MHZ = "/sys/class/kgsl/kgsl-3d0/min_clock_mhz"
    private val GPU_SET_MIN_FREQ_PATH_HZ = "$GPU_DEVFREQ_PATH/min_freq"

    private val USAGE_PATHS = listOf(
        "$GPU_PATH/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
    )
    private val TEMP_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp",
        "$GPU_PATH/temp",
        "/sys/class/thermal/thermal_zone10/temp",
        "/sys/class/thermal/thermal_zone9/temp"
    )

    suspend fun getGpuMonitorInfo(): GpuMonitorInfo = withContext(Dispatchers.IO) {
        try {
            val freqRaw = readFirstAvailable(FREQ_PATHS)
            val maxFreqRaw = readFirstAvailable(MAX_FREQ_PATHS)
            val minFreqRaw = readFirstAvailable(MIN_FREQ_PATHS)
            val usageRaw = readFirstAvailable(USAGE_PATHS)
            val tempRaw = readFirstAvailable(TEMP_PATHS)

            val freq = convertFreq(freqRaw)
            val maxFreq = convertFreq(maxFreqRaw)
            val minFreq = convertFreq(minFreqRaw)

            val usagePercent = parseGpuBusy(usageRaw)

            val tempC = tempRaw.toIntOrNull()?.let {
                if (it > 1000) it / 1000 else it
            } ?: 0

            GpuMonitorInfo(
                tempCelsius = tempC,
                freqMhz = freq,
                maxFreqMhz = maxFreq,
                minFreqMhz = minFreq,
                usagePercent = usagePercent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read GPU info", e)
            GpuMonitorInfo()
        }
    }

    private fun convertFreq(raw: String): Int {
        val longValue = raw.toLongOrNull() ?: 0
        return when {
            longValue > 100_000_000 -> (longValue / 1_000_000).toInt()
            longValue > 0 -> longValue.toInt()
            else -> 0
        }
    }

    private suspend fun readFirstAvailable(paths: List<String>): String {
        for (path in paths) {
            val result = RootUtils.runAsRoot("cat $path").trim()
            if (result.isNotEmpty() && !result.contains("No such file")) return result
        }
        return ""
    }

    private fun parseGpuBusy(raw: String): Int {
        return try {
            val cleanedRaw = raw.replace("%", "").trim()
            val simpleInt = cleanedRaw.toIntOrNull()
            if (simpleInt != null) return simpleInt

            val parts = cleanedRaw.split(" ")
            if (parts.size >= 2) {
                val busy = parts[0].toLongOrNull() ?: 0
                val total = parts[1].toLongOrNull() ?: 1
                if (total > 0) (100 * busy / total).toInt() else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getGpuMinFreqScript(minFreqMhz: String): String {
        return """
            #!/system/bin/sh
            while true; do
                echo "$minFreqMhz" > $GPU_SET_MIN_FREQ_PATH_MHZ
                sleep 2
            done
        """.trimIndent()
    }

    fun getGpuMaxFreqScript(maxFreqMhz: String): String {
        // Convert MHz to HZ for the devfreq node
        val maxFreqHz = (maxFreqMhz.toLongOrNull() ?: DEFAULT_GPU_MAX_FREQ.toLong()) * 1_000_000
        return """
            #!/system/bin/sh
            while true; do
                echo "$maxFreqHz" > $GPU_SET_MAX_FREQ_PATH_HZ
                sleep 2
            done
        """.trimIndent()
    }

    suspend fun setGpuMaxFreq(freqHz: String): Boolean = withContext(Dispatchers.IO) {
        if (freqHz.toLongOrNull() == null) {
            Log.w(TAG, "Invalid frequency input for Max Freq: $freqHz")
            return@withContext false
        }

        val command = "echo '$freqHz' > $GPU_SET_MAX_FREQ_PATH_HZ"
        val writeResult = RootUtils.runAsRoot(command)

        if (!writeResult.contains("ERROR:", ignoreCase = true) && !writeResult.contains("No such file") && !writeResult.contains("Permission denied")) {

            val readCommand = "cat $GPU_SET_MAX_FREQ_PATH_HZ"
            val readResult = RootUtils.runAsRoot(readCommand).trim()

            val normalizedReadResult = readResult.trim()
            val normalizedExpected = freqHz.trim()

            if (normalizedReadResult == normalizedExpected) {
                Log.d(TAG, "VERIFIED Success: GPU max freq set to $freqHz Hz on $GPU_SET_MAX_FREQ_PATH_HZ")
                return@withContext true
            } else {
                Log.w(TAG, "VERIFICATION FAILURE: Write succeeded in shell, but read back wrong value.")
                Log.w(TAG, "Path: $GPU_SET_MAX_FREQ_PATH_HZ")
                Log.w(TAG, "Expected (Hz): $normalizedExpected")
                Log.w(TAG, "Actual Read (Hz): $normalizedReadResult")
                return@withContext false
            }
        }
        Log.w(TAG, "SHELL FAILURE: Failed to set GPU max freq. Path: $GPU_SET_MAX_FREQ_PATH_HZ, Command: $command, Result: $writeResult. freqHz: $freqHz")
        return@withContext false
    }

    suspend fun setGpuMinFreq(freqHz: String): Boolean = withContext(Dispatchers.IO) {
        if (freqHz.toLongOrNull() == null) {
            Log.w(TAG, "Invalid frequency input for Min Freq: $freqHz")
            return@withContext false
        }

        val command = "echo '$freqHz' > $GPU_SET_MIN_FREQ_PATH_HZ"
        val writeResult = RootUtils.runAsRoot(command)

        if (!writeResult.contains("ERROR:", ignoreCase = true) && !writeResult.contains("No such file") && !writeResult.contains("Permission denied")) {

            val readCommand = "cat $GPU_SET_MIN_FREQ_PATH_HZ"
            val readResult = RootUtils.runAsRoot(readCommand).trim()

            val normalizedReadResult = readResult.trim()
            val normalizedExpected = freqHz.trim()

            if (normalizedReadResult == normalizedExpected) {
                Log.d(TAG, "VERIFIED Success: GPU min freq set to $freqHz Hz on $GPU_SET_MIN_FREQ_PATH_HZ")
                return@withContext true
            } else {
                Log.w(TAG, "VERIFICATION FAILURE: Write succeeded in shell, but read back wrong value.")
                Log.w(TAG, "Path: $GPU_SET_MIN_FREQ_PATH_HZ")
                Log.w(TAG, "Expected (Hz): $normalizedExpected")
                Log.w(TAG, "Actual Read (Hz): $normalizedReadResult")
                return@withContext false
            }
        }
        Log.w(TAG, "SHELL FAILURE: Failed to set GPU min freq. Path: $GPU_SET_MIN_FREQ_PATH_HZ, Command: $command, Result: $writeResult. freqHz: $freqHz")
        return@withContext false
    }
}