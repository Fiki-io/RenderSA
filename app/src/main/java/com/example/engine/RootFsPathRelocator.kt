package com.example.engine

import android.content.Context
import android.system.Os
import android.util.Log
import com.example.diagnostic.UniversalCrashLogger
import com.winlator.core.FileUtils
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RootFsPathRelocator {
    private const val TAG = "RootFsPathRelocator"
    private const val WINLATOR_ROOTFS_PREFIX = "/data/data/com.winlator/files/rootfs"
    private const val WINLATOR_NEEDLE = "/data/data/com.winlator"
    private const val RELOCATOR_STAMP_FILE = "home/xuser/.rendersa_relocated_v4"

    data class RelocationReport(
        val symlinksCreated: Int,
        val elfInterpsPatched: Int,
        val elfStringsPatched: Int,
        val textConfigsRewritten: Int,
        val dynamicLinkerLinkPath: String
    )

    fun relocateRootFsIfNeeded(
        context: Context,
        rootDir: File,
        forceFullScan: Boolean = false
    ): RelocationReport {
        val packageName = context.packageName
        val shortBase = "/data/data/$packageName"
        val userZeroBase = context.dataDir.absolutePath

        // 1. Ensure required runtime socket and temp directories exist inside rootDir
        val requiredDirs = listOf(
            "tmp",
            "tmp/shm",
            "tmp/.X11-unix",
            "tmp/.vortek",
            "tmp/.sysvshm",
            "tmp/.sound",
            "home/xuser",
            "var/run"
        )
        for (rel in requiredDirs) {
            val d = File(rootDir, rel)
            d.mkdirs()
            FileUtils.chmod(d, 511) // 0777
        }

        // 2. Ensure lib/ld-linux-aarch64.so.1 and usr/lib/ld-linux-aarch64.so.1 both resolve
        val ldCandidates = listOf(
            File(rootDir, "lib/ld-linux-aarch64.so.1"),
            File(rootDir, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        )
        val actualLdFile = ldCandidates.firstOrNull { it.exists() } ?: ldCandidates[0]
        if (actualLdFile.exists()) {
            FileUtils.chmod(actualLdFile, 493) // 0755
            val defaultLibLd = File(rootDir, "lib/ld-linux-aarch64.so.1")
            if (!defaultLibLd.exists() && actualLdFile.absolutePath != defaultLibLd.absolutePath) {
                defaultLibLd.parentFile?.mkdirs()
                ensureSymlink(defaultLibLd, actualLdFile.absolutePath)
            }
        }

        // 3. Create 1-character symlink bridge in context.dataDir (accessible via /data/data/<pkg>/<char>)
        val symlinkMap = linkedMapOf(
            "l" to actualLdFile.absolutePath,
            "L" to File(rootDir, "usr/lib").absolutePath,
            "B" to File(rootDir, "usr/bin").absolutePath,
            "S" to File(rootDir, "usr/share").absolutePath,
            "C" to File(rootDir, "usr/local").absolutePath,
            "W" to File(rootDir, "opt/wine").absolutePath,
            "H" to File(rootDir, "home/xuser").absolutePath,
            "v" to File(rootDir, "tmp/.vortek").absolutePath,
            "x" to File(rootDir, "tmp/.X11-unix").absolutePath,
            "m" to File(rootDir, "tmp/.sysvshm").absolutePath,
            "a" to File(rootDir, "tmp/.sound").absolutePath,
            "T" to File(rootDir, "tmp").absolutePath,
            "E" to File(rootDir, "etc").absolutePath,
            "F" to File(rootDir, "etc/fonts").absolutePath,
            "A" to File(rootDir, "etc/alsa").absolutePath,
            "O" to File(rootDir, "opt").absolutePath,
            "V" to File(rootDir, "var").absolutePath
        )

        var symlinksCreated = 0
        for ((shortName, targetPath) in symlinkMap) {
            if (ensureSymlink(File(context.dataDir, shortName), targetPath)) {
                symlinksCreated++
            }
            val legacyDataDir = File(shortBase)
            if (legacyDataDir.exists() && legacyDataDir.absolutePath != userZeroBase) {
                ensureSymlink(File(legacyDataDir, shortName), targetPath)
            }
        }

        val dynamicLinkerShortPath = "$shortBase/l"

        // 4. Always remove stale ld.so.cache that points to /data/data/com.winlator
        val ldSoCache = File(rootDir, "etc/ld.so.cache")
        if (ldSoCache.exists()) {
            try {
                ldSoCache.delete()
            } catch (_: Throwable) {
            }
        }

        // 5. Always rewrite Vulkan ICD JSONs and text configs on every session start
        var textConfigsRewritten = rewriteVulkanIcdFiles(rootDir)
        textConfigsRewritten += rewriteTextConfigs(rootDir)

        // 6. Always verify & patch box64 and libvulkan_vortek.so (fast check), and run full ELF scan if needed
        val stampFile = File(rootDir, RELOCATOR_STAMP_FILE)
        val expectedStamp = "$packageName:${actualLdFile.length()}"
        val alreadyFullScanned = !forceFullScan &&
            stampFile.isFile &&
            stampFile.readText().trim() == expectedStamp

        var elfInterpsPatched = 0
        var elfStringsPatched = 0

        val replacementPairs = buildExactLengthReplacements(shortBase)

        // Always inspect critical runtime binaries on every launch
        val wineRel = RuntimeEnvironmentManager.resolveWineRelativePath(rootDir)
        val criticalBinaries = mutableListOf(
            File(rootDir, "usr/local/bin/box64"),
            File(rootDir, "usr/bin/box64"),
            File(rootDir, "usr/lib/libvulkan_vortek.so"),
            File(rootDir, "lib/libvulkan_vortek.so"),
            File(rootDir, "usr/lib/libvulkan_freedreno.so"),
            File(rootDir, "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so"),
            File(rootDir, "$wineRel/bin/wine"),
            File(rootDir, "$wineRel/bin/wine64"),
            File(rootDir, "$wineRel/bin/wineserver"),
            File(rootDir, "$wineRel/lib/wine/x86_64-unix/ntdll.so"),
            File(rootDir, "$wineRel/lib/wine/x86_64-unix/libwine.so.1"),
            File(rootDir, "$wineRel/lib/wine/i386-unix/ntdll.so"),
            File(rootDir, "$wineRel/lib/wine/aarch64-unix/ntdll.so")
        )
        if (wineRel != "opt/wine") {
            criticalBinaries.add(File(rootDir, "opt/wine/bin/wine"))
            criticalBinaries.add(File(rootDir, "opt/wine/bin/wine64"))
            criticalBinaries.add(File(rootDir, "opt/wine/bin/wineserver"))
            criticalBinaries.add(File(rootDir, "opt/wine/lib/wine/x86_64-unix/ntdll.so"))
        }
        for (bin in criticalBinaries) {
            if (bin.isFile && !FileUtils.isSymlink(bin)) {
                if (patchElfPtInterpIfNeeded(bin, dynamicLinkerShortPath)) {
                    elfInterpsPatched++
                }
                elfStringsPatched += patchElfBytesInPlace(bin, shortBase, replacementPairs)
                FileUtils.chmod(bin, 493) // 0755
            }
        }

        if (!alreadyFullScanned) {
            val scanDirs = listOf(
                File(rootDir, "usr/local/bin"),
                File(rootDir, "usr/bin"),
                File(rootDir, "bin"),
                File(rootDir, "sbin"),
                File(rootDir, "usr/sbin"),
                File(rootDir, "usr/lib"),
                File(rootDir, "lib"),
                File(rootDir, "$wineRel/bin"),
                File(rootDir, "$wineRel/lib/wine/aarch64-unix"),
                File(rootDir, "$wineRel/lib/wine/x86_64-unix"),
                File(rootDir, "$wineRel/lib/wine/i386-unix")
            )
            val visitedCanonical = HashSet<String>()
            for (dir in scanDirs) {
                if (!dir.isDirectory) continue
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (!file.isFile || FileUtils.isSymlink(file)) return@forEach
                    val pathKey = try {
                        file.canonicalPath
                    } catch (_: Throwable) {
                        file.absolutePath
                    }
                    if (!visitedCanonical.add(pathKey)) return@forEach
                    if (patchElfPtInterpIfNeeded(file, dynamicLinkerShortPath)) {
                        elfInterpsPatched++
                    }
                    elfStringsPatched += patchElfBytesInPlace(file, shortBase, replacementPairs)
                }
            }
            try {
                stampFile.parentFile?.mkdirs()
                stampFile.writeText(expectedStamp)
            } catch (_: Throwable) {
            }
        }

        UniversalCrashLogger.logCheckpoint(
            TAG,
            "Relokasi RootFS selesai: symlinks=$symlinksCreated, ptInterp=$elfInterpsPatched, " +
                "elfStrings=$elfStringsPatched, configs=$textConfigsRewritten, linker=$dynamicLinkerShortPath"
        )

        return RelocationReport(
            symlinksCreated = symlinksCreated,
            elfInterpsPatched = elfInterpsPatched,
            elfStringsPatched = elfStringsPatched,
            textConfigsRewritten = textConfigsRewritten,
            dynamicLinkerLinkPath = dynamicLinkerShortPath
        )
    }

    private fun ensureSymlink(linkFile: File, targetPath: String): Boolean {
        return try {
            if (FileUtils.isSymlink(linkFile)) {
                val current = FileUtils.readSymlink(linkFile)
                if (current == targetPath) return true
                linkFile.delete()
            } else if (linkFile.exists()) {
                linkFile.delete()
            }
            Os.symlink(targetPath, linkFile.absolutePath)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $targetPath", e)
            false
        }
    }

    private fun rewriteVulkanIcdFiles(rootDir: File): Int {
        var count = 0
        val icdDir = File(rootDir, "usr/share/vulkan/icd.d")
        if (!icdDir.isDirectory) return 0

        val vortekJson = File(icdDir, "vortek_icd.aarch64.json")
        val vortekDisabled = File(icdDir, "vortek_icd.aarch64.json.disabled")
        val vortekLib = listOf(
            File(rootDir, "usr/lib/libvulkan_vortek.so"),
            File(rootDir, "lib/libvulkan_vortek.so")
        ).firstOrNull { it.exists() }

        if (vortekLib != null && vortekLib.exists()) {
            val targetJson = if (vortekJson.isFile) vortekJson else if (vortekDisabled.isFile) {
                vortekDisabled.renameTo(vortekJson)
                vortekJson
            } else vortekJson
            if (writeVulkanIcdJson(targetJson, vortekLib.absolutePath, "1.1.128")) {
                count++
            }
        } else if (vortekJson.isFile) {
            vortekJson.renameTo(vortekDisabled)
        }

        val turnipJson = File(icdDir, "freedreno_icd.aarch64.json")
        val turnipDisabled = File(icdDir, "freedreno_icd.aarch64.json.disabled")
        val turnipLib = listOf(
            File(rootDir, "usr/lib/libvulkan_freedreno.so"),
            File(rootDir, "lib/libvulkan_freedreno.so")
        ).firstOrNull { it.exists() }

        if (turnipLib != null && turnipLib.exists()) {
            val targetJson = if (turnipJson.isFile) turnipJson else if (turnipDisabled.isFile) {
                turnipDisabled.renameTo(turnipJson)
                turnipJson
            } else turnipJson
            if (writeVulkanIcdJson(targetJson, turnipLib.absolutePath, "1.3.296")) {
                count++
            }
        } else if (turnipJson.isFile) {
            turnipJson.renameTo(turnipDisabled)
        }

        return count
    }

    private fun writeVulkanIcdJson(jsonFile: File, libraryPath: String, defaultApiVersion: String): Boolean {
        return try {
            val existing = jsonFile.readText()
            val rootObj = try {
                JSONObject(existing)
            } catch (_: Throwable) {
                JSONObject()
            }
            val icdObj = rootObj.optJSONObject("ICD") ?: JSONObject()
            val apiVer = icdObj.optString("api_version").ifEmpty { defaultApiVersion }
            val currentLib = icdObj.optString("library_path")
            if (currentLib == libraryPath && !existing.contains(WINLATOR_NEEDLE)) {
                return false
            }
            icdObj.put("api_version", apiVer)
            icdObj.put("library_path", libraryPath)
            rootObj.put("ICD", icdObj)
            if (!rootObj.has("file_format_version")) {
                rootObj.put("file_format_version", "1.0.0")
            }
            jsonFile.writeText(rootObj.toString(4))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update ICD file ${jsonFile.absolutePath}", e)
            false
        }
    }

    private fun rewriteTextConfigs(rootDir: File): Int {
        var rewritten = 0
        val configDirs = listOf(
            File(rootDir, "etc"),
            File(rootDir, "usr/share/alsa"),
            File(rootDir, "usr/share/vulkan"),
            File(rootDir, "usr/local/bin")
        )
        for (dir in configDirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().maxDepth(4).forEach { file ->
                if (!file.isFile || FileUtils.isSymlink(file)) return@forEach
                val len = file.length()
                if (len <= 0L || len > 256 * 1024L) return@forEach
                try {
                    val bytes = file.readBytes()
                    // Skip ELF binaries
                    if (bytes.size >= 4 &&
                        bytes[0] == 0x7F.toByte() &&
                        bytes[1] == 'E'.code.toByte() &&
                        bytes[2] == 'L'.code.toByte() &&
                        bytes[3] == 'F'.code.toByte()
                    ) {
                        return@forEach
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.contains(WINLATOR_ROOTFS_PREFIX)) {
                        val updated = text.replace(WINLATOR_ROOTFS_PREFIX, rootDir.absolutePath)
                        file.writeText(updated, Charsets.UTF_8)
                        rewritten++
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return rewritten
    }

    private fun buildExactLengthReplacements(shortBase: String): List<Pair<ByteArray, ByteArray>> {
        val result = ArrayList<Pair<ByteArray, ByteArray>>()
        val needle = WINLATOR_NEEDLE

        // 1. Direct 1:1 replacement when package name length is 12 (e.g. "com.rendersa" vs "com.winlator")
        if (shortBase.length == needle.length) {
            val rootFsPrefix = "$shortBase/files/rootfs"
            result.add(
                WINLATOR_ROOTFS_PREFIX.toByteArray(Charsets.US_ASCII) to
                    rootFsPrefix.toByteArray(Charsets.US_ASCII)
            )
            result.add(
                needle.toByteArray(Charsets.US_ASCII) to
                    shortBase.toByteArray(Charsets.US_ASCII)
            )
        }

        // 2. Subpath mappings with slash padding or symlink characters
        val mappings = listOf(
            "$WINLATOR_ROOTFS_PREFIX/lib/ld-linux-aarch64.so.1" to 'l',
            "$WINLATOR_ROOTFS_PREFIX/tmp/.X11-unix" to 'x',
            "$WINLATOR_ROOTFS_PREFIX/tmp/.sysvshm" to 'm',
            "$WINLATOR_ROOTFS_PREFIX/tmp/.vortek" to 'v',
            "$WINLATOR_ROOTFS_PREFIX/tmp/.sound" to 'a',
            "$WINLATOR_ROOTFS_PREFIX/home/xuser" to 'H',
            "$WINLATOR_ROOTFS_PREFIX/usr/share" to 'S',
            "$WINLATOR_ROOTFS_PREFIX/usr/local" to 'C',
            "$WINLATOR_ROOTFS_PREFIX/etc/fonts" to 'F',
            "$WINLATOR_ROOTFS_PREFIX/opt/wine" to 'W',
            "$WINLATOR_ROOTFS_PREFIX/etc/alsa" to 'A',
            "$WINLATOR_ROOTFS_PREFIX/usr/lib" to 'L',
            "$WINLATOR_ROOTFS_PREFIX/usr/bin" to 'B',
            "$WINLATOR_ROOTFS_PREFIX/tmp" to 'T'
        )
        for ((oldStr, symChar) in mappings) {
            val targetLen = oldStr.length
            val slashCount = targetLen - shortBase.length - 1
            if (slashCount >= 1) {
                val newStr = shortBase + "/".repeat(slashCount) + symChar
                if (newStr.length == targetLen) {
                    result.add(
                        oldStr.toByteArray(Charsets.US_ASCII) to newStr.toByteArray(Charsets.US_ASCII)
                    )
                }
            }
        }
        // Handle standalone "/data/data/com.winlator/files/rootfs/lib\0\0" (42 bytes in .dynstr)
        val oldRpathPadded = "$WINLATOR_ROOTFS_PREFIX/lib\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val newRpath = if (shortBase.length == needle.length) {
            "$shortBase/files/rootfs/lib\u0000\u0000".toByteArray(Charsets.US_ASCII)
        } else {
            "$shortBase/L\u0000".toByteArray(Charsets.US_ASCII)
        }
        if (oldRpathPadded.size == newRpath.size) {
            result.add(oldRpathPadded to newRpath)
        }

        // Sort by length descending so longer exact patterns match before shorter prefix
        result.sortByDescending { it.first.size }
        return result
    }

    fun patchElfPtInterpIfNeeded(file: File, newInterpPath: String): Boolean {
        if (!file.isFile || file.length() < 64L) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val header = ByteArray(64)
                if (raf.read(header) < 64) return false
                if (header[0] != 0x7F.toByte() ||
                    header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) {
                    return false
                }
                // Only 64-bit little-endian ELF
                if (header[4].toInt() != 2 || header[5].toInt() != 1) return false

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val eMachine = bb.getShort(18).toInt() and 0xFFFF
                // Only patch ARM64 (AArch64 = 183) binaries
                if (eMachine != 183) return false

                val ePhoff = bb.getLong(32)
                val ePhentsize = bb.getShort(54).toInt() and 0xFFFF
                val ePhnum = bb.getShort(56).toInt() and 0xFFFF
                if (ePhoff <= 0L || ePhentsize < 56 || ePhnum <= 0 || ePhnum > 64) return false

                val phTableSize = ePhentsize * ePhnum
                if (ePhoff + phTableSize > raf.length()) return false

                val phBytes = ByteArray(phTableSize)
                raf.seek(ePhoff)
                raf.readFully(phBytes)
                val phBuf = ByteBuffer.wrap(phBytes).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until ePhnum) {
                    val base = i * ePhentsize
                    val pType = phBuf.getInt(base)
                    if (pType == 3) { // PT_INTERP
                        val pOffset = phBuf.getLong(base + 8)
                        val pFilesz = phBuf.getLong(base + 32).toInt()
                        if (pOffset <= 0L || pFilesz <= 0 || pFilesz > 256 || pOffset + pFilesz > raf.length()) {
                            return false
                        }
                        val interpBytes = ByteArray(pFilesz)
                        raf.seek(pOffset)
                        raf.readFully(interpBytes)
                        val nullIdx = interpBytes.indexOf(0.toByte()).let { if (it >= 0) it else pFilesz }
                        val currentInterp = String(interpBytes, 0, nullIdx, Charsets.US_ASCII)

                        if (currentInterp == newInterpPath) {
                            return false
                        }
                        if (!currentInterp.startsWith(WINLATOR_NEEDLE) &&
                            !currentInterp.startsWith("/data/data/") &&
                            !currentInterp.startsWith("/data/user/")
                        ) {
                            return false
                        }
                        val newBytes = newInterpPath.toByteArray(Charsets.US_ASCII)
                        if (newBytes.size + 1 > pFilesz) {
                            Log.w(TAG, "New PT_INTERP ($newInterpPath) exceeds p_filesz ($pFilesz) in ${file.name}")
                            return false
                        }
                        val replacement = ByteArray(pFilesz)
                        System.arraycopy(newBytes, 0, replacement, 0, newBytes.size)
                        raf.seek(pOffset)
                        raf.write(replacement)
                        FileUtils.chmod(file, 493) // 0755
                        return true
                    }
                }
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to patch PT_INTERP on ${file.absolutePath}", e)
            false
        }
    }

    private fun patchElfBytesInPlace(
        file: File,
        shortBase: String,
        replacements: List<Pair<ByteArray, ByteArray>>
    ): Int {
        val fileLen = file.length()
        if (fileLen < 64L || fileLen > 64L * 1024L * 1024L) return 0
        return try {
            val data = file.readBytes()
            if (data[0] != 0x7F.toByte() ||
                data[1] != 'E'.code.toByte() ||
                data[2] != 'L'.code.toByte() ||
                data[3] != 'F'.code.toByte()
            ) {
                return 0
            }
            val needle = WINLATOR_NEEDLE.toByteArray(Charsets.US_ASCII)
            var searchFrom = 0
            val patches = ArrayList<Pair<Int, ByteArray>>()

            while (searchFrom <= data.size - needle.size) {
                val idx = indexOfBytes(data, needle, searchFrom)
                if (idx < 0) break

                var matched = false
                for ((oldPattern, newPattern) in replacements) {
                    if (idx + oldPattern.size <= data.size &&
                        regionMatches(data, idx, oldPattern)
                    ) {
                        patches.add(idx to newPattern)
                        searchFrom = idx + oldPattern.size
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    searchFrom = idx + needle.size
                }
            }

            if (patches.isNotEmpty()) {
                RandomAccessFile(file, "rw").use { raf ->
                    for ((offset, newBytes) in patches) {
                        raf.seek(offset.toLong())
                        raf.write(newBytes)
                    }
                }
                FileUtils.chmod(file, 493)
            }
            patches.size
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to patch ELF strings in ${file.absolutePath}", e)
            0
        }
    }

    private fun indexOfBytes(data: ByteArray, needle: ByteArray, startIndex: Int): Int {
        val first = needle[0]
        val max = data.size - needle.size
        var i = startIndex
        while (i <= max) {
            if (data[i] == first) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) {
                    j++
                }
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun regionMatches(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        for (i in pattern.indices) {
            if (data[offset + i] != pattern[i]) return false
        }
        return true
    }
}
