package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.AppConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.PREFS_NAME
)

data class SavedSampServer(
    val address: String,
    val password: String = ""
) {
    val host: String
        get() = address.substringBefore(":").trim()

    val port: Int
        get() = address.substringAfter(":", AppConstants.DEFAULT_SAMP_PORT.toString())
            .trim()
            .toIntOrNull() ?: AppConstants.DEFAULT_SAMP_PORT
}

data class RenderSAUserPreferences(
    val nickname: String = "",
    val savedServers: List<SavedSampServer> = emptyList(),
    val selectedServerAddress: String = "",
    val gtaSaDirectoryPath: String = "",
    val gtaSaTreeUri: String = "",
    val gpuDriverMode: String = AppConstants.DRIVER_AUTO,
    val dxvkVersion: String = AppConstants.DXVK_1_10_3,
    val screenResolution: String = AppConstants.RES_1280_720,
    val box64Preset: String = AppConstants.BOX64_PERFORMANCE,
    val cpuAffinityEnabled: Boolean = true,
    val touchControlsEnabled: Boolean = true,
    val fpsHudEnabled: Boolean = true
)

class RenderSAPreferencesRepository(private val context: Context) {

    private object Keys {
        val NICKNAME = stringPreferencesKey(AppConstants.KEY_NICKNAME)
        val SAVED_SERVERS = stringPreferencesKey(AppConstants.KEY_SAVED_SERVERS)
        val SELECTED_SERVER = stringPreferencesKey(AppConstants.KEY_SELECTED_SERVER)
        val GTASA_PATH = stringPreferencesKey(AppConstants.KEY_GTASA_PATH)
        val GTASA_TREE_URI = stringPreferencesKey(AppConstants.KEY_GTASA_TREE_URI)
        val GPU_DRIVER = stringPreferencesKey(AppConstants.KEY_GPU_DRIVER)
        val DXVK_VERSION = stringPreferencesKey(AppConstants.KEY_DXVK_VERSION)
        val RESOLUTION = stringPreferencesKey(AppConstants.KEY_RESOLUTION)
        val BOX64_PRESET = stringPreferencesKey(AppConstants.KEY_BOX64_PRESET)
        val CPU_AFFINITY = booleanPreferencesKey(AppConstants.KEY_CPU_AFFINITY)
        val TOUCH_CONTROLS = booleanPreferencesKey(AppConstants.KEY_TOUCH_CONTROLS)
        val FPS_HUD = booleanPreferencesKey(AppConstants.KEY_FPS_HUD)
    }

    val preferencesFlow: Flow<RenderSAUserPreferences> = context.dataStore.data.map { prefs ->
        val serversRaw = prefs[Keys.SAVED_SERVERS].orEmpty()
        val parsedServers = parseServersJson(serversRaw)
        val selectedAddr = prefs[Keys.SELECTED_SERVER].orEmpty().let { saved ->
            if (parsedServers.any { it.address == saved }) saved
            else parsedServers.firstOrNull()?.address.orEmpty()
        }

        RenderSAUserPreferences(
            nickname = prefs[Keys.NICKNAME].orEmpty(),
            savedServers = parsedServers,
            selectedServerAddress = selectedAddr,
            gtaSaDirectoryPath = prefs[Keys.GTASA_PATH].orEmpty(),
            gtaSaTreeUri = prefs[Keys.GTASA_TREE_URI].orEmpty(),
            gpuDriverMode = prefs[Keys.GPU_DRIVER] ?: AppConstants.DRIVER_AUTO,
            dxvkVersion = prefs[Keys.DXVK_VERSION] ?: AppConstants.DXVK_1_10_3,
            screenResolution = prefs[Keys.RESOLUTION] ?: AppConstants.RES_1280_720,
            box64Preset = prefs[Keys.BOX64_PRESET] ?: AppConstants.BOX64_PERFORMANCE,
            cpuAffinityEnabled = prefs[Keys.CPU_AFFINITY] ?: true,
            touchControlsEnabled = prefs[Keys.TOUCH_CONTROLS] ?: true,
            fpsHudEnabled = prefs[Keys.FPS_HUD] ?: true
        )
    }

    suspend fun updateNickname(nickname: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NICKNAME] = nickname
        }
    }

    suspend fun updateGtaSaDirectoryPath(path: String, treeUri: String = "") {
        context.dataStore.edit { prefs ->
            prefs[Keys.GTASA_PATH] = path
            if (treeUri.isNotBlank()) {
                prefs[Keys.GTASA_TREE_URI] = treeUri
            }
        }
    }

    suspend fun addOrUpdateServer(address: String, password: String) {
        val normalized = normalizeServerAddress(address) ?: return
        context.dataStore.edit { prefs ->
            val current = parseServersJson(prefs[Keys.SAVED_SERVERS].orEmpty()).toMutableList()
            val existingIdx = current.indexOfFirst { it.address.equals(normalized, ignoreCase = true) }
            val entry = SavedSampServer(address = normalized, password = password)
            if (existingIdx >= 0) {
                current[existingIdx] = entry
            } else {
                current.add(entry)
            }
            prefs[Keys.SAVED_SERVERS] = serializeServersJson(current)
            prefs[Keys.SELECTED_SERVER] = normalized
        }
    }

    suspend fun removeServer(address: String) {
        context.dataStore.edit { prefs ->
            val current = parseServersJson(prefs[Keys.SAVED_SERVERS].orEmpty())
                .filterNot { it.address.equals(address, ignoreCase = true) }
            prefs[Keys.SAVED_SERVERS] = serializeServersJson(current)
            if (prefs[Keys.SELECTED_SERVER].equals(address, ignoreCase = true)) {
                prefs[Keys.SELECTED_SERVER] = current.firstOrNull()?.address.orEmpty()
            }
        }
    }

    suspend fun selectServer(address: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_SERVER] = address
        }
    }

    suspend fun updateGpuDriverMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GPU_DRIVER] = mode
        }
    }

    suspend fun updateDxvkVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DXVK_VERSION] = version
        }
    }

    suspend fun updateScreenResolution(resolution: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RESOLUTION] = resolution
        }
    }

    suspend fun updateBox64Preset(preset: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BOX64_PRESET] = preset
        }
    }

    suspend fun updateCpuAffinityEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CPU_AFFINITY] = enabled
        }
    }

    suspend fun updateTouchControlsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOUCH_CONTROLS] = enabled
        }
    }

    suspend fun updateFpsHudEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FPS_HUD] = enabled
        }
    }

    companion object {
        fun normalizeServerAddress(raw: String): String? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty() || cleaned.contains(" ")) return null
            val parts = cleaned.split(":")
            val host = parts[0].trim()
            if (host.isEmpty()) return null
            val port = if (parts.size >= 2) {
                parts[1].trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                AppConstants.DEFAULT_SAMP_PORT
            }
            return "$host:$port"
        }

        private fun parseServersJson(rawJson: String): List<SavedSampServer> {
            if (rawJson.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(rawJson)
                val list = ArrayList<SavedSampServer>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val addr = obj.optString("address").trim()
                    val pass = obj.optString("password", "")
                    if (addr.isNotEmpty()) {
                        list.add(SavedSampServer(addr, pass))
                    }
                }
                list
            } catch (e: Throwable) {
                emptyList()
            }
        }

        private fun serializeServersJson(servers: List<SavedSampServer>): String {
            val arr = JSONArray()
            for (srv in servers) {
                val obj = JSONObject()
                obj.put("address", srv.address)
                obj.put("password", srv.password)
                arr.put(obj)
            }
            return arr.toString()
        }
    }
}
