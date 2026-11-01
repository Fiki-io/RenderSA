package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import com.example.diagnostic.CrashReportData
import com.example.diagnostic.UniversalCrashLogger
import com.example.ui.components.UniversalErrorHoldScreen
import com.example.ui.theme.RenderSATheme

class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UniversalCrashLogger.initialize(applicationContext, isCrashReporterProcess = true)

        val persisted = UniversalCrashLogger.loadPersistedCrashReport(applicationContext)
        val fallbackTitle = intent.getStringExtra(EXTRA_CRASH_TITLE)
            ?: "Error Fatal Tertangkap"
        val fallbackCategory = intent.getStringExtra(EXTRA_CRASH_CATEGORY)
            ?: "CRASH DIAGNOSTIC"
        val fallbackSummary = intent.getStringExtra(EXTRA_CRASH_SUMMARY)
            ?: "Aplikasi ditahan agar laporan error dapat diperiksa dan disalin."

        val reportData = persisted ?: CrashReportData(
            timestamp = "",
            category = fallbackCategory,
            title = fallbackTitle,
            summary = fallbackSummary,
            primaryErrorSection = fallbackSummary,
            processOutputSection = "",
            checkpointsSection = "",
            rootFsDiagnosticSection = "",
            logcatSection = "",
            fullReportText = "$fallbackCategory\n$fallbackTitle\n$fallbackSummary"
        )

        setContent {
            RenderSATheme {
                BackHandler {
                    returnToMainLauncher()
                }

                UniversalErrorHoldScreen(
                    report = reportData,
                    closeActionLabel = stringResource(R.string.action_return_launcher),
                    onCloseOrReturn = {
                        returnToMainLauncher()
                    },
                    onClearLog = {
                        UniversalCrashLogger.clearActiveCrashReport(this@CrashReportActivity)
                        returnToMainLauncher()
                    }
                )
            }
        }
    }

    private fun returnToMainLauncher() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_CRASH_TITLE = "extra_crash_title"
        const val EXTRA_CRASH_CATEGORY = "extra_crash_category"
        const val EXTRA_CRASH_SUMMARY = "extra_crash_summary"
    }
}
