package com.trinityvirtual.module

import android.content.Context
import android.util.Log
import com.trinityvirtual.model.ModuleType
import com.trinityvirtual.model.TrinityModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

// ModuleManager - Trinity module lifecycle + Stealth Pre-loader.
//
// Stealth Pre-loader:
//   preloadStealthModules() scans assets/modules/ at launch.
//   Native: memfd_create + dlopen(/proc/self/fd/N) - no filesystem trace.
//
// ZIP-based install (UI-driven):
//   installModule(zipPath) - validates ZIP, canonical path check prevents ZIP slip.
//
// nativeLoadModule takes ABSOLUTE .so path - no composition in native layer.
object ModuleManager {

    private const val TAG              = "ModuleManager"
    private const val ASSET_MODULE_DIR = "modules"
    private const val MODULE_DIR_NAME  = "modules"

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        File(ctx.filesDir, MODULE_DIR_NAME).mkdirs()
    }

    // Scan assets/modules/ and load each .so in-memory via memfd_create.
    // Must be called from a background coroutine.
    suspend fun preloadStealthModules(): PreloadResult = withContext(Dispatchers.IO) {
        var loaded = 0
        var failed = 0
        try {
            val am      = ctx.assets
            val entries = try { am.list(ASSET_MODULE_DIR) } catch (e: Exception) { null }
            if (entries.isNullOrEmpty()) {
                Log.d(TAG, "No modules in assets/$ASSET_MODULE_DIR")
                return@withContext PreloadResult(0, 0)
            }
            for (name in entries) {
                if (!name.endsWith(".so") && !name.endsWith(".trinity")) continue
                try {
                    val bytes = am.open("$ASSET_MODULE_DIR/$name").use { it.readBytes() }
                    if (bytes.isEmpty()) { failed++; Log.e(TAG, "Empty asset: $name"); continue }
                    val modName = name.removeSuffix(".trinity").removeSuffix(".so")
                    if (nativeLoadModuleFromMemory(bytes, modName)) {
                        loaded++; Log.i(TAG, "Stealth loaded $modName")
                    } else {
                        failed++; Log.e(TAG, "Stealth load failed: $modName")
                    }
                } catch (e: Exception) {
                    failed++; Log.e(TAG, "Exception loading $name: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "preloadStealthModules error: ${e.message}")
        }
        Log.i(TAG, "Stealth preload: $loaded loaded, $failed failed")
        PreloadResult(loaded, failed)
    }

    data class PreloadResult(val loaded: Int, val failed: Int) {
        val allSucceeded get() = failed == 0
    }

    // Install a module from a ZIP file.
    // Security: canonical path check prevents ZIP slip (path traversal).
    // Validation: requires at least one .so OR module.prop in the ZIP.
    // Returns TrinityModule on success, null on failure.
    suspend fun installModule(zipPath: String): TrinityModule? = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(zipPath)
            if (!zipFile.exists() || zipFile.length() == 0L) {
                Log.e(TAG, "Invalid ZIP: $zipPath")
                return@withContext null
            }

            var moduleName  = zipFile.nameWithoutExtension
            var version     = "1.0"
            var description = "Trinity Module"
            var author      = "Unknown"
            var moduleType  = ModuleType.HOOK
            var hasSo       = false
            var hasProp     = false

            // Pass 1: read manifest + detect content
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".so")) {
                        hasSo = true
                    } else if (entry.name == "module.prop" || entry.name == "META-INF/module.prop") {
                        hasProp = true
                        val props = zis.bufferedReader().readText()
                        props.lines().forEach { line ->
                            val kv = line.split("=", limit = 2)
                            if (kv.size == 2) {
                                when (kv[0].trim()) {
                                    "name"        -> moduleName  = kv[1].trim()
                                    "version"     -> version     = kv[1].trim()
                                    "description" -> description = kv[1].trim()
                                    "author"      -> author      = kv[1].trim()
                                    "type"        -> moduleType  = when (kv[1].trim().uppercase()) {
                                        "XPOSED" -> ModuleType.XPOSED
                                        "SYSTEM" -> ModuleType.SYSTEM
                                        "SPOOF"  -> ModuleType.SPOOF
                                        else     -> ModuleType.HOOK
                                    }
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!hasSo && !hasProp) {
                Log.e(TAG, "ZIP rejected: no .so or module.prop in $zipPath")
                return@withContext null
            }

            // Pass 2: extract with ZIP slip guard
            val destDir       = File(File(ctx.filesDir, MODULE_DIR_NAME), moduleName)
            val destCanonical = destDir.canonicalPath + File.separator
            destDir.mkdirs()

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        Log.e(TAG, "ZIP slip rejected: ${entry.name}")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            Log.i(TAG, "Module installed: $moduleName v$version by $author -> ${destDir.path}")

            TrinityModule(
                name        = moduleName,
                version     = version,
                description = description,
                author      = author,
                modulePath  = destDir.absolutePath,
                isEnabled   = false,
                type        = moduleType
            )
        } catch (e: Exception) {
            Log.e(TAG, "installModule failed: ${e.message}", e)
            null
        }
    }

    // Enable: find .so in module dir and dlopen it via absolute path.
    suspend fun enableModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try {
            val soFile = findLibrary(module.modulePath)
            if (soFile != null) {
                val ok = nativeLoadModule(soFile.absolutePath)
                Log.i(TAG, "enableModule ${module.name}: ${if (ok) "OK" else "FAILED"}")
            } else {
                Log.w(TAG, "No .so in ${module.modulePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableModule failed: ${e.message}")
        }
    }

    // Disable: unload from native registry.
    suspend fun disableModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try { nativeUnloadModule(module.name) }
        catch (e: Exception) { Log.e(TAG, "disableModule failed: ${e.message}") }
    }

    // Uninstall: unload + delete files.
    suspend fun uninstallModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try {
            nativeUnloadModule(module.name)
            File(module.modulePath).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "uninstallModule failed: ${e.message}")
        }
    }

    // Return installed modules from internal storage.
    suspend fun getInstalledModules(): List<TrinityModule> = withContext(Dispatchers.IO) {
        try {
            File(ctx.filesDir, MODULE_DIR_NAME)
                .listFiles { f -> f.isDirectory }
                ?.map { dir ->
                    TrinityModule(
                        name        = dir.name,
                        version     = "1.0",
                        description = "Trinity Module",
                        author      = "Unknown",
                        modulePath  = dir.absolutePath,
                        isEnabled   = false,
                        type        = ModuleType.HOOK
                    )
                }
                ?.sortedByDescending { it.installedAt }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledModules failed", e)
            emptyList()
        }
    }

    // Find the first .so file inside a module directory (checks ABI sub-dirs).
    private fun findLibrary(moduleDir: String): File? {
        val dir = File(moduleDir)
        val abiPaths = listOf("lib/arm64-v8a", "lib/armeabi-v7a", "lib/x86_64", "lib", ".")
        for (rel in abiPaths) {
            val so = File(dir, rel).listFiles { f -> f.extension == "so" }?.firstOrNull()
            if (so != null) return so
        }
        return null
    }

    // JNI: in-memory load via memfd_create - no filesystem trace.
    external fun nativeLoadModuleFromMemory(data: ByteArray, name: String): Boolean

    // JNI: file-based load - caller passes ABSOLUTE .so path.
    private external fun nativeLoadModule(soPath: String): Boolean

    // JNI: unload by name (registry).
    private external fun nativeUnloadModule(name: String): Boolean

    @Suppress("UNUSED")
    private external fun nativeInstallHook(target: String, hook: String): Boolean

    init {
        try {
            System.loadLibrary("trinity_module")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "trinity_module not available: ${e.message}")
        }
    }
}
