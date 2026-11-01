package com.example.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.winlator.core.FileUtils
import java.io.File
import java.io.FileOutputStream

object StoragePathResolver {

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildManageStorageIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
    }

    fun resolveTreeUriToFilePath(context: Context, treeUri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":")
            if (split.isNotEmpty()) {
                val type = split[0]
                val relativePath = if (split.size > 1) split[1] else ""
                if ("primary".equals(type, ignoreCase = true)) {
                    val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
                    return if (relativePath.isEmpty()) primaryRoot else "$primaryRoot/$relativePath"
                } else {
                    val storageCandidate = File("/storage/$type/$relativePath")
                    if (storageCandidate.exists()) {
                        return storageCandidate.absolutePath
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val fallbackPath = FileUtils.getFilePathFromUri(treeUri)
        if (!fallbackPath.isNullOrBlank()) {
            return fallbackPath
        }
        return null
    }

    fun findGtaSaRootDirectory(baseDir: File, maxDepth: Int = 2): File {
        if (!baseDir.exists() || !baseDir.isDirectory) return baseDir
        if (containsGtaSaExecutable(baseDir)) return baseDir
        if (maxDepth <= 0) return baseDir

        val subDirs = baseDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (sub in subDirs) {
            if (containsGtaSaExecutable(sub)) {
                return sub
            }
        }
        if (maxDepth >= 2) {
            for (sub in subDirs) {
                val nested = sub.listFiles()?.filter { it.isDirectory }.orEmpty()
                for (deep in nested) {
                    if (containsGtaSaExecutable(deep)) {
                        return deep
                    }
                }
            }
        }
        return baseDir
    }

    private fun containsGtaSaExecutable(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        return children.any {
            it.isFile && (
                it.name.equals("gta_sa.exe", ignoreCase = true) ||
                    it.name.equals("gta-sa.exe", ignoreCase = true)
                )
        }
    }

    fun resolveOrMirrorGtaSaDirectory(context: Context, treeUri: Uri): String? {
        val directPath = resolveTreeUriToFilePath(context, treeUri)
        if (directPath != null) {
            val dir = File(directPath)
            if (dir.exists() && dir.isDirectory) {
                return findGtaSaRootDirectory(dir).absolutePath
            }
            return directPath
        }

        val docTree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!docTree.exists() || !docTree.isDirectory) return null

        val mirrorRoot = File(context.filesDir, "gtasa_gamedata")
        mirrorRoot.mkdirs()
        copyDocumentTreeRecursively(context, docTree, mirrorRoot)
        return findGtaSaRootDirectory(mirrorRoot).absolutePath
    }

    private fun copyDocumentTreeRecursively(
        context: Context,
        sourceDir: DocumentFile,
        targetDir: File
    ) {
        targetDir.mkdirs()
        for (child in sourceDir.listFiles()) {
            val name = child.name ?: continue
            val destFile = File(targetDir, name)
            if (child.isDirectory) {
                copyDocumentTreeRecursively(context, child, destFile)
            } else if (child.isFile) {
                val expectedLen = child.length()
                if (destFile.isFile && destFile.length() == expectedLen && expectedLen > 0L) {
                    continue
                }
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }
        }
    }

    fun formatBytesReadable(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(java.util.Locale.US, "%.2f MB", bytes / mb)
            bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
