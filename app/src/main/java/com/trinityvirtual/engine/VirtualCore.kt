package com.trinityvirtual.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.trinityvirtual.model.VirtualApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object VirtualCore {

    private const val TAG = "VirtualCore"
    private lateinit var ctx: Context
    private lateinit var containerDir: File
    private lateinit var iconDir: File
    private lateinit var dataDir: File

    fun init(context: Context) {
        ctx          = context.applicationContext
        containerDir = File(ctx.filesDir, "container").also { it.mkdirs() }
        iconDir      = File(ctx.filesDir, "icons").also { it.mkdirs() }
        dataDir      = File(ctx.filesDir, "app_data").also { it.mkdirs() }
        Log.d(TAG, "Container: ${containerDir.absolutePath}")
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    suspend fun importFromUri(uri: Uri, fileName: String): VirtualApp? =
        withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                tempFile = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    Log.e(TAG, "Temp file empty: $fileName")
                    return@withContext null
                }
                when (ext) {
                    "apk"  -> processApkFile(tempFile, "apk")
                    "apks" -> extractAndInstallApks(tempFile)
                    "xapk" -> extractAndInstallXapk(tempFile)
                    else   -> processApkFile(tempFile, "apk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "importFromUri failed: ${e.message}", e)
                null
            } finally {
                try { tempFile?.delete() } catch (_: Exception) {}
            }
        }

    suspend fun getInstalledApps(): List<VirtualApp> = withContext(Dispatchers.IO) {
        try {
            containerDir.listFiles { f -> f.extension == "apk" }
                ?.mapNotNull { buildVirtualAppFromApk(it) }
                ?.sortedByDescending { it.installedAt }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledApps failed", e)
            emptyList()
        }
    }

    suspend fun deleteApp(apkPath: String) = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(apkPath)
            val pkgName = apkFile.name.substringBefore('_')
            apkFile.delete()
            File(iconDir, "$pkgName.png").delete()
            File(dataDir, pkgName).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "deleteApp failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // § STABILITY TEST — Root Detection Bypass Verification
    // ─────────────────────────────────────────────────────────────────

    /**
     * Runs a standardised root-detection test suite inside TrinityVirtual.
     *
     * T01 — RootEngine status      : checkRootStatus() == VIRTUAL_ROOT
     * T02 — /system/bin/su access  : file exists
     * T03 — su in PATH             : `which su` non-empty
     * T04 — id command             : `id` contains uid=0
     * T05 — /system/xbin/su access : file exists
     * T06 — ro.build.tags          : not "test-keys" / "debug"
     * T07 — ro.debuggable          : "0"
     * T08 — /proc/self/maps clean  : no magisk/trinity/frida leaks
     * T09 — su -v stealth          : no suspicious root-manager names
     * T10 — No SU APK              : com.topjohnwu.magisk etc. not installed
     */
    suspend fun runRootDetectionTest(): List<RootCheckResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<RootCheckResult>()

            // T01
            results += try {
                val status = RootEngine.checkRootStatus()
                val passed = status == RootEngine.RootStatus.VIRTUAL_ROOT ||
                             status == RootEngine.RootStatus.ROOTED
                RootCheckResult("T01 UID/Status", passed, "RootEngine = $status")
            } catch (e: Exception) {
                RootCheckResult("T01 UID/Status", false, "Exception: ${e.message}")
            }

            // T02
            results += shellCheck("T02 /system/bin/su",
                "test -e /system/bin/su && echo OK || echo FAIL", "OK")

            // T03
            results += try {
                val out = shell("which su").trim()
                RootCheckResult("T03 su in PATH",
                    out.isNotEmpty() && !out.contains("not found"),
                    "which su → '$out'")
            } catch (e: Exception) {
                RootCheckResult("T03 su in PATH", false, "Exception: ${e.message}")
            }

            // T04
            results += try {
                val out = shell("id").trim()
                RootCheckResult("T04 id uid=0", out.contains("uid=0"), "id → '$out'")
            } catch (e: Exception) {
                RootCheckResult("T04 id uid=0", false, "Exception: ${e.message}")
            }

            // T05
            results += shellCheck("T05 /system/xbin/su",
                "test -e /system/xbin/su && echo OK || echo FAIL", "OK")

            // T06
            results += propCheck("T06 ro.build.tags", "ro.build.tags",
                listOf("test-keys", "debug", "userdebug"))

            // T07
            results += try {
                val v = getprop("ro.debuggable").trim()
                RootCheckResult("T07 ro.debuggable", v == "0" || v.isEmpty(),
                    "ro.debuggable='$v'")
            } catch (e: Exception) {
                RootCheckResult("T07 ro.debuggable", false, "Exception: ${e.message}")
            }

            // T08
            results += try {
                val maps  = File("/proc/self/maps").readText()
                val banned = listOf("magisk", "zygisk", "trinity", "fakeroot",
                                    "xposed", "substrate", "frida")
                val leaks = banned.filter { maps.contains(it, ignoreCase = true) }
                RootCheckResult("T08 maps clean", leaks.isEmpty(),
                    if (leaks.isEmpty()) "No leaks" else "Leaked: ${leaks.joinToString()}")
            } catch (e: Exception) {
                RootCheckResult("T08 maps clean", false, "Exception: ${e.message}")
            }

            // T09
            results += try {
                val ver  = shell("su -v 2>&1").trim()
                val sus  = listOf("magisk", "supersu", "trinity")
                val bad  = sus.any { ver.contains(it, ignoreCase = true) }
                RootCheckResult("T09 su -v stealth", ver.isNotEmpty() && !bad,
                    "su -v → '$ver'")
            } catch (e: Exception) {
                RootCheckResult("T09 su -v stealth", false, "Exception: ${e.message}")
            }

            // T10
            results += try {
                val pm   = ctx.packageManager
                val pkgs = listOf("com.noshufou.android.su", "eu.chainfire.supersu",
                                  "com.topjohnwu.magisk", "me.phh.superuser")
                val found = pkgs.filter { pkg ->
                    try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                }
                RootCheckResult("T10 no SU APK", found.isEmpty(),
                    if (found.isEmpty()) "None detected" else "Found: ${found.joinToString()}")
            } catch (e: Exception) {
                RootCheckResult("T10 no SU APK", false, "Exception: ${e.message}")
            }

            val pass = results.count { it.passed }
            Log.i(TAG, "═══ ROOT TEST: $pass/${results.size} passed ═══")
            results.forEach { r ->
                Log.i(TAG, "  ${if (r.passed) "✓" else "✗"} ${r.name}: ${r.detail}")
            }
            results
        }

    data class RootCheckResult(val name: String, val passed: Boolean, val detail: String)

    // ─────────────────────────────────────────────────────────────────
    // Shell / prop helpers (private)
    // ─────────────────────────────────────────────────────────────────

    private fun shell(cmd: String): String {
        return try {
            val p   = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (_: Exception) { "" }
    }

    private fun getprop(key: String): String = shell("getprop $key")

    private fun shellCheck(name: String, cmd: String, expect: String): RootCheckResult {
        return try {
            val out    = shell(cmd).trim()
            val passed = out.contains(expect, ignoreCase = true)
            RootCheckResult(name, passed, "→ '$out'")
        } catch (e: Exception) {
            RootCheckResult(name, false, "Exception: ${e.message}")
        }
    }

    private fun propCheck(name: String, key: String, banned: List<String>): RootCheckResult {
        return try {
            val v   = getprop(key).trim()
            val bad = banned.firstOrNull { v.contains(it, ignoreCase = true) }
            RootCheckResult(name, bad == null,
                if (bad == null) "$key='$v'" else "$key='$v' contains '$bad'")
        } catch (e: Exception) {
            RootCheckResult(name, false, "Exception: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // APK processing (block body — no expression-body early returns)
    // ─────────────────────────────────────────────────────────────────

    private fun processApkFile(file: File, sourceType: String): VirtualApp? {
        return try {
            val pm      = ctx.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath,
                              PackageManager.GET_META_DATA) ?: return null
            pkgInfo.applicationInfo?.also { ai ->
                ai.sourceDir       = file.absolutePath
                ai.publicSourceDir = file.absolutePath
            }
            val label   = pkgInfo.applicationInfo?.loadLabel(pm)?.toString()
                          ?: pkgInfo.packageName ?: return null
            val pkgName = pkgInfo.packageName ?: return null
            val version = pkgInfo.versionName  ?: "1.0"

            val destName = "${pkgName}_${System.currentTimeMillis()}.apk"
            val destFile = File(containerDir, destName)
            file.copyTo(destFile, overwrite = true)

            val iconPath = saveIcon(pkgName, extractIcon(pkgInfo.applicationInfo))

            VirtualApp(
                appName     = label,
                packageName = pkgName,
                apkPath     = destFile.absolutePath,
                iconPath    = iconPath.ifEmpty { null },
                versionName = version,
                sizeBytes   = destFile.length(),
                sourceType  = sourceType
            )
        } catch (e: Exception) {
            Log.e(TAG, "processApkFile failed", e)
            null
        }
    }

    private fun extractAndInstallApks(file: File): VirtualApp? {
        return try {
            var result: VirtualApp? = null
            ZipInputStream(file.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk") && !entry.name.contains("/")) {
                        val tmp = File(ctx.cacheDir, "base_${System.currentTimeMillis()}.apk")
                        FileOutputStream(tmp).use { zis.copyTo(it) }
                        result = processApkFile(tmp, "apks")
                        tmp.delete()
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "extractAndInstallApks failed", e)
            null
        }
    }

    private fun extractAndInstallXapk(file: File): VirtualApp? {
        return try {
            var result: VirtualApp? = null
            ZipInputStream(file.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk") && !entry.name.contains("/")) {
                        val tmp = File(ctx.cacheDir, "xapk_${System.currentTimeMillis()}.apk")
                        FileOutputStream(tmp).use { zis.copyTo(it) }
                        result = processApkFile(tmp, "xapk")
                        tmp.delete()
                        if (result != null) break
                    }
                    entry = zis.nextEntry
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "extractAndInstallXapk failed", e)
            null
        }
    }

    private fun buildVirtualAppFromApk(apkFile: File): VirtualApp? {
        return try {
            val pm      = ctx.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath,
                              PackageManager.GET_META_DATA) ?: return null
            pkgInfo.applicationInfo?.also { ai ->
                ai.sourceDir       = apkFile.absolutePath
                ai.publicSourceDir = apkFile.absolutePath
            }
            val label   = pkgInfo.applicationInfo?.loadLabel(pm)?.toString()
                          ?: pkgInfo.packageName ?: return null
            val pkgName = pkgInfo.packageName ?: return null
            val iconFile = File(iconDir, "$pkgName.png")

            VirtualApp(
                appName     = label,
                packageName = pkgName,
                apkPath     = apkFile.absolutePath,
                iconPath    = if (iconFile.exists()) iconFile.absolutePath else null,
                versionName = pkgInfo.versionName ?: "1.0",
                installedAt = apkFile.lastModified(),
                sizeBytes   = apkFile.length()
            )
        } catch (_: Exception) { null }
    }

    private fun extractIcon(appInfo: android.content.pm.ApplicationInfo?): Bitmap? {
        if (appInfo == null) return null
        return try {
            val drawable: Drawable = ctx.packageManager.getApplicationIcon(appInfo)
            val size = 192
            val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv   = Canvas(bmp)
            if (drawable is BitmapDrawable) {
                cv.drawBitmap(drawable.bitmap, null,
                    android.graphics.Rect(0, 0, size, size), null)
            } else {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(cv)
            }
            bmp
        } catch (_: Exception) { null }
    }

    private fun saveIcon(pkgName: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val f = File(iconDir, "$pkgName.png")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            f.absolutePath
        } catch (_: Exception) { "" }
    }
}
