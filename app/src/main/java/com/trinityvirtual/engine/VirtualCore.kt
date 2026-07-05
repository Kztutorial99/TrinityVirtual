package com.trinityvirtual.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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

    /**
     * Primary entry point — imports a file from a content URI.
     * Supports .apk, .apks (split-APK bundle), .xapk (APKPure format).
     * File is first copied to cache, then extracted/processed, then
     * the final APK is moved into the private container at
     * /data/user/0/<package>/files/container/.
     */
    suspend fun importFromUri(uri: Uri, fileName: String): VirtualApp? = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            tempFile = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.$ext")

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { out -> input.copyTo(out) }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "Temp file empty after copy: $fileName")
                return@withContext null
            }

            Log.d(TAG, "Importing $fileName (${tempFile.length()} bytes) as .$ext")

            when (ext) {
                "apk"  -> processApkFile(tempFile, "apk")
                "apks" -> extractAndInstallApks(tempFile)
                "xapk" -> extractAndInstallXapk(tempFile)
                else   -> processApkFile(tempFile, "apk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "importFromUri failed for $fileName: ${e.message}", e)
            null
        } finally {
            try { tempFile?.delete() } catch (e: Exception) { }
        }
    }

    suspend fun getInstalledApps(): List<VirtualApp> = withContext(Dispatchers.IO) {
        try {
            containerDir.listFiles { f -> f.extension == "apk" }
                ?.mapNotNull { apkFile -> buildVirtualAppFromApk(apkFile) }
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

    fun isInitialized(): Boolean = ::ctx.isInitialized

    // ─────────────────────────────────────────────────────────────────
    // FORMAT HANDLERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * .apks — Split APK bundle (ZIP file from Play Store / SAI).
     * Contains: base.apk + split_config.*.apk
     * We extract base.apk or the first APK found.
     */
    private fun extractAndInstallApks(apksFile: File): VirtualApp? {
        val extractDir = File(ctx.cacheDir, "apks_${System.currentTimeMillis()}")
        extractDir.mkdirs()
        return try {
            val extracted = mutableListOf<File>()
            ZipInputStream(apksFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                        val safeName = entry.name.replace('/', '_')
                        val dest     = File(extractDir, safeName)
                        dest.outputStream().use { out -> zip.copyTo(out) }
                        extracted.add(dest)
                        Log.d(TAG, "APKS — extracted: ${entry.name} (${dest.length()} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (extracted.isEmpty()) {
                Log.e(TAG, "No APK found inside .apks archive")
                return null
            }

            val target = extracted.firstOrNull { it.name.contains("base", ignoreCase = true) }
                ?: extracted.maxByOrNull { it.length() }
                ?: extracted.first()

            Log.d(TAG, "APKS — using: ${target.name}")
            processApkFile(target, "apks")
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /**
     * .xapk — APKPure bundle format (ZIP file).
     * Contains: [packagename].apk OR base.apk + manifest.json + icon.png
     * We extract the main/largest APK.
     */
    private fun extractAndInstallXapk(xapkFile: File): VirtualApp? {
        val extractDir = File(ctx.cacheDir, "xapk_${System.currentTimeMillis()}")
        extractDir.mkdirs()
        return try {
            val extracted = mutableListOf<File>()
            ZipInputStream(xapkFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".apk") &&
                        !name.contains("split_config", ignoreCase = true)) {
                        val safeName = name.replace('/', '_')
                        val dest     = File(extractDir, safeName)
                        dest.outputStream().use { out -> zip.copyTo(out) }
                        extracted.add(dest)
                        Log.d(TAG, "XAPK — extracted: $name (${dest.length()} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (extracted.isEmpty()) {
                Log.e(TAG, "No APK found inside .xapk archive")
                return null
            }

            val target = extracted.firstOrNull { it.name.contains("base", ignoreCase = true) }
                ?: extracted.maxByOrNull { it.length() }
                ?: extracted.first()

            Log.d(TAG, "XAPK — using: ${target.name}")
            processApkFile(target, "xapk")
        } finally {
            extractDir.deleteRecursively()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // APK PROCESSING — copy to private container & read metadata
    // ─────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun processApkFile(apkFile: File, sourceType: String): VirtualApp? {
        val pm = ctx.packageManager

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: run {
            Log.e(TAG, "getPackageArchiveInfo returned null — file may be invalid or corrupt")
            return null
        }

        val appInfo = packageInfo.applicationInfo ?: return null
        appInfo.sourceDir       = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath

        val packageName = packageInfo.packageName ?: return null
        val versionName = packageInfo.versionName ?: "1.0"
        val appName     = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { packageName }

        // Copy APK to private container
        val destFile = File(containerDir, "${packageName}_${System.currentTimeMillis()}.apk")
        apkFile.copyTo(destFile, overwrite = true)
        Log.d(TAG, "APK installed: $appName → ${destFile.absolutePath}")

        // Extract icon
        val icon     = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
        val iconPath = icon?.let { saveIcon(it, packageName) }

        // Create private sandbox dir
        File(dataDir, packageName).mkdirs()

        return VirtualApp(
            appName     = appName,
            packageName = packageName,
            apkPath     = destFile.absolutePath,
            iconPath    = iconPath,
            versionName = versionName,
            sizeBytes   = destFile.length(),
            sourceType  = sourceType
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun buildVirtualAppFromApk(apkFile: File): VirtualApp? {
        return try {
            val pm = ctx.packageManager
            val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir       = apkFile.absolutePath
            ai.publicSourceDir = apkFile.absolutePath
            VirtualApp(
                appName     = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pi.packageName },
                packageName = pi.packageName,
                apkPath     = apkFile.absolutePath,
                versionName = pi.versionName ?: "1.0",
                sizeBytes   = apkFile.length()
            )
        } catch (e: Exception) {
            Log.e(TAG, "buildVirtualAppFromApk failed for ${apkFile.name}", e)
            null
        }
    }

    private fun saveIcon(drawable: Drawable, packageName: String): String? {
        return try {
            val bitmap   = drawableToBitmap(drawable)
            val iconFile = File(iconDir, "$packageName.png")
            FileOutputStream(iconFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            iconFile.absolutePath
        } catch (e: Exception) { null }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w      = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h      = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
