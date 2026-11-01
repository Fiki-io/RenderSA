package com.example.diagnostic

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.CrashReportActivity
import com.example.util.StoragePathResolver
import com.winlator.xenvironment.RootFS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class CrashReportData(
    val timestamp: String,
    val category: String,
    val title: String,
    val summary: String,
    val primaryErrorSection: String,
    val processOutputSection: String,
    val checkpointsSection: String,
    val rootFsDiagnosticSection: String,
    val logcatSection: String,
    val fullReportText: String
)

object UniversalCrashLogger {
    private const val TAG = "UniversalCrashLogger"
    private const val LOG_DIR_NAME = "logs"
    private const val NATIVE_CRASH_PENDING_FILE = "native_crash_pending.log"
    private const val SESSION_ACTIVE_MARKER_FILE = "session_active_marker.log"
    private const val SESSION_LATEST_LOG_FILE = "session_latest.log"
    private const val CRASH_REPORT_FILE = "crash_report.log"
    private const val CRASH_META_FILE = "crash_meta.txt"

    private const val MAX_CHECKPOINT_LINES = 250
    private const val MAX_PROCESS_OUTPUT_LINES = 500

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var nativeLibraryLoaded = false

    @Volatile
    private var inSessionErrorCallback: ((CrashReportData) -> Unit)? = null

    private val checkpointLines = CopyOnWriteArrayList<String>()
    private val processOutputLines = CopyOnWriteArrayList<String>()
    private val fileLock = Any()

    private val _activeCrashReport = MutableStateFlow<CrashReportData?>(null)
    val activeCrashReport: StateFlow<CrashReportData?> = _activeCrashReport.asStateFlow()

    @JvmStatic
    private external fun nativeInstallSignalHandlers(crashFilePath: String, sessionLogPath: String)

    @JvmStatic
    private external fun nativeSetCheckpoint(component: String, message: String)

    @JvmStatic
    private external fun nativeInspectBinaryPath(path: String): String

    @JvmStatic
    private external fun nativeInspectMountAndSelinux(): String

