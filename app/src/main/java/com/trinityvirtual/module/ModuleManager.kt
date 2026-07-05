package com.trinityvirtual.module

import android.content.Context
import android.util.Log
import com.trinityvirtual.model.TrinityModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ModuleManager — Trinity module lifecycle + Stealth Pre-loader.
 *
 * Stealth Pre-loader:
 *  At app launch, scans [assets/modules/] for *.so (or *.trinity) files
 *  and loads each one directly into memory via [nativeLoadModuleFromMemory].
 *  The native side uses memfd_create() + dlopen("/proc/self/fd/N") so the
 *  library bytes never touch the filesystem and appear only as an anonymous
 *  [memfd:name] mapping in /proc/self/maps (which our preload lib filters out).
 *
 * File-based load (legacy):
 *  Modules installed from the UI are loaded via [nativeLoadModule] using the
 *  standard file path (kept for compatibility).
 */
object ModuleManager {

    private const val TAG              = "ModuleManager"
    private const val ASSET_MODULE_DIR = "modules"   // assets/modules/*.so

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    // ── Stealth Asset Pre-loader ─────────────────────────────────────────────

    /**
     * Silently loads all native modules bundled in assets/modules/ at startup.
     *
     * Flow:
     *  1. List entries under assets/modules/ via AssetManager
     *  2. For each .so / .trinity file, read all bytes into memory
     *  3. Pass bytes to native [nativeLoadModuleFromMemory]
     *  4. Native side: memfd_create → write → dlopen("/proc/self/fd/N")
     *  5. File never touches disk; /proc/self/maps entry is filtered
     *
     * Must be called from a background coroutine — I/O heavy.
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
                    val bytes = am.open("$ASSET_MODULE_DIR/$name").use { it.readBytes() }
                    val moduleName = name.removeSuffix(".trinity").removeSuffix(".so")
                    Log.d(TAG, "Loading module '$moduleName' (${bytes.size} bytes) in-memory")

                    val ok = nativeLoadModuleFromMemory(bytes, moduleName)
                    if (ok) {
                        loaded++
                        Log.i(TAG, "Module '$moduleName' loaded silently ✓")
                    } else {
                        failed++
                        Log.e(TAG, "Module '$moduleName' failed to load")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Exception loading module '$name': ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "preloadStealthModules error: ${e.message}")
        }

        Log.i(TAG, "Stealth preload complete: $loaded loaded, $failed failed")
        PreloadResult(loaded, failed)
    }

    data class PreloadResult(val loaded: Int, val failed: Int) {
        val allSucceeded get() = failed == 0
    }

    // ── File-based module API (UI-driven installs) ───────────────────────────

    suspend fun getInstalledModules(): List<TrinityModule> = withContext(Dispatchers.IO) {
        try {
            val moduleDir = File(ctx.filesDir, "modules")
            moduleDir.listFiles { f -> f.isDirectory }
                ?.map { dir ->
                    TrinityModule(
                        name        = dir.name,
                        packageName = dir.name,
                        version     = "1.0",
                        description = "Trinity Module",
                        enabled     = true,
                        path        = dir.absolutePath
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledModules failed", e)
            emptyList()
        }
    }

    suspend fun loadModule(module: TrinityModule): Boolean = withContext(Dispatchers.IO) {
        try {
            nativeLoadModule(module.path)
        } catch (e: Exception) {
            Log.e(TAG, "loadModule failed: ${e.message}")
            false
        }
    }

    suspend fun unloadModule(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            nativeUnloadModule(name)
        } catch (e: Exception) {
            Log.e(TAG, "unloadModule failed: ${e.message}")
            false
        }
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    /** Load .so from raw bytes via memfd_create — no filesystem trace. */
    external fun nativeLoadModuleFromMemory(data: ByteArray, name: String): Boolean

    /** Legacy file-based load. */
    private external fun nativeLoadModule(modulePath: String): Boolean

    /** Unload by name (tracked internally by C++ registry). */
    private external fun nativeUnloadModule(name: String): Boolean

    @Suppress("UNUSED")
    private external fun nativeInstallHook(target: String, hook: String): Boolean

    init {
        try {
            System.loadLibrary("trinity_module")
            Log.d(TAG, "trinity_module library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "trinity_module not available: ${e.message}")
        }
    }
}
