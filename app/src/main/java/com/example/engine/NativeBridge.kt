package com.example.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CpuTopology(
    val totalCores: Int,
    val pCoreMask: Int,
    val eCoreMask: Int,
    val maxFreqKhz: Int,
    val minFreqKhz: Int,
    val pCoreCount: Int
) {
    val maxFreqMhz: Int get() = maxFreqKhz / 1000
    val pCoreMaskHex: String get() = Integer.toHexString(pCoreMask).uppercase()
    val eCoreMaskHex: String get() = Integer.toHexString(eCoreMask).uppercase()
}

data class SampQueryResult(
    val online: Boolean,
    val pingMs: Int = -1,
    val hasPassword: Boolean = false,
    val players: Int = 0,
    val maxPlayers: Int = 0,
    val hostname: String = "",
    val gamemode: String = "",
    val language: String = ""
)

object NativeBridge {
    private const val TAG = "NativeBridge"

    init {
        try {
            System.loadLibrary("winlator")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libwinlator.so", e)
        }
    }

    @JvmStatic
    external fun nativeGetCpuTopologyInfo(): IntArray?

    @JvmStatic
    external fun nativeSetCurrentThreadAffinity(cpuMask: Int): Boolean

    @JvmStatic
    external fun nativeOptimizeRunningGameProcesses(pCoreMask: Int, eCoreMask: Int): Int

    @JvmStatic
    external fun nativeQuerySampServer(host: String, port: Int, timeoutMs: Int): String?

    fun detectCpuTopology(): CpuTopology {
        return try {
            val raw = nativeGetCpuTopologyInfo()
            if (raw != null && raw.size >= 6) {
                CpuTopology(
                    totalCores = raw[0],
                    pCoreMask = raw[1],
                    eCoreMask = raw[2],
                    maxFreqKhz = raw[3],
                    minFreqKhz = raw[4],
                    pCoreCount = raw[5]
                )
            } else {
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val fullMask = (1 shl cores) - 1
                CpuTopology(cores, fullMask, fullMask, 0, 0, cores)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read CPU topology from native layer", e)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val fullMask = (1 shl cores) - 1
            CpuTopology(cores, fullMask, fullMask, 0, 0, cores)
        }
    }

    suspend fun querySampServer(
        host: String,
        port: Int,
        timeoutMs: Int
    ): SampQueryResult = withContext(Dispatchers.IO) {
        try {
            val raw = nativeQuerySampServer(host.trim(), port, timeoutMs) ?: return@withContext SampQueryResult(online = false)
            if (!raw.startsWith("ONLINE|")) {
                return@withContext SampQueryResult(online = false)
            }
            val parts = raw.split("|", limit = 8)
            if (parts.size < 7) {
                return@withContext SampQueryResult(online = false)
            }
            val pingMs = parts[1].toIntOrNull() ?: -1
            if (pingMs < 0) {
                return@withContext SampQueryResult(online = false)
            }
            val passwordFlag = (parts[2].toIntOrNull() ?: 0) != 0
            val players = parts[3].toIntOrNull() ?: 0
            val maxPlayers = parts[4].toIntOrNull() ?: 0
            val hostname = parts[5].trim()
            val gamemode = parts[6].trim()
            val language = if (parts.size >= 8) parts[7].trim() else ""
            SampQueryResult(
                online = true,
                pingMs = pingMs,
                hasPassword = passwordFlag,
                players = players,
                maxPlayers = maxPlayers,
                hostname = hostname,
                gamemode = gamemode,
                language = language
            )
        } catch (e: Throwable) {
            Log.e(TAG, "UDP query failed for $host:$port", e)
            SampQueryResult(online = false)
        }
    }
}