    fun initialize(context: Context, isCrashReporterProcess: Boolean = false) {
        val appCtx = context.applicationContext
        appContext = appCtx

        val logDir = getLogDir(appCtx)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        if (!isCrashReporterProcess) {
            try {
                System.loadLibrary("winlator")
                nativeLibraryLoaded = true
                val pendingNativeFile = File(logDir, NATIVE_CRASH_PENDING_FILE)
                val sessionLogFile = File(logDir, SESSION_LATEST_LOG_FILE)
                nativeInstallSignalHandlers(
                    pendingNativeFile.absolutePath,
                    sessionLogFile.absolutePath
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal memuat libwinlator.so saat inisialisasi logger", t)
            }

            installJvmUncaughtExceptionHandler(appCtx)
            checkAndPromotePendingCrash(appCtx)
        } else {
            loadPersistedCrashReport(appCtx)
        }
    }

    fun registerInSessionErrorCallback(callback: ((CrashReportData) -> Unit)?) {
        inSessionErrorCallback = callback
    }

    private fun getLogDir(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun nowFormatted(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date())
    }

    fun beginGameSession(launchMode: String, command: String, driver: String, dxvk: String, resolution: String) {
        val ctx = appContext ?: return
        checkpointLines.clear()
        processOutputLines.clear()

        synchronized(fileLock) {
            val logDir = getLogDir(ctx)
            val sessionFile = File(logDir, SESSION_LATEST_LOG_FILE)
            val markerFile = File(logDir, SESSION_ACTIVE_MARKER_FILE)
            val header = buildString {
                appendLine("=== SESI RENDERSA DIMULAI ===")
                appendLine("Waktu Mulai : ${nowFormatted()}")
                appendLine("Mode        : $launchMode")
                appendLine("Driver GPU  : $driver | DXVK: $dxvk | Resolusi: $resolution")
                appendLine("Perintah    : $command")
                appendLine("PID Proses  : ${Process.myPid()}")
            }
            try {
                sessionFile.writeText(header)
                markerFile.writeText(header)
            } catch (_: Throwable) {
            }
        }

        logCheckpoint("Session", "Memulai sesi $launchMode ($driver, DXVK $dxvk, $resolution)")
    }

    fun markSessionCleanExit() {
        val ctx = appContext ?: return
        logCheckpoint("Session", "Sesi dihentikan secara normal oleh pengguna")
        synchronized(fileLock) {
            try {
                val markerFile = File(getLogDir(ctx), SESSION_ACTIVE_MARKER_FILE)
                if (markerFile.exists()) {
                    markerFile.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun logCheckpoint(component: String, message: String) {
        val line = "[${nowFormatted()}] [$component] $message"
        checkpointLines.add(line)
        while (checkpointLines.size > MAX_CHECKPOINT_LINES) {
            checkpointLines.removeAt(0)
        }
        Log.i(TAG, "[$component] $message")

        if (nativeLibraryLoaded) {
            try {
                nativeSetCheckpoint(component, message)
            } catch (_: Throwable) {
            }
        }

        val ctx = appContext ?: return
        synchronized(fileLock) {
            try {
                val sessionFile = File(getLogDir(ctx), SESSION_LATEST_LOG_FILE)
                FileOutputStream(sessionFile, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun logProcessOutput(source: String, rawLine: String) {
        val trimmed = rawLine.trimEnd()
        if (trimmed.isEmpty()) return
        val line = "[${nowFormatted()}] [$source] $trimmed"
        processOutputLines.add(line)
        while (processOutputLines.size > MAX_PROCESS_OUTPUT_LINES) {
            processOutputLines.removeAt(0)
        }
        Log.d(TAG, "[$source] $trimmed")

        val ctx = appContext ?: return
        synchronized(fileLock) {
            try {
                val sessionFile = File(getLogDir(ctx), SESSION_LATEST_LOG_FILE)
                FileOutputStream(sessionFile, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun recordProcessStartFailure(
        command: String,
        workingDir: String?,
        envSummary: String,
        error: Throwable
    ): CrashReportData {
        val stackStr = stackTraceToString(error)
        val primary = buildString {
            appendLine("Komponen      : ProcessHelper / Eksekutor Biner Guest (Box64 & Wine)")
            appendLine("Status        : Gagal menjalankan ProcessBuilder.start()")
            appendLine("Command       : $command")
            appendLine("Working Dir   : ${workingDir ?: "null"}")
            appendLine("Exception     : ${error.javaClass.name}: ${error.message}")
            appendLine()
            appendLine("--- Analisis Teknis Penyebab ---")
            val msg = error.message.orEmpty()
            when {
                msg.contains("error=13") || msg.contains("Permission denied", ignoreCase = true) -> {
                    appendLine("Kernel Android menolak eksekusi langsung biner di direktori data aplikasi (errno=13 EACCES / Permission denied).")
                    appendLine("Penyebab umum pada Android 10+ (targetSdk >= 29): kebijakan SELinux untrusted_app memblokir execve() langsung pada berkas bertipe app_data_file di /data/user/0/<pkg>/files/rootfs.")
                }
                msg.contains("error=2") || msg.contains("No such file or directory", ignoreCase = true) -> {
                    appendLine("Biner target atau ELF Dynamic Linker (PT_INTERP, misalnya /lib/ld-linux-aarch64.so.1) tidak ditemukan di path host Android (errno=2 ENOENT).")
                    appendLine("Periksa bagian DIAGNOSTIK ROOTFS & ELF PT_INTERP di bawah untuk melihat apakah PT_INTERP biner box64/wine menunjuk ke path absolut yang tidak ada di luar chroot/proot.")
                }
                else -> {
                    appendLine("Gagal memulai proses native guest. Lihat stack trace dan diagnostik biner di bawah.")
                }
            }
            appendLine()
            appendLine("--- Environment Variables Utama ---")
            appendLine(envSummary)
            appendLine()
            appendLine("--- Stack Trace Lengkap ---")
            appendLine(stackStr)
        }

        val report = buildAndPersistReport(
            category = "EKSEKUSI BINER BOX64 / WINE GAGAL",
            title = "Gagal Menjalankan Proses Box64 / Wine",
            summary = "${error.javaClass.simpleName}: ${error.message ?: "Gagal memulai proses"}",
            primaryErrorSection = primary
        )
        notifyInSessionOrPublish(report)
        return report
    }

    @JvmStatic
    fun recordProcessUnexpectedExit(
        command: String,
        workingDir: String?,
        pid: Int,
        exitCode: Int,
        durationMs: Long,
        envSummary: String
    ): CrashReportData {
        val exitExplanation = explainExitCode(exitCode, durationMs)
        val primary = buildString {
            appendLine("Komponen      : GuestProgramLauncherComponent (Box64 / Wine / GTA SA)")
            appendLine("PID Proses    : $pid")
            appendLine("Kode Keluar   : $exitCode ($exitExplanation)")
            appendLine("Durasi Hidup  : ${durationMs} ms")
            appendLine("Command       : $command")
            appendLine("Working Dir   : ${workingDir ?: "null"}")
            appendLine()
            appendLine("--- Environment Variables Utama ---")
            appendLine(envSummary)
        }

        val report = buildAndPersistReport(
            category = "PROSES WINE / BOX64 BERHENTI",
            title = "Proses Wine / Box64 Berhenti (Kode: $exitCode)",
            summary = "$exitExplanation (Durasi: ${durationMs} ms). Aplikasi ditahan agar log dapat diperiksa dan disalin.",
            primaryErrorSection = primary
        )
        notifyInSessionOrPublish(report)
        return report
    }

    @JvmStatic
    fun recordComponentFatalError(
        component: String,
        stage: String,
        error: Throwable
    ): CrashReportData {
        val stackStr = stackTraceToString(error)
        val primary = buildString {
            appendLine("Komponen      : $component")
            appendLine("Tahap         : $stage")
            appendLine("Thread        : ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
            appendLine("Exception     : ${error.javaClass.name}: ${error.message}")
            appendLine()
            appendLine("--- Stack Trace Lengkap ---")
            appendLine(stackStr)
        }

        val report = buildAndPersistReport(
            category = "ERROR KOMPONEN $component",
            title = "Error Fatal pada $component ($stage)",
            summary = "${error.javaClass.simpleName}: ${error.message ?: "Tanpa pesan"}",
            primaryErrorSection = primary
        )
        notifyInSessionOrPublish(report)
        return report
    }

    fun generateOnDemandDiagnosticReport(context: Context): CrashReportData {
        appContext = context.applicationContext
        val existing = _activeCrashReport.value
        if (existing != null) return existing

        val persisted = loadPersistedCrashReport(context.applicationContext)
        if (persisted != null) return persisted

        val primary = buildString {
            appendLine("Status        : Laporan Diagnostik Manual (Belum ada crash tercatat pada sesi ini)")
            appendLine("Waktu         : ${nowFormatted()}")
            appendLine("PID Aktif     : ${Process.myPid()}")
        }
        val report = buildAndPersistReport(
            category = "DIAGNOSTIK SISTEM & ROOTFS",
            title = "Laporan Diagnostik Sistem, RootFS & Wine",
            summary = "Pemeriksaan menyeluruh status biner RootFS, ELF Linker, izin penyimpanan, dan log sesi terakhir.",
            primaryErrorSection = primary,
            persistAsCrash = false
        )
        _activeCrashReport.value = report
        return report
    }

    fun clearActiveCrashReport(context: Context) {
        _activeCrashReport.value = null
        synchronized(fileLock) {
            try {
                val logDir = getLogDir(context.applicationContext)
                File(logDir, CRASH_REPORT_FILE).delete()
                File(logDir, CRASH_META_FILE).delete()
                File(logDir, NATIVE_CRASH_PENDING_FILE).delete()
                File(logDir, SESSION_ACTIVE_MARKER_FILE).delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun saveReportToDownloadOrAppStorage(context: Context, report: CrashReportData): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "RenderSA_Log_$stamp.txt"
        return try {
            val downloadDir = File("/storage/emulated/0/Download")
            val targetFile = if (StoragePathResolver.hasStoragePermission(context) &&
                (downloadDir.exists() || downloadDir.mkdirs()) &&
                downloadDir.canWrite()
            ) {
                File(downloadDir, fileName)
            } else {
                val extDir = context.getExternalFilesDir(null) ?: getLogDir(context)
                if (!extDir.exists()) extDir.mkdirs()
                File(extDir, fileName)
            }
            targetFile.writeText(report.fullReportText)
            targetFile.absolutePath
        } catch (t: Throwable) {
            val fallback = File(getLogDir(context), fileName)
            fallback.writeText(report.fullReportText)
            fallback.absolutePath
        }
    }

    private fun explainExitCode(exitCode: Int, durationMs: Long): String {
        return when (exitCode) {
            -1 -> "Gagal mengeksekusi biner (ProcessBuilder gagal memulai proses)"
            0 -> if (durationMs < 8000L) {
                "Proses keluar cepat dengan kode 0 tanpa merender jendela (kemungkinan argumen/path exe tidak ditemukan oleh Wine)"
            } else {
                "Proses selesai dengan kode 0"
            }
            1 -> "Error umum Wine / Box64 (periksa log stdout/stderr Wine & Box64)"
            2 -> "Argumen perintah atau berkas target tidak ditemukan (Exit Code 2)"
            126 -> "Biner tidak dapat dieksekusi (Exit 126: Permission denied / EACCES / Format ELF tidak cocok)"
            127 -> "Biner, ELF Interpreter (PT_INTERP), atau shared library (.so) tidak ditemukan (Exit 127: ENOENT)"
            132 -> "Proses dihentikan oleh sinyal SIGILL (128+4: Instruksi CPU Ilegal)"
            134 -> "Proses dihentikan oleh sinyal SIGABRT (128+6: Abort / Assertion Gagal)"
            135 -> "Proses dihentikan oleh sinyal SIGBUS (128+7: Bus Error / Akses Memori Tidak Selaras)"
            136 -> "Proses dihentikan oleh sinyal SIGFPE (128+8: Kesalahan Aritmatika)"
            137 -> "Proses dihentikan paksa oleh SIGKILL (128+9: Out of Memory / OS Killer)"
            139 -> "Proses mengalami Segmentation Fault SIGSEGV (128+11: Akses Memori Tidak Valid)"
            159 -> "Proses diblokir oleh kebijakan Seccomp Android SIGSYS (128+31: System Call Terlarang)"
            else -> if (exitCode > 128) {
                "Proses dihentikan oleh sinyal POSIX ${exitCode - 128} (Kode $exitCode)"
            } else {
                "Proses berhenti dengan kode keluar non-nol ($exitCode)"
            }
        }
    }

    private fun notifyInSessionOrPublish(report: CrashReportData) {
        _activeCrashReport.value = report
        val callback = inSessionErrorCallback
        if (callback != null) {
            try {
                callback.invoke(report)
            } catch (_: Throwable) {
            }
        }
    }

    private fun installJvmUncaughtExceptionHandler(context: Context) {
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler?.javaClass?.name?.contains("UniversalCrashLogger") == true) {
            return
        }
        defaultExceptionHandler = currentHandler

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackStr = stackTraceToString(throwable)
                val primary = buildString {
                    appendLine("Thread Crash  : ${thread.name} (id=${thread.id}, state=${thread.state})")
                    appendLine("Exception     : ${throwable.javaClass.name}")
                    appendLine("Pesan Error   : ${throwable.message ?: "Tanpa pesan"}")
                    appendLine()
                    appendLine("--- Stack Trace Lengkap ---")
                    appendLine(stackStr)
                }

                val report = buildAndPersistReport(
                    category = "UNCAUGHT JVM / KOTLIN EXCEPTION",
                    title = "Crash Tak Tertangani pada Thread [${thread.name}]",
                    summary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "Exception tanpa pesan"}",
                    primaryErrorSection = primary
                )

                val isMainThread = Looper.getMainLooper().thread === thread
                val sessionCallback = inSessionErrorCallback
                if (!isMainThread && sessionCallback != null) {
                    // Tahan di dalam Activity yang sedang berjalan tanpa mematikan proses
                    _activeCrashReport.value = report
                    sessionCallback.invoke(report)
                    return@setDefaultUncaughtExceptionHandler
                }

                // Jika thread utama (Main Looper) yang mengalami crash, luncurkan CrashReportActivity
                // pada proses terpisah (:crash_reporter) agar aplikasi tetap tampil di layar dan tidak keluar.
                launchStandaloneCrashActivity(context, report)
                Thread.sleep(400L)
            } catch (inner: Throwable) {
                Log.e(TAG, "Gagal memproses uncaught exception", inner)
            } finally {
                Process.killProcess(Process.myPid())
                 kotlin.system.exitProcess(10)
            }
        }
    }

    private fun launchStandaloneCrashActivity(context: Context, report: CrashReportData) {
        try {
            val intent = Intent(context, CrashReportActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(CrashReportActivity.EXTRA_CRASH_TITLE, report.title)
                putExtra(CrashReportActivity.EXTRA_CRASH_CATEGORY, report.category)
                putExtra(CrashReportActivity.EXTRA_CRASH_SUMMARY, report.summary)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal membuka CrashReportActivity", t)
        }
    }

    fun checkAndPromotePendingCrash(context: Context): CrashReportData? {
        val appCtx = context.applicationContext
        appContext = appCtx
        val logDir = getLogDir(appCtx)
        val nativeCrashFile = File(logDir, NATIVE_CRASH_PENDING_FILE)
        val sessionMarkerFile = File(logDir, SESSION_ACTIVE_MARKER_FILE)
        val sessionLatestFile = File(logDir, SESSION_LATEST_LOG_FILE)

        if (nativeCrashFile.isFile && nativeCrashFile.length() > 0L) {
            val nativeDump = try {
                nativeCrashFile.readText()
            } catch (_: Throwable) {
                "Gagal membaca berkas native_crash_pending.log"
            }
            val prevSessionLog = if (sessionLatestFile.isFile) {
                try {
                    sessionLatestFile.readText()
                } catch (_: Throwable) {
                    ""
                }
            } else ""

            try {
                nativeCrashFile.delete()
                sessionMarkerFile.delete()
            } catch (_: Throwable) {
            }

            val firstSignalLine = nativeDump.lineSequence()
                .firstOrNull { it.startsWith("Sinyal") }
                ?.substringAfter(":")
                ?.trim()
                ?: "Sinyal Fatal Native C/C++"

            val primary = buildString {
                appendLine(nativeDump)
                if (prevSessionLog.isNotBlank()) {
                    appendLine()
                    appendLine("--- LOG SESI SEBELUM CRASH NATIVE ---")
                    appendLine(prevSessionLog)
                }
            }

            val report = buildAndPersistReport(
                category = "CRASH NATIVE C/C++ (JNI / SIGNAL)",
                title = "Crash Native C/C++ Terdeteksi ($firstSignalLine)",
                summary = "Proses dihentikan oleh sinyal hardware/OS ($firstSignalLine). Aplikasi memulihkan log crash secara otomatis.",
                primaryErrorSection = primary
            )
            _activeCrashReport.value = report
            return report
        }

        if (sessionMarkerFile.isFile && sessionMarkerFile.length() > 0L) {
            val markerContent = try {
                sessionMarkerFile.readText()
            } catch (_: Throwable) {
                ""
            }
            val prevSessionLog = if (sessionLatestFile.isFile) {
                try {
                    sessionLatestFile.readText()
                } catch (_: Throwable) {
                    ""
                }
            } else ""

            try {
                sessionMarkerFile.delete()
            } catch (_: Throwable) {
            }

            val exitReasonInfo = inspectHistoricalProcessExitReasons(appCtx)
            val primary = buildString {
                appendLine("Sesi permainan sebelumnya berhenti mendadak tanpa keluar secara normal.")
                appendLine()
                appendLine("--- Informasi Sesi Terakhir ---")
                appendLine(markerContent)
                if (exitReasonInfo.isNotBlank()) {
                    appendLine()
                    appendLine("--- Riwayat Alasan Berhenti Proses Sistem (ActivityManager HistoricalExitReasons) ---")
                    appendLine(exitReasonInfo)
                }
                if (prevSessionLog.isNotBlank()) {
                    appendLine()
                    appendLine("--- Rekaman Log Sesi Terakhir ---")
                    appendLine(prevSessionLog)
                }
            }

            val report = buildAndPersistReport(
                category = "SESI BERHENTI MENDADAK / FORCE KILL",
                title = "Sesi Permainan Sebelumnya Berhenti Mendadak",
                summary = "Proses sesi dihentikan oleh sistem atau komponen native. Seluruh jejak sesi dan alasan terminasi kernel telah direkam di bawah.",
                primaryErrorSection = primary
            )
            _activeCrashReport.value = report
            return report
        }

        return loadPersistedCrashReport(appCtx)
    }

    fun loadPersistedCrashReport(context: Context): CrashReportData? {
        val logDir = getLogDir(context.applicationContext)
        val reportFile = File(logDir, CRASH_REPORT_FILE)
        if (!reportFile.isFile || reportFile.length() == 0L) return null

        return try {
            val fullText = reportFile.readText()
            val metaFile = File(logDir, CRASH_META_FILE)
            val metaLines = if (metaFile.isFile) metaFile.readLines() else emptyList()
            val timestamp = metaLines.getOrNull(0) ?: nowFormatted()
            val category = metaLines.getOrNull(1) ?: "LAPORAN ERROR TERSIMPAN"
            val title = metaLines.getOrNull(2) ?: "Laporan Error / Crash Terakhir"
            val summary = metaLines.getOrNull(3) ?: "Salin log di bawah untuk analisis lengkap."

            val data = CrashReportData(
                timestamp = timestamp,
                category = category,
                title = title,
                summary = summary,
                primaryErrorSection = extractSection(fullText, "=== 1. RINGKASAN & DETAIL ERROR FATAL ===", "=== 2."),
                processOutputSection = extractSection(fullText, "=== 2. KELUARAN WINE / BOX64 / XSERVER (STDOUT & STDERR) ===", "=== 3."),
                checkpointsSection = extractSection(fullText, "=== 3. RIWAYAT TAHAPAN EKSEKUSI (CHECKPOINTS) ===", "=== 4."),
                rootFsDiagnosticSection = extractSection(fullText, "=== 4. DIAGNOSTIK ROOTFS, ELF PT_INTERP, IZIN & SELINUX ===", "=== 5."),
                logcatSection = extractSection(fullText, "=== 5. CUPLIKAN LOGCAT SISTEM & NATIVE ===", "=== AKHIR LAPORAN ==="),
                fullReportText = fullText
            )
            _activeCrashReport.value = data
            data
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractSection(fullText: String, startHeader: String, nextHeaderPrefix: String): String {
        val startIdx = fullText.indexOf(startHeader)
        if (startIdx < 0) return fullText
        val contentStart = startIdx + startHeader.length
        val endIdx = fullText.indexOf(nextHeaderPrefix, contentStart)
        return if (endIdx > contentStart) {
            fullText.substring(contentStart, endIdx).trim()
        } else {
            fullText.substring(contentStart).trim()
        }
    }

    private fun buildAndPersistReport(
        category: String,
        title: String,
        summary: String,
        primaryErrorSection: String,
        persistAsCrash: Boolean = true
    ): CrashReportData {
        val timestamp = nowFormatted()
        val ctx = appContext

        val processOutText = if (processOutputLines.isEmpty()) {
            val diskSession = ctx?.let {
                val f = File(getLogDir(it), SESSION_LATEST_LOG_FILE)
                if (f.isFile) f.readText() else ""
            }.orEmpty()
            if (diskSession.isNotBlank()) {
                "Tidak ada baris stdout/stderr baru di memori; menampilkan rekaman disk sesi terakhir:\n$diskSession"
            } else {
                "Tidak ada output stdout/stderr yang keluar dari proses Box64/Wine (proses mungkin gagal dieksekusi sebelum menghasilkan output)."
            }
        } else {
            processOutputLines.joinToString("\n")
        }

        val checkpointsText = if (checkpointLines.isEmpty()) {
            "Belum ada checkpoint terekam di memori."
        } else {
            checkpointLines.joinToString("\n")
        }

        val rootFsDiagText = if (ctx != null) {
            collectRootFsAndSystemDiagnostics(ctx)
        } else {
            "Context aplikasi belum tersedia untuk pemeriksaan RootFS."
        }

        val logcatText = captureRecentLogcat()

        val fullReport = buildString {
            appendLine("======================================================================")
            appendLine("LAPORAN DIAGNOSTIK & ERROR UNIVERSAL RENDERSA")
            appendLine("======================================================================")
            appendLine("Waktu Kejadian : $timestamp")
            appendLine("Kategori       : $category")
            appendLine("Judul          : $title")
            appendLine("Ringkasan      : $summary")
            appendLine()
            appendLine("=== 1. RINGKASAN & DETAIL ERROR FATAL ===")
            appendLine(primaryErrorSection.trim())
            appendLine()
            appendLine("=== 2. KELUARAN WINE / BOX64 / XSERVER (STDOUT & STDERR) ===")
            appendLine(processOutText.trim())
            appendLine()
            appendLine("=== 3. RIWAYAT TAHAPAN EKSEKUSI (CHECKPOINTS) ===")
            appendLine(checkpointsText.trim())
            appendLine()
            appendLine("=== 4. DIAGNOSTIK ROOTFS, ELF PT_INTERP, IZIN & SELINUX ===")
            appendLine(rootFsDiagText.trim())
            appendLine()
            appendLine("=== 5. CUPLIKAN LOGCAT SISTEM & NATIVE ===")
            appendLine(logcatText.trim())
            appendLine("======================================================================")
            appendLine("=== AKHIR LAPORAN ===")
        }

        val reportData = CrashReportData(
            timestamp = timestamp,
            category = category,
            title = title,
            summary = summary,
            primaryErrorSection = primaryErrorSection.trim(),
            processOutputSection = processOutText.trim(),
            checkpointsSection = checkpointsText.trim(),
            rootFsDiagnosticSection = rootFsDiagText.trim(),
            logcatSection = logcatText.trim(),
            fullReportText = fullReport
        )

        if (persistAsCrash && ctx != null) {
            synchronized(fileLock) {
                try {
                    val logDir = getLogDir(ctx)
                    File(logDir, CRASH_REPORT_FILE).writeText(fullReport)
                    File(logDir, CRASH_META_FILE).writeText(
                        listOf(
                            timestamp,
                            category.replace("\n", " "),
                            title.replace("\n", " "),
                            summary.replace("\n", " ")
                        ).joinToString("\n")
                    )
                    File(logDir, SESSION_ACTIVE_MARKER_FILE).delete()
                } catch (_: Throwable) {
                }
            }
        }

        return reportData
    }

    private fun collectRootFsAndSystemDiagnostics(context: Context): String {
        return buildString {
            appendLine("Perangkat      : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE} / ${Build.BOARD})")
            appendLine("SoC / Hardware : ${Build.HARDWARE} | ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Android SDK    : API ${Build.VERSION.SDK_INT} (Release ${Build.VERSION.RELEASE}) | Kernel: ${System.getProperty("os.version")}")
            appendLine("Target SDK App : ${context.applicationInfo.targetSdkVersion}")
            appendLine("NativeLibDir   : ${context.applicationInfo.nativeLibraryDir}")
            appendLine("FilesDir       : ${context.filesDir.absolutePath}")
            appendLine("Izin Storage   : ${if (StoragePathResolver.hasStoragePermission(context)) "DIBERIKAN (All Files Access Aktif)" else "BELUM DIBERIKAN"}")

            if (nativeLibraryLoaded) {
                try {
                    appendLine()
                    appendLine("--- Status Mount Filesystem & SELinux ---")
                    appendLine(nativeInspectMountAndSelinux().trim())
                } catch (t: Throwable) {
                    appendLine("Gagal memeriksa mount/SELinux native: ${t.message}")
                }
            }

            val rootFs = RootFS.find(context)
            val rootDir = rootFs.rootDir
            appendLine()
            appendLine("--- Pemeriksaan Direktori & Biner Inti RootFS ---")
            appendLine("RootFS Dir     : ${rootDir.absolutePath} (Ada: ${rootDir.isDirectory})")

            val keyPaths = listOf(
                File("/data/data/${context.packageName}/l"),
                File("/data/data/${context.packageName}/v"),
                File("/data/data/${context.packageName}/x"),
                File(rootDir, "usr/local/bin/box64"),
                File(rootDir, "usr/bin/box64"),
                File(rootDir, "opt/wine/bin/wine"),
                File(rootDir, "opt/wine/bin/wine64"),
                File(rootDir, "opt/wine/bin/wineserver"),
                File(rootDir, "usr/lib/ld-linux-aarch64.so.1"),
                File(rootDir, "lib/ld-linux-aarch64.so.1"),
                File("/system/bin/linker64"),
                File("/system/bin/sh"),
                File(rootDir, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json"),
                File(rootDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"),
                File(rootDir, "home/xuser/.wine/system.reg"),
                File(rootDir, "home/xuser/.wine/dosdevices/d:")
            )

            for (target in keyPaths) {
                if (nativeLibraryLoaded) {
                    try {
                        appendLine(nativeInspectBinaryPath(target.absolutePath))
                    } catch (t: Throwable) {
                        appendLine("Path: ${target.absolutePath} (Error inspect: ${t.message})")
                    }
                } else {
                    appendLine(
                        "Path: ${target.absolutePath} | Exists=${target.exists()} | CanRead=${target.canRead()} | CanExec=${target.canExecute()} | Len=${target.length()}"
                    )
                }
            }

            // Periksa isi vortek_icd.aarch64.json dan freedreno_icd.aarch64.json jika ada
            val vortekIcd = File(rootDir, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json")
            if (vortekIcd.isFile) {
                try {
                    appendLine()
                    appendLine("--- Isi vortek_icd.aarch64.json ---")
                    appendLine(vortekIcd.readText().trim())
                } catch (_: Throwable) {
                }
            }
            val turnipIcd = File(rootDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
            if (turnipIcd.isFile) {
                try {
                    appendLine()
                    appendLine("--- Isi freedreno_icd.aarch64.json ---")
                    appendLine(turnipIcd.readText().trim())
                } catch (_: Throwable) {
                }
            }

            // Periksa native libraries yang terpasang di nativeLibraryDir
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            if (nativeDir.isDirectory) {
                val libs = nativeDir.listFiles()?.map { "${it.name} (${it.length()} B)" }.orEmpty()
                appendLine()
                appendLine("--- Daftar .so di nativeLibraryDir (${libs.size} berkas) ---")
                appendLine(libs.joinToString(", "))
            }
        }
    }

    private fun inspectHistoricalProcessExitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "API HistoricalProcessExitReasons hanya tersedia di Android 11+ (API 30+)."
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "ActivityManager tidak tersedia."
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (reasons.isEmpty()) {
                "Tidak ada catatan HistoricalProcessExitReasons."
            } else {
                reasons.joinToString("\n") { info ->
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(info.timestamp))
                    "[$time] Process=${info.processName} | PID=${info.pid} | Reason=${info.reason} | Status=${info.status} | Desc=${info.description}"
                }
            }
        } catch (t: Throwable) {
            "Gagal membaca HistoricalProcessExitReasons: ${t.message}"
        }
    }

    private fun captureRecentLogcat(): String {
        return try {
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "220")
                .redirectErrorStream(true)
                .start()
            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    lines.add(l)
                }
            }
            if (lines.isEmpty()) {
                "Logcat kosong atau tidak mengembalikan baris."
            } else {
                lines.takeLast(220).joinToString("\n")
            }
        } catch (t: Throwable) {
            "Gagal mengambil logcat: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
