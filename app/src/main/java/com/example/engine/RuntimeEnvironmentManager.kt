package com.example.engine

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.core.AppConstants
import com.example.diagnostic.UniversalCrashLogger
import com.example.util.StoragePathResolver
import com.winlator.core.DefaultVersion
import com.winlator.core.EnvVars
import com.winlator.core.FileUtils
import com.winlator.core.GPUHelper
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.RootFS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class GtaSaDirectoryVerification(
    val directoryPath: String,
    val directoryExists: Boolean,
    val gtaSaExeExists: Boolean,
    val gtaSaExeSizeBytes: Long,
    val sampDllExists: Boolean,
    val sampDllSizeBytes: Long,
    val sampExeExists: Boolean,
    val sampExeSizeBytes: Long,
    val requiresStoragePermission: Boolean = false,
    val actualExeName: String = "gta_sa.exe",
    val actualSampExeName: String = "samp.exe"
) {
    val isReadyForSingleplayer: Boolean
        get() = !requiresStoragePermission && directoryExists && gtaSaExeExists && gtaSaExeSizeBytes > 64 * 1024L

    val isReadyForSamp: Boolean
        get() = isReadyForSingleplayer && sampDllExists && sampDllSizeBytes > 16 * 1024L
}

data class RootFsVerification(
    val isInstalled: Boolean,
    val wineBinaryExists: Boolean,
    val box64BinaryExists: Boolean,
    val containerInitialized: Boolean,
    val rootFsPath: String
)

data class ResolvedGpuDriver(
    val selectedMode: String,
    val effectiveDriver: String,
    val vkDeviceName: String,
    val vkApiVersionFormatted: String
)

object RuntimeEnvironmentManager {
    private const val TAG = "RuntimeEnvManager"
    private const val RFS_VERSION_STAMP = "17"
    private const val CONTAINER_READY_MARKER = ".rendersa_container_ready"
    private const val DLLS_LINKED_MARKER_V2 = ".rendersa_dlls_linked_v2"
    private const val INSTALLED_DXVK_MARKER = ".rendersa_dxvk_version"
    private const val INSTALLED_DRIVER_MARKER = ".rendersa_gpu_driver"
    private const val INSTALLED_WINCOMPONENTS_MARKER = ".rendersa_wincomponents_ready"

    private val UNNEEDED_WINE_SERVICES = listOf(
        "BITS", "Eventlog", "HTTP", "LanmanServer", "NDIS",
        "PlugPlay", "RpcSs", "scardsvr", "Schedule", "Spooler",
        "StiSvc", "TermService", "Winmgmt", "wuauserv", "winebth"
    )

    private fun isBinaryOrSymlinkPresent(file: File): Boolean {
        if (file.isFile && file.length() > 0L) return true
        if (FileUtils.isSymlink(file)) {
            val targetName = FileUtils.readSymlink(file)
            if (targetName.isNotEmpty()) {
                val resolved = if (targetName.startsWith("/")) {
                    File(targetName)
                } else {
                    File(file.parentFile, targetName)
                }
                if (resolved.exists()) return true
            }
        }
        return false
    }

