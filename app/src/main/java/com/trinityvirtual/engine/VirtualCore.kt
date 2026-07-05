package com.trinityvirtual.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

    suspend fun installApk(sourceApkPath: String): VirtualApp? = withContext(Dispatchers.IO) {
        try {
            val pm = ctx.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                sourceApkPath,
                PackageManager.GET_META_DATA
            ) ?: return@withContext null

            val appInfo = packageInfo.applicationInfo ?: return@withContext null
            appInfo.sourceDir = sourceApkPath
            appInfo.publicSourceDir = sourceApkPath

            val appName = pm.getApplicationLabel(appInfo).toString()
            val packageName = packageInfo.packageName
            val versionName = packageInfo.versionName ?: "1.0"
            val sourceFile = File(sourceApkPath)
            val destFile = File(virtualDir, "${packageName}_${System.currentTimeMillis()}.apk")
            sourceFile.copyTo(destFile, overwrite = true)

            val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
            val iconPath = icon?.let { saveIcon(it, packageName) }

            // Create isolated data dir for this virtual app
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
            e.printStackTrace()
            null
        }
    }

    private fun saveIcon(drawable: Drawable, packageName: String): String? {
        return try {
            val bitmap = drawableToBitmap(drawable)
            val iconFile = File(iconDir, "$packageName.png")
            FileOutputStream(iconFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            iconFile.absolutePath
        } catch (e: Exception) { null }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val bmp = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    fun deleteApp(apkPath: String) {
        File(apkPath).delete()
    }

    fun getVirtualDir(): File = virtualDir
    fun getAppDataDir(packageName: String): File = File(dataDir, packageName).also { it.mkdirs() }
}
