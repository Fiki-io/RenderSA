package com.example

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.core.AppConstants
import com.example.diagnostic.CrashReportData
import com.example.diagnostic.UniversalCrashLogger
import com.example.engine.CpuTopology
import com.example.engine.NativeBridge
import com.example.engine.RuntimeEnvironmentManager
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.UniversalErrorHoldScreen
import com.example.ui.theme.RenderSAColors
import com.example.ui.theme.RenderSADimens
import com.example.ui.theme.RenderSAShapes
import com.example.ui.theme.RenderSATheme
import com.winlator.alsaserver.ALSAClient
import com.winlator.core.EnvVars
import com.winlator.core.KeyValueSet
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.renderer.GLRenderer
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.winhandler.WinHandler
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Property
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import com.winlator.xserver.XSessionHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class RenderSADisplayActivity : ComponentActivity(), XSessionHost {

    private var xServer: XServer? = null
    private var xServerView: XServerView? = null
    private var touchpadView: TouchpadView? = null
    private var inputControlsView: InputControlsView? = null
    private var winHandler: WinHandler? = null
    private var xEnvironment: XEnvironment? = null
    private var rootFrameLayout: FrameLayout? = null
    private var inputControlsManager: InputControlsManager? = null

    private var cpuTopology: CpuTopology = CpuTopology(1, 1, 1, 0, 0, 1)
    private var cpuAffinityEnabled: Boolean = true
    private var touchControlsVisibleState = mutableStateOf(true)
    private val renderedFrameCount = AtomicInteger(0)

    @Volatile
    private var userRequestedExit: Boolean = false

    private val heldCrashReportState = mutableStateOf<CrashReportData?>(null)
    private val manualDiagnosticReportState = mutableStateOf<CrashReportData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureImmersiveWindow()

        UniversalCrashLogger.registerInSessionErrorCallback { report ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    heldCrashReportState.value = report
                    unlockOrientationForErrorReading()
                }
            }
        }

        val launchMode = intent.getStringExtra(AppConstants.EXTRA_LAUNCH_MODE)
            ?: AppConstants.MODE_SINGLEPLAYER
        val gtaSaPath = intent.getStringExtra(AppConstants.EXTRA_GTASA_PATH).orEmpty()
        val nickname = intent.getStringExtra(AppConstants.EXTRA_NICKNAME).orEmpty()
        val serverAddress = intent.getStringExtra(AppConstants.EXTRA_SERVER_ADDRESS).orEmpty()
        val serverPassword = intent.getStringExtra(AppConstants.EXTRA_SERVER_PASSWORD).orEmpty()
        val effectiveDriver = intent.getStringExtra(AppConstants.EXTRA_GPU_DRIVER)
            ?: AppConstants.DRIVER_VORTEK
        val dxvkVersion = intent.getStringExtra(AppConstants.EXTRA_DXVK_VERSION)
            ?: AppConstants.DXVK_1_10_3
        val screenResolution = intent.getStringExtra(AppConstants.EXTRA_RESOLUTION)
            ?: AppConstants.RES_1280_720
        val box64Preset = intent.getStringExtra(AppConstants.EXTRA_BOX64_PRESET)
            ?: AppConstants.BOX64_PERFORMANCE
        cpuAffinityEnabled = intent.getBooleanExtra(AppConstants.EXTRA_CPU_AFFINITY, true)
        val initialTouchControls = intent.getBooleanExtra(AppConstants.EXTRA_TOUCH_CONTROLS, true)
        val showFpsHud = intent.getBooleanExtra(AppConstants.EXTRA_FPS_HUD, true)

        touchControlsVisibleState.value = initialTouchControls
        cpuTopology = NativeBridge.detectCpuTopology()

        setContent {
            RenderSATheme {
                var showExitDialog by remember { mutableStateOf(false) }
                var currentFps by remember { mutableFloatStateOf(0f) }
                var pinnedCount by remember { mutableIntStateOf(0) }
                val touchVisible by touchControlsVisibleState
                val heldCrashReport by heldCrashReportState
                val manualReport by manualDiagnosticReportState

                BackHandler {
                    when {
                        manualReport != null -> {
                            manualDiagnosticReportState.value = null
                            configureImmersiveWindow()
                        }
                        heldCrashReport != null -> {
                            userRequestedExit = true
                            UniversalCrashLogger.markSessionCleanExit()
                            shutdownXEnvironment()
                            finish()
                        }
                        else -> {
                            showExitDialog = true
                        }
                    }
                }

                LaunchedEffect(cpuAffinityEnabled) {
                    var lastFpsTimestamp = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(1000L)
                        val now = SystemClock.elapsedRealtime()
                        val elapsedMs = (now - lastFpsTimestamp).coerceAtLeast(1L)
                        val frames = renderedFrameCount.getAndSet(0)
                        currentFps = (frames * 1000f) / elapsedMs.toFloat()
                        lastFpsTimestamp = now

                        if (cpuAffinityEnabled && cpuTopology.totalCores > 1 && heldCrashReport == null) {
                            val count = NativeBridge.nativeOptimizeRunningGameProcesses(
                                cpuTopology.pCoreMask,
                                cpuTopology.eCoreMask
                            )
                            pinnedCount = count
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        UniversalCrashLogger.registerInSessionErrorCallback(null)
                        shutdownXEnvironment()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RenderSAColors.BackgroundBase)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            buildNativeDisplayContainer(
                                context = ctx,
                                launchMode = launchMode,
                                gtaSaPath = gtaSaPath,
                                nickname = nickname,
                                serverAddress = serverAddress,
                                serverPassword = serverPassword,
                                effectiveDriver = effectiveDriver,
                                dxvkVersion = dxvkVersion,
                                screenResolution = screenResolution,
                                box64Preset = box64Preset,
                                showTouchControls = initialTouchControls
                            )
                        }
                    )

                    if (showFpsHud && heldCrashReport == null && manualReport == null) {
                        SessionTopHudOverlay(
                            fps = currentFps,
                            pCoreActive = cpuAffinityEnabled && pinnedCount > 0,
                            touchControlsVisible = touchVisible,
                            onToggleTouchControls = {
                                val next = !touchVisible
                                touchControlsVisibleState.value = next
                                setTouchControlsVisibility(next)
                            },
                            onToggleKeyboard = {
                                toggleSoftwareKeyboard()
                            },
                            onOpenLiveLog = {
                                val liveReport = UniversalCrashLogger.generateOnDemandDiagnosticReport(
                                    this@RenderSADisplayActivity
                                )
                                manualDiagnosticReportState.value = liveReport
                                unlockOrientationForErrorReading()
                            },
                            onRequestExit = {
                                showExitDialog = true
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(RenderSADimens.SpaceS)
                        )
                    }

                    val activeOverlayReport = heldCrashReport ?: manualReport
                    if (activeOverlayReport != null) {
                        val isFatalHold = heldCrashReport != null
                        UniversalErrorHoldScreen(
                            report = activeOverlayReport,
                            closeActionLabel = if (isFatalHold) {
                                stringResource(R.string.action_return_launcher)
                            } else {
                                stringResource(R.string.action_close_log_viewer)
                            },
                            onCloseOrReturn = {
                                if (isFatalHold) {
                                    userRequestedExit = true
                                    UniversalCrashLogger.markSessionCleanExit()
                                    shutdownXEnvironment()
                                    finish()
                                } else {
                                    manualDiagnosticReportState.value = null
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    configureImmersiveWindow()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (showExitDialog) {
                    ConfirmActionDialog(
                        title = stringResource(R.string.dialog_exit_session_title),
                        description = stringResource(R.string.dialog_exit_session_desc),
                        confirmText = stringResource(R.string.action_exit_now),
                        onDismiss = { showExitDialog = false },
                        onConfirm = {
                            showExitDialog = false
                            userRequestedExit = true
                            UniversalCrashLogger.markSessionCleanExit()
                            shutdownXEnvironment()
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun configureImmersiveWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun unlockOrientationForErrorReading() {
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Throwable) {
        }
    }

    private fun buildNativeDisplayContainer(
        context: Context,
        launchMode: String,
        gtaSaPath: String,
        nickname: String,
        serverAddress: String,
        serverPassword: String,
        effectiveDriver: String,
        dxvkVersion: String,
        screenResolution: String,
        box64Preset: String,
        showTouchControls: Boolean
    ): FrameLayout {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }
        rootFrameLayout = container

        try {
            UniversalCrashLogger.logCheckpoint(
                "RenderSADisplayActivity",
                "Membangun XServer & XServerView ($screenResolution)"
            )
            val server = XServer(this, ScreenInfo(screenResolution))
            val view = XServerView(context, server)
            server.setWinHandler(WinHandler(this))
            val handler = server.winHandler
            winHandler = handler
            xServer = server

            val renderer: GLRenderer = view.renderer
            renderer.setCursorVisible(false)

            server.windowManager.addOnWindowModificationListener(
                object : com.winlator.xserver.WindowManager.OnWindowModificationListener {
                    override fun onUpdateWindowContent(window: Window) {
                        renderedFrameCount.incrementAndGet()
                    }

                    override fun onModifyWindowProperty(window: Window, property: Property) {
                    }

                    override fun onMapWindow(window: Window) {
                        UniversalCrashLogger.logCheckpoint(
                            "XServer",
                            "Jendela X11 dipetakan: id=${window.id} (${window.width}x${window.height})"
                        )
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (cpuAffinityEnabled && cpuTopology.totalCores > 1) {
                                NativeBridge.nativeOptimizeRunningGameProcesses(
                                    cpuTopology.pCoreMask,
                                    cpuTopology.eCoreMask
                                )
                            }
                        }
                    }
                }
            )

            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            xServerView = view

            val tpView = TouchpadView(context, server, false)
            tpView.setSensitivity(1.0f)
            container.addView(
                tpView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            touchpadView = tpView

            val icManager = InputControlsManager(context)
            inputControlsManager = icManager
            val icView = InputControlsView(context)
            icView.setXServer(server)
            icView.setTouchpadView(tpView)
            icView.setOverlayOpacity(0.55f)

            val profiles: ArrayList<ControlsProfile> = icManager.getProfiles(false)
            val selectedProfile = profiles.firstOrNull {
                it.name.contains("GTA", ignoreCase = true) ||
                    it.name.contains("Shooter", ignoreCase = true) ||
                    it.name.contains("Virtual Gamepad", ignoreCase = true)
            } ?: profiles.firstOrNull()

            if (selectedProfile != null) {
                icView.setProfile(selectedProfile)
            }
            icView.setShowTouchscreenControls(showTouchControls)
            icView.visibility = if (showTouchControls) View.VISIBLE else View.GONE
            container.addView(
                icView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            inputControlsView = icView

            lifecycleScope.launch(Dispatchers.IO) {
                startXEnvironmentSession(
                    context = context,
                    server = server,
                    launchMode = launchMode,
                    gtaSaPath = gtaSaPath,
                    nickname = nickname,
                    serverAddress = serverAddress,
                    serverPassword = serverPassword,
                    effectiveDriver = effectiveDriver,
                    dxvkVersion = dxvkVersion,
                    screenResolution = screenResolution,
                    box64Preset = box64Preset
                )
            }
        } catch (t: Throwable) {
            val report = UniversalCrashLogger.recordComponentFatalError(
                component = "RenderSADisplayActivity",
                stage = "buildNativeDisplayContainer",
                error = t
            )
            runOnUiThread {
                heldCrashReportState.value = report
                unlockOrientationForErrorReading()
            }
        }

        return container
    }

    private fun startXEnvironmentSession(
        context: Context,
        server: XServer,
        launchMode: String,
        gtaSaPath: String,
        nickname: String,
        serverAddress: String,
        serverPassword: String,
        effectiveDriver: String,
        dxvkVersion: String,
        screenResolution: String,
        box64Preset: String
    ) {
        try {
            val rootFs = RootFS.find(context)
            val rootDir = rootFs.rootDir
            val wineRelPath = RuntimeEnvironmentManager.resolveWineRelativePath(rootDir)
            rootFs.winePath = "/$wineRelPath"
            com.example.engine.RootFsPathRelocator.relocateRootFsIfNeeded(
                context = context,
                rootDir = rootDir,
                forceFullScan = false
            )

            val gtaCheck = RuntimeEnvironmentManager.verifyGtaSaDirectory(gtaSaPath, context)
            val homeXUserDir = java.io.File(rootDir, "home/xuser")
            val winePrefixDir = java.io.File(homeXUserDir, ".wine")
            RuntimeEnvironmentManager.setupDosDevices(winePrefixDir, gtaCheck.directoryPath)

            val targetExeFile = java.io.File(gtaCheck.directoryPath, gtaCheck.actualExeName)
            UniversalCrashLogger.logCheckpoint(
                "RenderSADisplayActivity",
                "Verifikasi Exe Target: ${targetExeFile.absolutePath} (exists=${targetExeFile.exists()}, canRead=${targetExeFile.canRead()}, len=${targetExeFile.length()} B)"
            )

            val baseCommand = RuntimeEnvironmentManager.buildGuestLaunchCommand(
                launchMode = launchMode,
                gtaCheck = gtaCheck,
                nickname = nickname,
                serverAddress = serverAddress,
                serverPassword = serverPassword
            ).replace(AppConstants.RES_1280_720, screenResolution)

            UniversalCrashLogger.beginGameSession(
                launchMode = launchMode,
                command = baseCommand,
                driver = effectiveDriver,
                dxvk = dxvkVersion,
                resolution = screenResolution
            )

            val env = XEnvironment(context, rootFs)

            UniversalCrashLogger.logCheckpoint("XEnvironment", "Menyiapkan SysVSharedMemoryComponent")
            val shmComponent = SysVSharedMemoryComponent(
                server,
                UnixSocketConfig.create(rootDir.absolutePath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
            )
            env.addComponent(shmComponent)

            UniversalCrashLogger.logCheckpoint("XEnvironment", "Menyiapkan XServerComponent")
            val xServerComponent = XServerComponent(
                server,
                UnixSocketConfig.create(rootDir.absolutePath, UnixSocketConfig.XSERVER_PATH)
            )
            env.addComponent(xServerComponent)

            UniversalCrashLogger.logCheckpoint("XEnvironment", "Menyiapkan ALSAServerComponent")
            ALSAClient.assignFramesPerBuffer(context)
            val alsaOptions = ALSAClient.Options.fromKeyValueSet(
                KeyValueSet("performanceMode=1,volume=1.0")
            )
            val alsaComponent = ALSAServerComponent(
                UnixSocketConfig.create(rootDir.absolutePath, UnixSocketConfig.ALSA_SERVER_PATH),
                alsaOptions
            )
            env.addComponent(alsaComponent)

            if (effectiveDriver == AppConstants.DRIVER_VORTEK) {
                UniversalCrashLogger.logCheckpoint("XEnvironment", "Menyiapkan VortekRendererComponent")
                val vortekOptions = VortekRendererComponent.Options.fromKeyValueSet(
                    context,
                    KeyValueSet("vkMaxVersion=1.3,maxDeviceMemory=2048")
                )
                val vortekComponent = VortekRendererComponent(
                    server,
                    UnixSocketConfig.create(rootDir.absolutePath, UnixSocketConfig.VORTEK_SERVER_PATH),
                    vortekOptions
                )
                env.addComponent(vortekComponent)
            }

            val envVars: EnvVars = RuntimeEnvironmentManager.buildSessionEnvVars(
                rootDir = rootDir,
                effectiveDriver = effectiveDriver,
                dxvkVersion = dxvkVersion
            )

            val launcherComponent = GuestProgramLauncherComponent()
            launcherComponent.envVars = envVars
            launcherComponent.box64Preset = box64Preset
            launcherComponent.guestExecutable = baseCommand
            launcherComponent.terminationCallback = com.winlator.core.Callback { status ->
                val exitStatus = status ?: -1
                if (userRequestedExit) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    }
                } else {
                    val durationMs = (System.currentTimeMillis() - launcherComponent.lastStartTimestampMs)
                        .coerceAtLeast(0L)
                    // Jika sudah ada laporan kegagalan ProcessBuilder.start() atau komponen fatal lain, pertahankan laporan tersebut
                    val existingReport = heldCrashReportState.value ?: UniversalCrashLogger.activeCrashReport.value
                    val report = existingReport ?: UniversalCrashLogger.recordProcessUnexpectedExit(
                        command = launcherComponent.lastFullCommand.ifBlank { baseCommand },
                        workingDir = rootDir.absolutePath,
                        pid = GuestProgramLauncherComponent.getCurrentPid(),
                        exitCode = exitStatus,
                        durationMs = durationMs,
                        envSummary = launcherComponent.lastEnvSummary
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            heldCrashReportState.value = report
                            unlockOrientationForErrorReading()
                        }
                    }
                }
            }
            env.addComponent(launcherComponent)

            xEnvironment = env
            env.startEnvironmentComponents()
            UniversalCrashLogger.logCheckpoint("WinHandler", "Memulai WinHandler UDP")
            winHandler?.start()
            UniversalCrashLogger.logCheckpoint("Session", "Seluruh komponen XEnvironment telah dimulai")
        } catch (t: Throwable) {
            val report = UniversalCrashLogger.recordComponentFatalError(
                component = "RenderSADisplayActivity",
                stage = "startXEnvironmentSession",
                error = t
            )
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    heldCrashReportState.value = report
                    unlockOrientationForErrorReading()
                }
            }
        }
    }

    private fun setTouchControlsVisibility(visible: Boolean) {
        val icView = inputControlsView ?: return
        icView.setShowTouchscreenControls(visible)
        icView.visibility = if (visible) View.VISIBLE else View.GONE
        icView.invalidate()
    }

    private fun toggleSoftwareKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val targetView = xServerView ?: window.decorView
        targetView.requestFocus()
        imm.toggleSoftInputFromWindow(
            targetView.windowToken,
            InputMethodManager.SHOW_FORCED,
            0
        )
    }

    private fun shutdownXEnvironment() {
        try {
            winHandler?.stop()
            winHandler = null
        } catch (_: Throwable) {
        }
        try {
            xEnvironment?.stopEnvironmentComponents()
            xEnvironment = null
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        xEnvironment?.onPause()
        xServerView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (heldCrashReportState.value == null && manualDiagnosticReportState.value == null) {
            configureImmersiveWindow()
        }
        xServerView?.onResume()
        xEnvironment?.onResume()
    }

    override fun onDestroy() {
        UniversalCrashLogger.registerInSessionErrorCallback(null)
        shutdownXEnvironment()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (heldCrashReportState.value != null || manualDiagnosticReportState.value != null) {
            return super.dispatchKeyEvent(event)
        }
        val handler = winHandler
        val server = xServer
        if (handler != null && handler.onKeyEvent(event)) {
            return true
        }
        if (server != null && server.keyboard.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (heldCrashReportState.value != null || manualDiagnosticReportState.value != null) {
            return super.dispatchGenericMotionEvent(event)
        }
        val handler = winHandler
        val tp = touchpadView
        if (handler != null && handler.onGenericMotionEvent(event)) {
            return true
        }
        if (tp != null && tp.onExternalMouseEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun getHostActivity(): Activity = this

    override fun getPreferences(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(this)

    override fun getXServer(): XServer? = xServer

    override fun getXServerView(): XServerView? = xServerView

    override fun getInputControlsView(): InputControlsView? = inputControlsView

    override fun onDebugLog(message: String?) {
        if (!message.isNullOrBlank()) {
            Log.d("RenderSADisplay", message)
            UniversalCrashLogger.logProcessOutput("XServer", message)
        }
    }

    override fun onFramePresented(drawableId: Int) {
        renderedFrameCount.incrementAndGet()
    }
}

@Composable
private fun SessionTopHudOverlay(
    fps: Float,
    pCoreActive: Boolean,
    touchControlsVisible: Boolean,
    onToggleTouchControls: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onOpenLiveLog: () -> Unit,
    onRequestExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RenderSAShapes.Tag)
            .background(RenderSAColors.SurfaceModal)
            .border(
                RenderSADimens.BorderStrokeWidth,
                RenderSAColors.BorderSubtle,
                RenderSAShapes.Tag
            )
            .padding(horizontal = RenderSADimens.SpaceXS, vertical = RenderSADimens.SpaceXXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
    ) {
        Text(
            text = stringResource(R.string.hud_fps_format, fps),
            style = MaterialTheme.typography.labelMedium,
            color = RenderSAColors.TextPrimary,
            modifier = Modifier.padding(horizontal = RenderSADimens.SpaceXS)
        )

        if (pCoreActive) {
            Text(
                text = stringResource(R.string.hud_pcore_active),
                style = MaterialTheme.typography.labelMedium,
                color = RenderSAColors.AccentPrimary,
                modifier = Modifier.padding(horizontal = RenderSADimens.SpaceXXS)
            )
        }

        HudCompactActionChip(
            text = stringResource(R.string.hud_toggle_controls),
            active = touchControlsVisible,
            onClick = onToggleTouchControls,
            tag = "hud_toggle_controls"
        )

        HudCompactActionChip(
            text = stringResource(R.string.hud_toggle_keyboard),
            active = false,
            onClick = onToggleKeyboard,
            tag = "hud_toggle_keyboard"
        )

        HudCompactActionChip(
            text = stringResource(R.string.session_Overlay_log_btn),
            active = false,
            onClick = onOpenLiveLog,
            tag = "hud_open_log"
        )

        HudCompactActionChip(
            text = stringResource(R.string.hud_exit_session),
            active = false,
            onClick = onRequestExit,
            tag = "hud_exit_button"
        )
    }
}

@Composable
private fun HudCompactActionChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val bg = if (active) RenderSAColors.AccentSubtleBg else RenderSAColors.SurfaceInteractive
    val border = if (active) RenderSAColors.AccentBorder else RenderSAColors.BorderSubtle

    Box(
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 36.dp)
            .clip(RenderSAShapes.Tag)
            .background(bg)
            .border(RenderSADimens.BorderStrokeWidth, border, RenderSAShapes.Tag)
            .clickable(onClick = onClick)
            .padding(horizontal = RenderSADimens.SpaceS, vertical = RenderSADimens.SpaceXXS),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = RenderSAColors.TextPrimary
        )
    }
}
