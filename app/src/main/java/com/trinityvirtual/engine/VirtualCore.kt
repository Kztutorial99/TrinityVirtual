package com.trinityvirtual.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.trinityvirtual.model.VirtualApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VirtualCore {

    private lateinit var ctx: Context
    private lateinit var virtualDir: File
    private lateinit var iconDir: File
    private lateinit var dataDir: File

    fun init(context: Context) {
        ctx = context.applicationContext
        virtualDir = File(ctx.filesDir, "virtual_apps").also { it.mkdirs() }
        iconDir = File(ctx.filesDir, "icons").also { it.mkdirs() }
        dataDir = File(ctx.filesDir, "app_data").also { it.mkdirs() }
    }

    @Suppress("DEPRECATION")
    suspend fun installApk(sourceApkPath: String): VirtualApp? = withContext(Dispatchers.IO) {
        try {
            val pm = ctx.packageManager
            val sourceFile = File(sourceApkPath)

            if (!sourceFile.exists() || sourceFile.length() == 0L) return@withContext null

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(sourceApkPath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageArchiveInfo(sourceApkPath, 0)
            } ?: return@withContext null

            val appInfo = packageInfo.applicationInfo ?: return@withContext null
            appInfo.sourceDir = sourceApkPath
            appInfo.publicSourceDir = sourceApkPath

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageInfo.packageName ?: "Unknown App"
            }

            val packageName = packageInfo.packageName ?: return@withContext null
            val versionName = packageInfo.versionName ?: "1.0"
            val destFile = File(virtualDir, "${packageName}_${System.currentTimeMillis()}.apk")
            sourceFile.copyTo(destFile, overwrite = true)

            val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
            val iconPath = icon?.let { saveIcon(it, packageName) }

            File(dataDir, packageName).mkdirs()

            VirtualApp(
                appName = appName,
                packageName = packageName,
                apkPath = destFile.absolutePath,
                iconPath = iconPath,
                versionName = versionName,
                sizeBytes = destFile.length()
            )
        } catch (e: Exception) {
            android.util.Log.e("VirtualCore", "installApk failed: ${e.message}", e)
            null
        }
    }

    private fun saveIcon(drawable: Drawable, packageName: String): String? {
        return try {
            val bitmap = drawableToBitmap(drawable)
            val iconFile = File(iconDir, "$packageName.png")
            FileOutputStream(iconFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            iconFile.absolutePath
        } catch (e: Exception) { null }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    suspend fun getInstalledApps(): List<VirtualApp> = withContext(Dispatchers.IO) {
        try {
            virtualDir.listFiles { f -> f.extension == "apk" }
                ?.mapNotNull { apkFile ->
                    val pm = ctx.packageManager
                    @Suppress("DEPRECATION")
                    val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return@mapNotNull null
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    ai.sourceDir = apkFile.absolutePath
                    ai.publicSourceDir = apkFile.absolutePath
                    VirtualApp(
                        appName = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pi.packageName },
                        packageName = pi.packageName,
                        apkPath = apkFile.absolutePath,
                        versionName = pi.versionName ?: "1.0",
                        sizeBytes = apkFile.length()
                    )
                } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    suspend fun deleteApp(apkPath: String) = withContext(Dispatchers.IO) {
        try { File(apkPath).delete() } catch (e: Exception) { }
    }

    fun isInitialized() = ::ctx.isInitialized
}
