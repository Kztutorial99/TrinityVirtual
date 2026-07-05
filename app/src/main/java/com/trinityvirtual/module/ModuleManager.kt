package com.trinityvirtual.module

import android.content.Context
import android.util.Log
import com.trinityvirtual.model.ModuleType
import com.trinityvirtual.model.TrinityModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ModuleManager — Trinity module lifecycle + Stealth Pre-loader.
 *
 * Stealth Pre-loader:
 *  At app launch, scans [assets/modules/] for *.so / *.trinity files
 *  and loads each one directly into memory via [nativeLoadModuleFromMemory].
 *  Native side: memfd_create() + dlopen("/proc/self/fd/N") — zero filesystem trace.
 *
 * File-based install (UI-driven, ZIP packages):
 *  [installModule]  — extract ZIP to internal storage, return TrinityModule
 *  [enableModule]   — dlopen the module lib
 *  [disableModule]  — mark inactive (dlclose not supported yet)
 *  [uninstallModule]— delete from internal storage
 */
object ModuleManager {

    private const val TAG              = "ModuleManager"
    private const val ASSET_MODULE_DIR = "modules"     // assets/modules/*.so
    private const val MODULE_DIR_NAME  = "modules"     // filesDir/modules/

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        File(ctx.filesDir, MODULE_DIR_NAME).mkdirs()
    }

    // ── Stealth Asset Pre-loader ─────────────────────────────────────────────

    /**
     * Silently loads all native modules bundled in assets/modules/ at startup.
     * Must be called from a background coroutine.
     */
    suspend fun preloadStealthModules(): PreloadResult = withContext(Dispatchers.IO) {
        var loaded = 0; var failed = 0
        try {
            val am      = ctx.assets
            val entries = try { am.list(ASSET_MODULE_DIR) } catch (e: Exception) { null }

            if (entries.isNullOrEmpty()) {
                Log.d(TAG, "No modules found in assets/$ASSET_MODULE_DIR")
                return@withContext PreloadResult(0, 0)
            }

            Log.i(TAG, "Stealth preloader: ${entries.size} module(s) found in assets")

            for (name in entries) {
                if (!name.endsWith(".so") && !name.endsWith(".trinity")) continue
                try {
                    val bytes      = am.open("$ASSET_MODULE_DIR/$name").use { it.readBytes() }
                    val moduleName = name.removeSuffix(".trinity").removeSuffix(".so")
                    Log.d(TAG, "Loading '$moduleName' in-memory (${bytes.size} bytes)")

                    if (nativeLoadModuleFromMemory(bytes, moduleName)) {
                        loaded++
                        Log.i(TAG, "Module '$moduleName' loaded silently ✓")
                    } else {
                        failed++
                        Log.e(TAG, "Module '$moduleName' failed")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Exception loading '$name': ${e.message}")
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

    // ── ZIP-based install (called from UI) ───────────────────────────────────

    /**
     * Install a module from a ZIP file path.
     * Extracts ZIP → [filesDir/modules/<name>/], reads manifest, returns TrinityModule.
     * @return TrinityModule on success, null on failure.
     */
    suspend fun installModule(zipPath: String): TrinityModule? = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) { Log.e(TAG, "ZIP not found: $zipPath"); return@withContext null }

            // Read module manifest from zip first
            var moduleName    = zipFile.nameWithoutExtension
            var version       = "1.0"
            var description   = "Trinity Module"
            var author        = "Unknown"
            var moduleType    = ModuleType.HOOK

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "module.prop" || entry.name == "META-INF/module.prop") {
                        val props = zis.bufferedReader().readText()
                        props.lines().forEach { line ->
                            val kv = line.split("=", limit = 2)
                            if (kv.size == 2) when (kv[0].trim()) {
                                "name"        -> moduleName  = kv[1].trim()
                                "version"     -> version     = kv[1].trim()
                                "description" -> description = kv[1].trim()
                                "author"      -> author      = kv[1].trim()
                                "type"        -> moduleType  = when (kv[1].trim().uppercase()) {
                                    "XPOSED"  -> ModuleType.XPOSED
                                    "SYSTEM"  -> ModuleType.SYSTEM
                                    "SPOOF"   -> ModuleType.SPOOF
                                    else      -> ModuleType.HOOK
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // Extract ZIP to internal storage
            val destDir = File(File(ctx.filesDir, MODULE_DIR_NAME), moduleName)
            destDir.mkdirs()

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) { outFile.mkdirs() }
                    else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    entry = zis.nextEntry
                }
            }

            Log.i(TAG, "Module installed: $moduleName v$version by $author → ${destDir.path}")

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

    /**
     * Enable (load) an installed module.
     */
    suspend fun enableModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try {
            val libFile = findLibrary(module.modulePath)
            if (libFile != null) {
                val ok = nativeLoadModule(libFile.absolutePath)
                Log.i(TAG, "enableModule '${module.name}': ${if (ok) "OK ✓" else "FAILED ✗"}")
            } else {
                // Try in-memory load if no .so found on disk but has raw bytes concept
                Log.w(TAG, "No .so found in ${module.modulePath} — module may already be in memory")
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableModule failed: ${e.message}")
        }
    }

    /**
     * Disable a module (marks inactive; actual dlclose not supported in this version).
     */
    suspend fun disableModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try {
            nativeUnloadModule(module.name)
            Log.i(TAG, "disableModule '${module.name}' OK")
        } catch (e: Exception) {
            Log.e(TAG, "disableModule failed: ${e.message}")
        }
    }

    /**
     * Uninstall — remove from filesystem.
     */
    suspend fun uninstallModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        try {
            nativeUnloadModule(module.name)
            File(module.modulePath).deleteRecursively()
            Log.i(TAG, "uninstallModule '${module.name}' — files deleted")
        } catch (e: Exception) {
            Log.e(TAG, "uninstallModule failed: ${e.message}")
        }
    }

    /**
     * Return list of all installed modules from internal storage.
     */
    suspend fun getInstalledModules(): List<TrinityModule> = withContext(Dispatchers.IO) {
        try {
            val moduleDir = File(ctx.filesDir, MODULE_DIR_NAME)
            moduleDir.listFiles { f -> f.isDirectory }
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

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun findLibrary(moduleDir: String): File? {
        val dir = File(moduleDir)
        // Check ABI-specific paths first, then root
        val abiPaths = listOf("lib/arm64-v8a", "lib/armeabi-v7a", "lib/x86_64", "lib", ".")
        for (rel in abiPaths) {
            val subDir = File(dir, rel)
            val so = subDir.listFiles { f -> f.extension == "so" }?.firstOrNull()
            if (so != null) return so
        }
        return null
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    /** Load .so from raw bytes via memfd_create — no filesystem trace. */
    external fun nativeLoadModuleFromMemory(data: ByteArray, name: String): Boolean

    /** Legacy file-based load by absolute .so path. */
    private external fun nativeLoadModule(soPath: String): Boolean

    /** Unload module by name. */
    private external fun nativeUnloadModule(name: String): Boolean

    @Suppress("UNUSED")
    private external fun nativeInstallHook(target: String, hook: String): Boolean

    init {
        try {
            System.loadLibrary("trinity_module")
            Log.d(TAG, "trinity_module loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "trinity_module not available: ${e.message}")
        }
    }
}
