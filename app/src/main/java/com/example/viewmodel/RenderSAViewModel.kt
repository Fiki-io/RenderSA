package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example. R
import com.example.RenderSADisplayActivity
import com.example.core.AppConstants
import com.example.data.RenderSAPreferencesRepository
import com.example.data.RenderSAUserPreferences
import com.example.data.SavedSampServer
import com.example.engine.CpuTopology
import com.example.engine.GtaSaDirectoryVerification
import com.example.engine.NativeBridge
import com.example.engine.ResolvedGpuDriver
import com.example.engine.RootFsVerification
import com.example.engine.RuntimeEnvironmentManager
import com.example.engine.SampQueryResult
import com.example.util.StoragePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerItemUiState(
    val server: SavedSampServer,
    val isQuerying: Boolean = false,
    val queryResult: SampQueryResult? = null
)

data class RenderSAUiState(
    val selectedTabIndex: Int = 0,
    val preferences: RenderSAUserPreferences = RenderSAUserPreferences(),
    val hasStoragePermission: Boolean = true,
    val rootFsVerification: RootFsVerification = RootFsVerification(
        isInstalled = false,
        wineBinaryExists = false,
        box64BinaryExists = false,
        containerInitialized = false,
        rootFsPath = ""
    ),
    val gtaSaVerification: GtaSaDirectoryVerification = GtaSaDirectoryVerification(
        directoryPath = "",
        directoryExists = false,
        gtaSaExeExists = false,
        gtaSaExeSizeBytes = 0L,
        sampDllExists = false,
        sampDllSizeBytes = 0L,
        sampExeExists = false,
        sampExeSizeBytes = 0L
    ),
    val cpuTopology: CpuTopology = CpuTopology(1, 1, 1, 0, 0, 1),
    val resolvedGpuDriver: ResolvedGpuDriver = ResolvedGpuDriver(
        selectedMode = AppConstants.DRIVER_AUTO,
        effectiveDriver = AppConstants.DRIVER_VORTEK,
        vkDeviceName = "",
        vkApiVersionFormatted = ""
    ),
    val serverItems: List<ServerItemUiState> = emptyList(),
    val isExtractingRootFs: Boolean = false,
    val extractionProgressPercent: Int = 0,
    val isPreparingSession: Boolean = false,
    val statusBannerMessage: String? = null
)

sealed interface RenderSAUiEvent {
    data class ShowMessage(val message: String) : RenderSAUiEvent
    data class LaunchDisplayIntent(val intent: Intent) : RenderSAUiEvent
}

class RenderSAViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RenderSAPreferencesRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(RenderSAUiState())
    val uiState: StateFlow<RenderSAUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RenderSAUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RenderSAUiEvent> = _events.asSharedFlow()

    private val queryCache = LinkedHashMap<String, SampQueryResult>()

    init {
        refreshHardwareAndVerification()
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                val app = getApplication<Application>()
                val hasStoragePerm = StoragePathResolver.hasStoragePermission(app)
                val gtaVerification = withContext(Dispatchers.IO) {
                    RuntimeEnvironmentManager.verifyGtaSaDirectory(
                        rawPath = prefs.gtaSaDirectoryPath,
                        context = app,
                        treeUriString = prefs.gtaSaTreeUri
                    )
                }
                val resolvedGpu = withContext(Dispatchers.IO) {
                    RuntimeEnvironmentManager.resolveGpuDriver(app, prefs.gpuDriverMode)
                }

                val existingMap = _uiState.value.serverItems.associateBy { it.server.address }
                val updatedServers = prefs.savedServers.map { srv ->
                    val existing = existingMap[srv.address]
                    ServerItemUiState(
                        server = srv,
                        isQuerying = existing?.isQuerying ?: false,
                        queryResult = existing?.queryResult ?: queryCache[srv.address]
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        preferences = prefs,
                        hasStoragePermission = hasStoragePerm,
                        gtaSaVerification = gtaVerification,
                        resolvedGpuDriver = resolvedGpu,
                        serverItems = updatedServers
                    )
                }

                val unqueried = updatedServers.filter { it.queryResult == null && !it.isQuerying }
                if (unqueried.isNotEmpty()) {
                    unqueried.forEach { querySingleServer(it.server) }
                }
            }
        }
    }

    fun refreshHardwareAndVerification() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val hasStoragePerm = StoragePathResolver.hasStoragePermission(app)
            val rootFsCheck = RuntimeEnvironmentManager.verifyRootFs(app)
            val cpuTopo = NativeBridge.detectCpuTopology()
            val currentPrefs = _uiState.value.preferences
            val gpuResolved = RuntimeEnvironmentManager.resolveGpuDriver(app, currentPrefs.gpuDriverMode)
            val gtaCheck = RuntimeEnvironmentManager.verifyGtaSaDirectory(
                rawPath = currentPrefs.gtaSaDirectoryPath,
                context = app,
                treeUriString = currentPrefs.gtaSaTreeUri
            )

            if (gtaCheck.isReadyForSingleplayer &&
                gtaCheck.directoryPath.isNotBlank() &&
                gtaCheck.directoryPath != currentPrefs.gtaSaDirectoryPath
            ) {
                repository.updateGtaSaDirectoryPath(gtaCheck.directoryPath)
            }

            _uiState.update { state ->
                state.copy(
                    hasStoragePermission = hasStoragePerm,
                    rootFsVerification = rootFsCheck,
                    cpuTopology = cpuTopo,
                    resolvedGpuDriver = gpuResolved,
                    gtaSaVerification = gtaCheck
                )
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index.coerceIn(0, 1)) }
    }

    fun dismissBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun updateNickname(nickname: String) {
        viewModelScope.launch {
            repository.updateNickname(nickname)
        }
    }

    fun addServer(rawAddress: String, password: String): Boolean {
        val normalized = RenderSAPreferencesRepository.normalizeServerAddress(rawAddress)
        if (normalized == null) {
            val app = getApplication<Application>()
            _events.tryEmit(
                RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_invalid_server_address))
            )
            return false
        }
        viewModelScope.launch {
            repository.addOrUpdateServer(normalized, password.trim())
            querySingleServer(SavedSampServer(normalized, password.trim()))
        }
        return true
    }

    fun removeServer(address: String) {
        viewModelScope.launch {
            queryCache.remove(address)
            repository.removeServer(address)
        }
    }

    fun selectServer(address: String) {
        viewModelScope.launch {
            repository.selectServer(address)
        }
    }

    fun refreshAllServers() {
        val list = _uiState.value.serverItems.map { it.server }
        list.forEach { querySingleServer(it) }
    }

    fun querySingleServer(server: SavedSampServer) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    serverItems = state.serverItems.map { item ->
                        if (item.server.address == server.address) {
                            item.copy(isQuerying = true)
                        } else item
                    }
                )
            }

            val result = NativeBridge.querySampServer(
                host = server.host,
                port = server.port,
                timeoutMs = AppConstants.SAMP_UDP_TIMEOUT_MS
            )
            queryCache[server.address] = result

            _uiState.update { state ->
                state.copy(
                    serverItems = state.serverItems.map { item ->
                        if (item.server.address == server.address) {
                            item.copy(isQuerying = false, queryResult = result)
                        } else item
                    }
                )
            }
        }
    }

    fun importRootFsFromUri(uri: Uri) {
        if (_uiState.value.isExtractingRootFs) return
        val app = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtractingRootFs = true,
                    extractionProgressPercent = 1,
                    statusBannerMessage = app.getString(R.string.msg_extracting_rootfs)
                )
            }

            val result = RuntimeEnvironmentManager.importAndInitializeRootFs(
                context = app,
                rootFsUri = uri,
                onProgressPercent = { pct ->
                    _uiState.update { state ->
                        state.copy(extractionProgressPercent = pct)
                    }
                }
            )

            result.fold(
                onSuccess = { verification ->
                    _uiState.update {
                        it.copy(
                            isExtractingRootFs = false,
                            extractionProgressPercent = 100,
                            rootFsVerification = verification,
                            statusBannerMessage = app.getString(R.string.msg_rootfs_success)
                        )
                    }
                    _events.tryEmit(
                        RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_rootfs_success))
                    )
                },
                onFailure = { err ->
                    val errMsg = app.getString(
                        R.string.msg_rootfs_failed,
                        err.message ?: "Unknown Error"
                    )
                    _uiState.update {
                        it.copy(
                            isExtractingRootFs = false,
                            extractionProgressPercent = 0,
                            statusBannerMessage = errMsg
                        )
                    }
                    _events.tryEmit(RenderSAUiEvent.ShowMessage(errMsg))
                }
            )
        }
    }

    fun onGtaSaFolderTreeSelected(treeUri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                app.contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: Throwable) {
            }

            val resolvedPath = StoragePathResolver.resolveOrMirrorGtaSaDirectory(app, treeUri)
            if (resolvedPath.isNullOrBlank()) {
                _events.tryEmit(
                    RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_gtasa_invalid))
                )
                return@launch
            }

            val treeUriStr = treeUri.toString()
            repository.updateGtaSaDirectoryPath(resolvedPath, treeUriStr)
            val verification = RuntimeEnvironmentManager.verifyGtaSaDirectory(
                rawPath = resolvedPath,
                context = app,
                treeUriString = treeUriStr
            )
            val hasPerm = StoragePathResolver.hasStoragePermission(app)
            _uiState.update {
                it.copy(
                    hasStoragePermission = hasPerm,
                    gtaSaVerification = verification
                )
            }

            when {
                verification.isReadyForSingleplayer -> {
                    _events.tryEmit(
                        RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_gtasa_verified))
                    )
                }
                verification.requiresStoragePermission || !hasPerm -> {
                    _events.tryEmit(
                        RenderSAUiEvent.ShowMessage(app.getString(R.string.verify_storage_perm_required))
                    )
                }
                else -> {
                    _events.tryEmit(
                        RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_gtasa_invalid))
                    )
                }
            }
        }
    }

    fun updateGpuDriverMode(mode: String) {
        viewModelScope.launch {
            repository.updateGpuDriverMode(mode)
        }
    }

    fun updateDxvkVersion(version: String) {
        viewModelScope.launch {
            repository.updateDxvkVersion(version)
        }
    }

    fun updateScreenResolution(resolution: String) {
        viewModelScope.launch {
            repository.updateScreenResolution(resolution)
        }
    }

    fun updateBox64Preset(preset: String) {
        viewModelScope.launch {
            repository.updateBox64Preset(preset)
        }
    }

    fun updateCpuAffinityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateCpuAffinityEnabled(enabled)
        }
    }

    fun updateTouchControlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateTouchControlsEnabled(enabled)
        }
    }

    fun updateFpsHudEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateFpsHudEnabled(enabled)
        }
    }

    fun launchGameSession(launchMode: String) {
        val state = _uiState.value
        if (state.isPreparingSession || state.isExtractingRootFs) return
        val app = getApplication<Application>()

        if (!state.rootFsVerification.isInstalled) {
            _uiState.update { it.copy(selectedTabIndex = 1) }
            _events.tryEmit(
                RenderSAUiEvent.ShowMessage(app.getString(R.string.row_rootfs_desc_missing))
            )
            return
        }

        if (!state.gtaSaVerification.isReadyForSingleplayer) {
            _uiState.update { it.copy(selectedTabIndex = 1) }
            _events.tryEmit(
                RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_gtasa_invalid))
            )
            return
        }

        if (launchMode == AppConstants.MODE_SAMP) {
            if (!state.gtaSaVerification.isReadyForSamp) {
                _events.tryEmit(
                    RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_samp_missing_error))
                )
                return
            }
            if (state.preferences.nickname.trim().isEmpty()) {
                _events.tryEmit(
                    RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_nickname_empty_error))
                )
                return
            }
            if (state.preferences.selectedServerAddress.isBlank()) {
                _events.tryEmit(
                    RenderSAUiEvent.ShowMessage(app.getString(R.string.msg_server_empty_error))
                )
                return
            }
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPreparingSession = true,
                    statusBannerMessage = app.getString(R.string.msg_preparing_session)
                )
            }

            val prepResult = RuntimeEnvironmentManager.prepareSessionEnvironment(
                context = app,
                gtaSaDirPath = state.gtaSaVerification.directoryPath,
                nickname = state.preferences.nickname,
                gpuDriverMode = state.preferences.gpuDriverMode,
                dxvkVersion = state.preferences.dxvkVersion
            )

            prepResult.fold(
                onSuccess = { resolvedDriver ->
                    _uiState.update {
                        it.copy(
                            isPreparingSession = false,
                            statusBannerMessage = null,
                            resolvedGpuDriver = resolvedDriver
                        )
                    }

                    val selectedServerObj = state.preferences.savedServers.firstOrNull {
                        it.address == state.preferences.selectedServerAddress
                    }

                    val intent = Intent(app, RenderSADisplayActivity::class.java).apply {
                        putExtra(AppConstants.EXTRA_LAUNCH_MODE, launchMode)
                        putExtra(AppConstants.EXTRA_GTASA_PATH, state.gtaSaVerification.directoryPath)
                        putExtra(AppConstants.EXTRA_NICKNAME, state.preferences.nickname.trim())
                        putExtra(AppConstants.EXTRA_SERVER_ADDRESS, selectedServerObj?.address.orEmpty())
                        putExtra(AppConstants.EXTRA_SERVER_PASSWORD, selectedServerObj?.password.orEmpty())
                        putExtra(AppConstants.EXTRA_GPU_DRIVER, resolvedDriver.effectiveDriver)
                        putExtra(AppConstants.EXTRA_DXVK_VERSION, state.preferences.dxvkVersion)
                        putExtra(AppConstants.EXTRA_RESOLUTION, state.preferences.screenResolution)
                        putExtra(AppConstants.EXTRA_BOX64_PRESET, state.preferences.box64Preset)
                        putExtra(AppConstants.EXTRA_CPU_AFFINITY, state.preferences.cpuAffinityEnabled)
                        putExtra(AppConstants.EXTRA_TOUCH_CONTROLS, state.preferences.touchControlsEnabled)
                        putExtra(AppConstants.EXTRA_FPS_HUD, state.preferences.fpsHudEnabled)
                    }
                    _events.tryEmit(RenderSAUiEvent.LaunchDisplayIntent(intent))
                },
                onFailure = { err ->
                    val msg = app.getString(
                        R.string.msg_session_failed,
                        err.message ?: "Error"
                    )
                    _uiState.update {
                        it.copy(
                            isPreparingSession = false,
                            statusBannerMessage = msg
                        )
                    }
                    _events.tryEmit(RenderSAUiEvent.ShowMessage(msg))
                }
            )
        }
    }
}
