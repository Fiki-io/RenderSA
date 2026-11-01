package com.example

import android.app.ActivityManager
import android.app.Application
import android.os.Process
import com.example.diagnostic.UniversalCrashLogger

class RenderSAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val isCrashReporterProcess = isCrashReporterProcessName()
        UniversalCrashLogger.initialize(
            context = this,
            isCrashReporterProcess = isCrashReporterProcess
        )
    }

    private fun isCrashReporterProcessName(): Boolean {
        return try {
            val pid = Process.myPid()
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            val procName = am?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                .orEmpty()
            procName.endsWith(":crash_reporter")
        } catch (_: Throwable) {
            false
        }
    }
}