    fun resolveWineRelativePath(rootDir: File): String {
        val candidates = mutableListOf("opt/wine", "usr", "usr/local")
        val optDir = File(rootDir, "opt")
        if (optDir.isDirectory) {
            optDir.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name.startsWith("wine", ignoreCase = true)) {
                    val rel = "opt/${child.name}"
                    if (!candidates.contains(rel)) {
                        candidates.add(rel)
                    }
                }
            }
        }
        for (rel in candidates) {
            val binDir = File(rootDir, "$rel/bin")
            val wineBin = File(binDir, "wine")
            val wine64Bin = File(binDir, "wine64")
            val wineServerBin = File(binDir, "wineserver")
            if ((isBinaryOrSymlinkPresent(wineBin) || isBinaryOrSymlinkPresent(wine64Bin)) &&
                isBinaryOrSymlinkPresent(wineServerBin)
            ) {
                return rel
            }
        }
        return "opt/wine"
    }

    fun verifyRootFs(context: Context): RootFsVerification {
        val rootFs = RootFS.find(context)
        val rootDir = rootFs.rootDir
        val wineRelPath = resolveWineRelativePath(rootDir)
        rootFs.winePath = "/$wineRelPath"

        val wineBinDir = File(rootDir, "$wineRelPath/bin")
        val wineBin = File(wineBinDir, "wine")
        val wine64Bin = File(wineBinDir, "wine64")
        val wineServerBin = File(wineBinDir, "wineserver")
        val box64Bin = File(rootDir, "usr/local/bin/box64")
        val box64FallbackBin = File(rootDir, "usr/bin/box64")
        val homeXUserDir = File(rootDir, "home/xuser")
        val containerMarker = File(homeXUserDir, CONTAINER_READY_MARKER)
        val dllsLinkedMarker = File(homeXUserDir, DLLS_LINKED_MARKER_V2)
        val systemReg = File(homeXUserDir, ".wine/system.reg")

        var wineExists = (isBinaryOrSymlinkPresent(wineBin) || isBinaryOrSymlinkPresent(wine64Bin)) &&
            isBinaryOrSymlinkPresent(wineServerBin)
        var box64Exists = isBinaryOrSymlinkPresent(box64Bin) || isBinaryOrSymlinkPresent(box64FallbackBin)

        // Auto-repair if RootFS was already extracted but finalization or box64/container needs completion
        if (wineExists && (!box64Exists || !systemReg.isFile || !dllsLinkedMarker.isFile || !rootFs.isValid)) {
            try {
                if (!box64Exists) {
                    val box64Version = DefaultVersion.BOX64
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context,
                        "box64/box64-$box64Version.tzst",
                        rootDir
                    )
                    box64Exists = isBinaryOrSymlinkPresent(box64Bin) || isBinaryOrSymlinkPresent(box64FallbackBin)
                }
                if (!systemReg.isFile) {
                    homeXUserDir.mkdirs()
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context,
                        "container_pattern.tzst",
                        homeXUserDir
                    )
                }
                finalizeRootFsInstallation(context, rootFs, rootDir, wineRelPath)
            } catch (e: Throwable) {
                Log.e(TAG, "Auto-repair of existing RootFS failed", e)
            }
        }

        val containerReady = containerMarker.isFile && systemReg.isFile && systemReg.length() > 0L

        return RootFsVerification(
            isInstalled = rootFs.isValid && wineExists && box64Exists && containerReady,
            wineBinaryExists = wineExists,
            box64BinaryExists = box64Exists,
            containerInitialized = containerReady,
            rootFsPath = rootDir.absolutePath
        )
    }

    private fun finalizeRootFsInstallation(
        context: Context,
        rootFs: RootFS,
        rootDir: File,
        wineRelPath: String
    ) {
        val homeXUserDir = File(rootDir, "home/xuser")
        homeXUserDir.mkdirs()

        // Ensure /opt/wine symlink exists if wine was located at another path
        if (wineRelPath != "opt/wine") {
            val optWine = File(rootDir, "opt/wine")
            if (!optWine.exists()) {
                optWine.parentFile?.mkdirs()
                recreateSymlink(File(rootDir, wineRelPath).absolutePath, optWine)
            }
        }

        // Ensure 'wine' binary exists if only 'wine64' is present
        val wineBinDir = File(rootDir, "$wineRelPath/bin")
        val wineBin = File(wineBinDir, "wine")
        val wine64Bin = File(wineBinDir, "wine64")
        if (!wineBin.exists() && !FileUtils.isSymlink(wineBin) && wine64Bin.exists()) {
            recreateSymlink("wine64", wineBin)
        }

        linkCommonWineDlls(context, rootDir, homeXUserDir, wineRelPath)

        val box64Version = DefaultVersion.BOX64
        rootFs.createRFSVersionFile(RFS_VERSION_STAMP.toInt())
        File(homeXUserDir, ".box64_version").writeText(box64Version)
        File(homeXUserDir, CONTAINER_READY_MARKER).writeText(RFS_VERSION_STAMP)
        File(homeXUserDir, DLLS_LINKED_MARKER_V2).writeText(RFS_VERSION_STAMP)

        val box64Bin = File(rootDir, "usr/local/bin/box64")
        if (box64Bin.exists()) {
            FileUtils.chmod(box64Bin, 493)
        }
        wineBinDir.listFiles()?.forEach { file ->
            if (file.isFile) FileUtils.chmod(file, 493)
        }

        RootFsPathRelocator.relocateRootFsIfNeeded(
            context = context,
            rootDir = rootDir,
            forceFullScan = true
        )
    }

    fun verifyGtaSaDirectory(
        rawPath: String,
        context: Context? = null,
        treeUriString: String = ""
    ): GtaSaDirectoryVerification {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return GtaSaDirectoryVerification(
                directoryPath = "",
                directoryExists = false,
                gtaSaExeExists = false,
                gtaSaExeSizeBytes = 0L,
                sampDllExists = false,
                sampDllSizeBytes = 0L,
                sampExeExists = false,
                sampExeSizeBytes = 0L,
                requiresStoragePermission = false
            )
        }

        val hasStoragePerm = context?.let { StoragePathResolver.hasStoragePermission(it) } ?: true
        val initialDir = File(trimmed)
        if (!initialDir.exists() || !initialDir.isDirectory) {
            return GtaSaDirectoryVerification(
                directoryPath = trimmed,
                directoryExists = false,
                gtaSaExeExists = false,
                gtaSaExeSizeBytes = 0L,
                sampDllExists = false,
                sampDllSizeBytes = 0L,
                sampExeExists = false,
                sampExeSizeBytes = 0L,
                requiresStoragePermission = !hasStoragePerm
            )
        }

        val resolvedDir = StoragePathResolver.findGtaSaRootDirectory(initialDir)
        val children = resolvedDir.listFiles().orEmpty()
        fun findIgnoreCase(targetName: String): File? {
            return children.firstOrNull { it.isFile && it.name.equals(targetName, ignoreCase = true) }
        }

        val gtaSaExe = findIgnoreCase("gta_sa.exe") ?: findIgnoreCase("gta-sa.exe")
        val sampDll = findIgnoreCase("samp.dll")
        val sampExe = findIgnoreCase("samp.exe")
        val resolvedExeName = gtaSaExe?.name ?: "gta_sa.exe"
        val resolvedSampExeName = sampExe?.name ?: "samp.exe"

        if (gtaSaExe != null && gtaSaExe.canRead()) {
            return GtaSaDirectoryVerification(
                directoryPath = resolvedDir.absolutePath,
                directoryExists = true,
                gtaSaExeExists = true,
                gtaSaExeSizeBytes = gtaSaExe.length(),
                sampDllExists = sampDll != null && sampDll.canRead(),
                sampDllSizeBytes = sampDll?.length() ?: 0L,
                sampExeExists = sampExe != null && sampExe.canRead(),
                sampExeSizeBytes = sampExe?.length() ?: 0L,
                requiresStoragePermission = false,
                actualExeName = resolvedExeName,
                actualSampExeName = resolvedSampExeName
            )
        }

        // If direct File API could not see gta_sa.exe on shared storage because MANAGE_EXTERNAL_STORAGE is missing,
        // inspect SAF DocumentFile if available so we can accurately inform the user that Storage Permission is needed.
        if (!hasStoragePerm && context != null && treeUriString.isNotBlank()) {
            try {
                val treeUri = Uri.parse(treeUriString)
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                if (docDir != null && docDir.isDirectory) {
                    val docChildren = docDir.listFiles()
                    val docExe = docChildren.firstOrNull {
                        it.isFile && (
                            it.name.equals("gta_sa.exe", ignoreCase = true) ||
                                it.name.equals("gta-sa.exe", ignoreCase = true)
                            )
                    }
                    val docSampDll = docChildren.firstOrNull {
                        it.isFile && it.name.equals("samp.dll", ignoreCase = true)
                    }
                    val docSampExe = docChildren.firstOrNull {
                        it.isFile && it.name.equals("samp.exe", ignoreCase = true)
                    }
                    if (docExe != null) {
                        return GtaSaDirectoryVerification(
                            directoryPath = resolvedDir.absolutePath,
                            directoryExists = true,
                            gtaSaExeExists = true,
                            gtaSaExeSizeBytes = docExe.length(),
                            sampDllExists = docSampDll != null,
                            sampDllSizeBytes = docSampDll?.length() ?: 0L,
                            sampExeExists = docSampExe != null,
                            sampExeSizeBytes = docSampExe?.length() ?: 0L,
                            requiresStoragePermission = true,
                            actualExeName = docExe.name ?: "gta_sa.exe",
                            actualSampExeName = docSampExe?.name ?: "samp.exe"
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return GtaSaDirectoryVerification(
            directoryPath = resolvedDir.absolutePath,
            directoryExists = true,
            gtaSaExeExists = false,
            gtaSaExeSizeBytes = 0L,
            sampDllExists = sampDll != null && sampDll.canRead(),
            sampDllSizeBytes = sampDll?.length() ?: 0L,
            sampExeExists = sampExe != null && sampExe.canRead(),
            sampExeSizeBytes = sampExe?.length() ?: 0L,
            requiresStoragePermission = !hasStoragePerm,
            actualExeName = resolvedExeName,
            actualSampExeName = resolvedSampExeName
        )
    }

    fun resolveGpuDriver(context: Context, selectedMode: String): ResolvedGpuDriver {
        val rendererName = try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString("gpu_renderer", "")
                ?.takeIf { it.isNotBlank() }
                ?: android.os.Build.HARDWARE.orEmpty()
        } catch (_: Throwable) {
            android.os.Build.HARDWARE.orEmpty()
        }
        val apiVersion = try {
            GPUHelper.vkGetApiVersion()
        } catch (_: Throwable) {
            0
        }
        val major = GPUHelper.vkVersionMajor(apiVersion)
        val minor = GPUHelper.vkVersionMinor(apiVersion)
        val patch = GPUHelper.vkVersionPatch(apiVersion)
        val apiFormatted = if (apiVersion > 0) "$major.$minor.$patch" else "1.3"

        val normalizedName = (rendererName + " " + android.os.Build.HARDWARE + " " + android.os.Build.BOARD)
            .lowercase(Locale.ROOT)
        val isAdreno = normalizedName.contains("adreno") ||
            normalizedName.contains("qcom") ||
            normalizedName.contains("taro") ||
            normalizedName.contains("kalama") ||
            normalizedName.contains("lahaina") ||
            normalizedName.contains("sm8") ||
            normalizedName.contains("sm7")
        val effective = when (selectedMode) {
            AppConstants.DRIVER_VORTEK -> AppConstants.DRIVER_VORTEK
            AppConstants.DRIVER_TURNIP -> AppConstants.DRIVER_TURNIP
            AppConstants.DRIVER_ZINK -> AppConstants.DRIVER_ZINK
            else -> if (isAdreno) AppConstants.DRIVER_TURNIP else AppConstants.DRIVER_VORTEK
        }

        return ResolvedGpuDriver(
            selectedMode = selectedMode,
            effectiveDriver = effective,
            vkDeviceName = if (rendererName.isNotEmpty()) rendererName else "ARM64 Vulkan GPU",
            vkApiVersionFormatted = apiFormatted
        )
    }

    suspend fun importAndInitializeRootFs(
        context: Context,
        rootFsUri: Uri,
        onProgressPercent: (Int) -> Unit
    ): Result<RootFsVerification> = withContext(Dispatchers.IO) {
        try {
            onProgressPercent(2)
            val rootFs = RootFS.find(context)
            val rootDir = rootFs.rootDir

            if (rootDir.exists()) {
                FileUtils.clear(rootDir)
            } else {
                rootDir.mkdirs()
            }

            val processedFiles = AtomicLong(0L)
            val extractedOk = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                rootFsUri,
                rootDir
            ) { file, _ ->
                val count = processedFiles.incrementAndGet()
                val progress = (5 + ((count * 55L) / 14500L).toInt()).coerceIn(5, 60)
                onProgressPercent(progress)
                file
            }

            if (!extractedOk) {
                return@withContext Result.failure(
                    IllegalStateException("Arsip rootfs.tzst gagal diekstrak atau format rusak.")
                )
            }

            onProgressPercent(65)
            val patchesOk = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "rootfs_patches.tzst",
                rootDir
            )
            if (!patchesOk) {
                return@withContext Result.failure(
                    IllegalStateException("Gagal memasang rootfs_patches.tzst ke sistem dasar.")
                )
            }

            onProgressPercent(72)
            val box64Version = DefaultVersion.BOX64
            val box64Ok = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "box64/box64-$box64Version.tzst",
                rootDir
            )
            if (!box64Ok) {
                return@withContext Result.failure(
                    IllegalStateException("Gagal memasang Box64 $box64Version.")
                )
            }

            onProgressPercent(80)
            val homeXUserDir = File(rootDir, "home/xuser")
            homeXUserDir.mkdirs()
            val containerOk = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "container_pattern.tzst",
                homeXUserDir
            )
            if (!containerOk) {
                return@withContext Result.failure(
                    IllegalStateException("Gagal mengekstrak container_pattern.tzst.")
                )
            }

            onProgressPercent(88)
            val wineRelPath = resolveWineRelativePath(rootDir)
            rootFs.winePath = "/$wineRelPath"

            onProgressPercent(93)
            finalizeRootFsInstallation(context, rootFs, rootDir, wineRelPath)

            onProgressPercent(100)
            val verification = verifyRootFs(context)
            if (!verification.isInstalled) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Arsip diekstrak tetapi biner Wine ($wineRelPath/bin/wine) atau Box64 tidak ditemukan."
                    )
                )
            }
            Result.success(verification)
        } catch (e: Throwable) {
            Log.e(TAG, "RootFS extraction failed", e)
            Result.failure(e)
        }
    }

    private fun linkCommonWineDlls(
        context: Context,
        rootDir: File,
        homeXUserDir: File,
        wineRelPath: String
    ) {
        val jsonText = FileUtils.readString(context, "common_dlls.json") ?: return
        val json = JSONObject(jsonText)

        // common_dlls.json uses keys "system32" (64-bit x86_64-windows) and "syswow64" (32-bit i386-windows)
        val archMappings = listOf(
            Triple("system32", "system32", "$wineRelPath/lib/wine/x86_64-windows"),
            Triple("syswow64", "syswow64", "$wineRelPath/lib/wine/i386-windows")
        )

        for ((jsonKey, targetFolder, sourceRelPath) in archMappings) {
            val dllArray = json.optJSONArray(jsonKey) ?: continue
            val srcDir = File(rootDir, sourceRelPath)
            val dstDir = File(homeXUserDir, ".wine/drive_c/windows/$targetFolder")
            dstDir.mkdirs()

            for (i in 0 until dllArray.length()) {
                val dllName = dllArray.optString(i)
                if (dllName.isNotEmpty()) {
                    val srcFile = File(srcDir, dllName)
                    val dstFile = File(dstDir, dllName)
                    if (srcFile.exists() && !dstFile.exists()) {
                        FileUtils.copy(srcFile, dstFile)
                    }
                }
            }
        }
    }

    suspend fun prepareSessionEnvironment(
        context: Context,
        gtaSaDirPath: String,
        nickname: String,
        gpuDriverMode: String,
        dxvkVersion: String
    ): Result<ResolvedGpuDriver> = withContext(Dispatchers.IO) {
        try {
            val rootFsCheck = verifyRootFs(context)
            if (!rootFsCheck.isInstalled) {
                return@withContext Result.failure(
                    IllegalStateException("RootFS belum terpasang atau belum lengkap.")
                )
            }

            if (!StoragePathResolver.hasStoragePermission(context)) {
                return@withContext Result.failure(
                    IllegalStateException("Izin Akses Semua Berkas belum diberikan.")
                )
            }

            val gtaCheck = verifyGtaSaDirectory(gtaSaDirPath, context)
            if (!gtaCheck.isReadyForSingleplayer) {
                return@withContext Result.failure(
                    IllegalStateException("Direktori GTA SA tidak valid atau gta_sa.exe tidak ditemukan.")
                )
            }

            val rootFs = RootFS.find(context)
            val rootDir = rootFs.rootDir
            val homeXUserDir = File(rootDir, "home/xuser")
            val winePrefixDir = File(homeXUserDir, ".wine")
            val systemRegFile = File(winePrefixDir, "system.reg")
            val userRegFile = File(winePrefixDir, "user.reg")

            setupDosDevices(winePrefixDir, gtaCheck.directoryPath)

            val resolvedDriver = resolveGpuDriver(context, gpuDriverMode)
            installGraphicsDriverIfNeeded(context, rootDir, resolvedDriver.effectiveDriver)
            RootFsPathRelocator.relocateRootFsIfNeeded(
                context = context,
                rootDir = rootDir,
                forceFullScan = false
            )
            installDxvkIfNeeded(context, winePrefixDir, dxvkVersion)
            installWinComponentsIfNeeded(context, winePrefixDir)

            val dxvkConfContent = """
                d3d9.maxFrameLatency = 1
                d3d9.deferSurfaceCreation = True
                d3d9.samplerAnisotropy = 0
                dxvk.numCompilerThreads = 1
                dxvk.useRawSsbo = False
            """.trimIndent()
            val dxvkConfHome = File(homeXUserDir, "dxvk.conf")
            dxvkConfHome.writeText(dxvkConfContent)
            try {
                val dxvkConfGame = File(gtaCheck.directoryPath, "dxvk.conf")
                if (dxvkConfGame.parentFile?.canWrite() == true) {
                    dxvkConfGame.writeText(dxvkConfContent)
                }
            } catch (_: Throwable) {}

            val winHandlerExe = File(winePrefixDir, "drive_c/windows/winhandler.exe")
            if (!winHandlerExe.exists() || winHandlerExe.length() == 0L) {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "rootfs_patches.tzst",
                    rootDir
                )
            }
            File(winePrefixDir, "drive_c/users/xuser/Documents/GTA San Andreas User Files").mkdirs()
            File(winePrefixDir, "drive_c/users/xuser/My Documents/GTA San Andreas User Files").mkdirs()

            configureWineRegistries(
                systemRegFile = systemRegFile,
                userRegFile = userRegFile,
                nickname = nickname.trim().ifEmpty { "Player" }
            )

            Result.success(resolvedDriver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepare session environment", e)
            Result.failure(e)
        }
    }

    fun setupDosDevices(winePrefixDir: File, gtaSaDirPath: String) {
        val dosDevicesDir = File(winePrefixDir, "dosdevices")
        dosDevicesDir.mkdirs()

        val cLink = File(dosDevicesDir, "c:")
        val zLink = File(dosDevicesDir, "z:")
        val dLink = File(dosDevicesDir, "d:")

        recreateSymlink("../drive_c", cLink)
        recreateSymlink("/", zLink)
        if (gtaSaDirPath.isNotBlank()) {
            val targetDir = File(gtaSaDirPath)
            recreateSymlink(targetDir.absolutePath, dLink)
            val exists = dLink.exists() || FileUtils.isSymlink(dLink)
            UniversalCrashLogger.logCheckpoint(
                "RuntimeEnvironmentManager",
                "setupDosDevices: d: -> ${targetDir.absolutePath} (symlinkExists=$exists, targetExists=${targetDir.exists()})"
            )
        }
    }

    private fun recreateSymlink(target: String, linkFile: File) {
        try {
            if (linkFile.exists() || FileUtils.isSymlink(linkFile)) {
                linkFile.delete()
            }
            Os.symlink(target, linkFile.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $target", e)
        }
    }

    private fun installGraphicsDriverIfNeeded(
        context: Context,
        rootDir: File,
        effectiveDriver: String
    ) {
        val markerFile = File(rootDir, "home/xuser/$INSTALLED_DRIVER_MARKER")
        val currentInstalled = if (markerFile.isFile) markerFile.readText().trim() else ""
        if (currentInstalled == effectiveDriver) return

        when (effectiveDriver) {
            AppConstants.DRIVER_VORTEK -> {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "graphics_driver/vortek-${DefaultVersion.VORTEK}.tzst",
                    rootDir
                )
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "graphics_driver/zink-${DefaultVersion.ZINK}.tzst",
                    rootDir
                )
            }
            AppConstants.DRIVER_TURNIP -> {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "graphics_driver/turnip-${DefaultVersion.TURNIP}.tzst",
                    rootDir
                )
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "graphics_driver/zink-${DefaultVersion.ZINK}.tzst",
                    rootDir
                )
            }
            AppConstants.DRIVER_ZINK -> {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "graphics_driver/zink-${DefaultVersion.ZINK}.tzst",
                    rootDir
                )
            }
        }
        markerFile.writeText(effectiveDriver)
    }

    private fun installDxvkIfNeeded(
        context: Context,
        winePrefixDir: File,
        dxvkVersion: String
    ) {
        val markerFile = File(winePrefixDir, INSTALLED_DXVK_MARKER)
        val currentVersion = if (markerFile.isFile) markerFile.readText().trim() else ""
        if (currentVersion == dxvkVersion) return

        val windowsDir = File(winePrefixDir, "drive_c/windows")
        val extracted = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            context,
            "dxwrapper/dxvk-$dxvkVersion.tzst",
            windowsDir
        )
        if (extracted) {
            markerFile.writeText(dxvkVersion)
        }
    }

    private fun installWinComponentsIfNeeded(
        context: Context,
        winePrefixDir: File
    ) {
        val markerFile = File(winePrefixDir, INSTALLED_WINCOMPONENTS_MARKER)
        if (markerFile.isFile) return

        val windowsDir = File(winePrefixDir, "drive_c/windows")
        val archives = listOf(
            "wincomponents/directsound.tzst",
            "wincomponents/wmdecoder.tzst",
            "wincomponents/directshow.tzst",
            "wincomponents/vcrun2005.tzst",
            "wincomponents/vcrun2010.tzst"
        )
        for (archive in archives) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                archive,
                windowsDir
            )
        }
        markerFile.writeText("1")
    }

    private fun configureWineRegistries(
        systemRegFile: File,
        userRegFile: File,
        nickname: String
    ) {
        if (systemRegFile.isFile) {
            WineRegistryEditor(systemRegFile).use { reg ->
                for (service in UNNEEDED_WINE_SERVICES) {
                    reg.setDwordValue("System\\CurrentControlSet\\Services\\$service", "Start", 4)
                }
                reg.setStringValue("Software\\Classes\\.reg", null, "REGfile")
                reg.setStringValue(
                    "Software\\Classes\\REGfile\\Shell\\Open\\command",
                    null,
                    "C:\\windows\\regedit.exe /C \"%1\""
                )
                reg.setStringValue("Software\\Wine\\Drives", "c:", "hd")
                reg.setStringValue("Software\\Wine\\Drives", "d:", "hd")
                reg.setStringValue("Software\\Wine\\Drives", "z:", "network")
            }
        }

        if (userRegFile.isFile) {
            WineRegistryEditor(userRegFile).use { reg ->
                reg.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa")
                reg.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11")

                val dllOverridesKey = "Software\\Wine\\DllOverrides"
                reg.setStringValue(dllOverridesKey, "d3d8", "native,builtin")
                reg.setStringValue(dllOverridesKey, "d3d9", "native,builtin")
                reg.setStringValue(dllOverridesKey, "dxgi", "native,builtin")
                reg.setStringValue(dllOverridesKey, "d3d10core", "native,builtin")
                reg.setStringValue(dllOverridesKey, "d3d11", "native,builtin")
                reg.setStringValue(dllOverridesKey, "dsound", "native,builtin")
                reg.setStringValue(dllOverridesKey, "dinput8", "native,builtin")
                reg.setStringValue(dllOverridesKey, "quartz", "native,builtin")
                reg.setStringValue(dllOverridesKey, "devenum", "native,builtin")
                reg.setStringValue(dllOverridesKey, "wmadmod", "native,builtin")
                reg.setStringValue(dllOverridesKey, "wmvdecod", "native,builtin")
                reg.setStringValue(dllOverridesKey, "msvcr80", "native,builtin")
                reg.setStringValue(dllOverridesKey, "msvcp80", "native,builtin")
                reg.setStringValue(dllOverridesKey, "msvcr100", "native,builtin")
                reg.setStringValue(dllOverridesKey, "msvcp100", "native,builtin")

                reg.setStringValue("Software\\SAMP", "PlayerName", nickname)
                reg.setStringValue("Software\\SAMP", "gta_sa_exe", "D:\\gta_sa.exe")

                reg.setStringValue("Software\\Wine\\Direct3D", "csmt", "0x3")
                reg.setStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", "2048")
                reg.setStringValue("Software\\Wine\\Direct3D", "OffscreenRenderingMode", "fbo")
            }
        }
    }

    fun buildSessionEnvVars(
        rootDir: File,
        effectiveDriver: String,
        dxvkVersion: String
    ): EnvVars {
        val envVars = EnvVars()
        val dynCacheDir = File(rootDir, "home/xuser/.cache/box64_dynacache")
        val mesaCacheDir = File(rootDir, "home/xuser/.cache/mesa_shader_cache")
        val dxvkCacheDir = File(rootDir, "home/xuser/.cache/dxvk_state_cache")
        dynCacheDir.mkdirs()
        mesaCacheDir.mkdirs()
        dxvkCacheDir.mkdirs()

        envVars.put("WINEESYNC", "1")
        envVars.put("WINEDEBUG", "-all,err+all")
        envVars.put("WINEARCH", "win64")
        envVars.put("WINEDLLOVERRIDES", "d3d8,d3d9,dxgi,dsound,dinput8=n,b;winemenubuilder.exe=d;mscoree,mshtml=d")

        envVars.put("BOX64_DYNAREC", "1")
        envVars.put("BOX64_DYNACACHE", "1")
        envVars.put("BOX64_DYNACACHE_FOLDER", dynCacheDir.absolutePath)
        envVars.put("BOX64_DYNACACHE_MIN", "128")
        envVars.put("BOX64_LOG", "0")
        envVars.put("BOX64_NOBANNER", "1")
        envVars.put("BOX64_SHOWSEGV", "1")
        envVars.put("BOX64_SHOWBT", "1")

        envVars.put("MESA_SHADER_CACHE_DISABLE", "false")
        envVars.put("MESA_SHADER_CACHE_DIR", mesaCacheDir.absolutePath)
        envVars.put("MESA_SHADER_CACHE_MAX_SIZE", "512M")
        envVars.put("MESA_NO_ERROR", "1")
        envVars.put("MESA_GL_VERSION_OVERRIDE", "4.6")
        envVars.put("MESA_GLES_VERSION_OVERRIDE", "3.2")
        envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate")
        envVars.put("ZINK_DESCRIPTORS", "lazy")

        envVars.put("DXVK_HUD", "0")
        envVars.put("DXVK_LOG_LEVEL", "warn")
        envVars.put("DXVK_STATE_CACHE", "1")
        envVars.put("DXVK_STATE_CACHE_PATH", dxvkCacheDir.absolutePath)
        envVars.put("DXVK_CONFIG_FILE", File(rootDir, "home/xuser/dxvk.conf").absolutePath)
        if (dxvkVersion.startsWith("1.")) {
            envVars.put("DXVK_ASYNC", "1")
        }

        val icdDir = File(rootDir, "usr/share/vulkan/icd.d").absolutePath
        when (effectiveDriver) {
            AppConstants.DRIVER_VORTEK -> {
                envVars.put("GALLIUM_DRIVER", "zink")
                val vortekIcdAarch64 = File(icdDir, "vortek_icd.aarch64.json")
                val vortekIcdPath = if (vortekIcdAarch64.isFile) {
                    vortekIcdAarch64.absolutePath
                } else {
                    "$icdDir/vortek_icd.aarch64.json"
                }
                envVars.put("VK_ICD_FILENAMES", vortekIcdPath)
                envVars.put("VK_DRIVER_FILES", vortekIcdPath)
            }
            AppConstants.DRIVER_TURNIP -> {
                envVars.put("GALLIUM_DRIVER", "zink")
                val turnipIcdPath = "$icdDir/freedreno_icd.aarch64.json"
                envVars.put("VK_ICD_FILENAMES", turnipIcdPath)
                envVars.put("VK_DRIVER_FILES", turnipIcdPath)
                envVars.put("TU_DEBUG", "noconform")
            }
            AppConstants.DRIVER_ZINK -> {
                envVars.put("GALLIUM_DRIVER", "zink")
            }
        }

        return envVars
    }

    fun buildGuestLaunchCommand(
        launchMode: String,
        gtaCheck: GtaSaDirectoryVerification,
        nickname: String,
        serverAddress: String,
        serverPassword: String
    ): String {
        val cleanNick = nickname.trim().ifEmpty { "Player" }
        val targetExe = gtaCheck.actualExeName.ifBlank { "gta_sa.exe" }
        val targetSampExe = gtaCheck.actualSampExeName.ifBlank { "samp.exe" }
        val dosTargetExe = if (targetExe.contains(" ")) "\"D:\\$targetExe\"" else "D:\\$targetExe"
        val dosTargetSampExe = if (targetSampExe.contains(" ")) "\"D:\\$targetSampExe\"" else "D:\\$targetSampExe"

        return if (launchMode == AppConstants.MODE_SAMP && serverAddress.isNotBlank()) {
            val parts = serverAddress.trim().split(":")
            val host = parts[0].trim()
            val port = parts.getOrNull(1)?.trim()?.ifEmpty { "7777" } ?: "7777"
            val passArg = if (serverPassword.isNotBlank()) " -z ${serverPassword.trim()}" else ""
            if (gtaCheck.sampExeExists) {
                "wine explorer /desktop=shell,${AppConstants.RES_1280_720} $dosTargetSampExe $host:$port -n $cleanNick$passArg"
            } else {
                "wine explorer /desktop=shell,${AppConstants.RES_1280_720} $dosTargetExe -c -n $cleanNick -h $host -p $port$passArg"
            }
        } else {
            "wine explorer /desktop=shell,${AppConstants.RES_1280_720} $dosTargetExe"
        }
    }
}
