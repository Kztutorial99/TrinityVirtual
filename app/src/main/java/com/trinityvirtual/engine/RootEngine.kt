package com.trinityvirtual.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RootEngine — Virtual root via LD_PRELOAD library injection.
 *
 * Replaces the former Linux mount-namespace approach which is blocked by
 * SELinux (untrusted_app domain) on Android 12+ with the error:
 *   "Failed to unshare mount namespace: Operation not permitted"
 *
 * New approach — zero kernel namespace changes required:
 *  1. libfakeroot_preload.so is compiled into the APK by CMake (fakeroot_preload target).
 *  2. At startup, its path in nativeLibraryDir is passed to nativeStartRootNamespace.
 *  3. The C++ engine:
 *       a. setenv("LD_PRELOAD", path)          — all child processes inherit this
 *       b. dlopen(path, RTLD_NOW | RTLD_GLOBAL) — host process injection
 *       c. Deploys fake-su shell script to rootDir and prepends to PATH
 *  4. Guest apps and child processes see getuid()=0 and a working su binary.
 *
 * SELinux impact: none — no unshare(), mount(), or CLONE_NEWNS used.
 */
object RootEngine {

    private const val TAG = "TrinityRootEngine"

    private lateinit var ctx: Context
    private lateinit var rootDir: File
    private lateinit var preloadLib: File   // libfakeroot_preload.so

    var isRootActive: Boolean = false
        private set

    // ── Initialise ───────────────────────────────────────────────────────────

    fun init(context: Context) {
        ctx     = context.applicationContext
        rootDir = File(ctx.filesDir, "root_engine").also { it.mkdirs() }

        // libfakeroot_preload.so is placed in nativeLibraryDir by the build system.
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        preloadLib = File(nativeLibDir, "libfakeroot_preload.so")

        Log.d(TAG, "RootEngine init")
        Log.d(TAG, "  rootDir    : ${rootDir.absolutePath}")
        Log.d(TAG, "  preloadLib : ${preloadLib.absolutePath}  exists=${preloadLib.exists()}")
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    /**
     * Activate virtual root via LD_PRELOAD injection.
     * Must be called from a background coroutine (IO dispatcher).
     *
     * @return true if injection succeeded.
     */
    suspend fun startRootEnvironment(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!::preloadLib.isInitialized) {
                Log.e(TAG, "RootEngine.init() belum dipanggil — panggil init(context) di onCreate terlebih dahulu")
                return@withContext false
            }
            if (!preloadLib.exists()) {
                Log.e(TAG, "libfakeroot_preload.so not found in nativeLibraryDir — cannot inject")
                Log.e(TAG, "  expected: ${preloadLib.absolutePath}")
                return@withContext false
            }

            val rc = nativeStartRootNamespace(
                rootDir.absolutePath,
                preloadLib.absolutePath   // preload lib path — NOT a su binary
            )
            isRootActive = (rc == 0)

            if (isRootActive) {
                Log.i(TAG, "LD_PRELOAD virtual root active (no mount namespace)")
            } else {
                Log.e(TAG, "nativeStartRootNamespace returned $rc")
            }
            isRootActive
        } catch (e: Exception) {
            Log.e(TAG, "startRootEnvironment failed: ${e.message}")
            false
        }
    }

    suspend fun stopRootEnvironment() = withContext(Dispatchers.IO) {
        if (!::preloadLib.isInitialized) return@withContext
        try {
            nativeStopRootNamespace()
            isRootActive = false
            Log.i(TAG, "Virtual root stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopRootEnvironment failed: ${e.message}")
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun checkRootStatus(): RootStatus {
        return try {
            val uid = nativeGetEffectiveUid()
            when {
                uid == 0 && isRootActive -> RootStatus.VIRTUAL_ROOT
                uid == 0                 -> RootStatus.ROOTED        // unexpected real root
                else                     -> RootStatus.NO_ROOT
            }
        } catch (e: Exception) {
            RootStatus.NO_ROOT
        }
    }

    /** True when libfakeroot_preload.so is resident in the process. */
    fun isInjectionActive(): Boolean = try {
        nativeIsInjectionActive()
    } catch (e: Exception) {
        false
    }

    // ── Command execution ──────────────────────────────────────────────────────

    /**
     * Execute a shell command in the virtual-root environment.
     * popen() inherits LD_PRELOAD from the process env, so the child process
     * automatically loads libfakeroot_preload.so and sees uid=0.
     */
    suspend fun executeAsRoot(command: String): String = withContext(Dispatchers.IO) {
        try {
            nativeExecuteRoot(command)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── JNI declarations ───────────────────────────────────────────────────────

    /**
     * @param rootDir        App-private directory for the fake root env.
     * @param preloadLibPath Absolute path to libfakeroot_preload.so.
     * @return 0 on success.
     */
    private external fun nativeStartRootNamespace(rootDir: String, preloadLibPath: String): Int
    private external fun nativeStopRootNamespace(): Int
    private external fun nativeGetEffectiveUid(): Int
    private external fun nativeExecuteRoot(command: String): String
    private external fun nativeIsInjectionActive(): Boolean

    init {
        try {
            System.loadLibrary("trinity_root")
            Log.d(TAG, "Native root library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available: ${e.message}")
        }
    }

    enum class RootStatus {
        ROOTED, VIRTUAL_ROOT, NO_ROOT
    }
}
