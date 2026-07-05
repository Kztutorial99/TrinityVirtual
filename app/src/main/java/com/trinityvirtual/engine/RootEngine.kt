package com.trinityvirtual.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RootEngine — Hybrid virtual root using Linux namespaces + fake su binary.
 *
 * Approach:
 * 1. Deploy a pre-compiled fake-su binary into the app's private dir
 * 2. Use Linux mount namespaces to create an isolated root environment
 * 3. Inside the namespace, /su resolves to our fake-su binary
 * 4. Apps running inside VirtualCore believe they have root (uid=0)
 * 5. For deeper root ops, a QEMU user-mode process handles privileged calls
 */
object RootEngine {

    private const val TAG = "TrinityRootEngine"
    private lateinit var ctx: Context
    private lateinit var rootDir: File
    private lateinit var suBinary: File
    private lateinit var qemuBinary: File

    var isRootActive: Boolean = false
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        rootDir = File(ctx.filesDir, "root_engine").also { it.mkdirs() }
        suBinary = File(rootDir, "su")
        qemuBinary = File(rootDir, "qemu-user")
        deployNativeBinaries()
    }

    private fun deployNativeBinaries() {
        try {
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val libSu = File(nativeLibDir, "libtrinity_su.so")
            val libQemu = File(nativeLibDir, "libtrinity_qemu.so")
            if (libSu.exists()) libSu.copyTo(suBinary, overwrite = true)
            if (libQemu.exists()) libQemu.copyTo(qemuBinary, overwrite = true)
            suBinary.setExecutable(true, false)
            qemuBinary.setExecutable(true, false)
            Log.d(TAG, "Native binaries deployed: su=${suBinary.exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy binaries: ${e.message}")
        }
    }

    suspend fun startRootEnvironment(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = nativeStartRootNamespace(
                rootDir.absolutePath,
                suBinary.absolutePath
            )
            isRootActive = result == 0
            Log.d(TAG, "Root environment started: $isRootActive")
            isRootActive
        } catch (e: Exception) {
            Log.e(TAG, "Root start failed: ${e.message}")
            false
        }
    }

    suspend fun stopRootEnvironment() = withContext(Dispatchers.IO) {
        try {
            nativeStopRootNamespace()
            isRootActive = false
        } catch (e: Exception) {
            Log.e(TAG, "Root stop failed: ${e.message}")
        }
    }

    fun checkRootStatus(): RootStatus {
        return try {
            val uid = nativeGetEffectiveUid()
            when {
                uid == 0 -> RootStatus.ROOTED
                isRootActive -> RootStatus.VIRTUAL_ROOT
                else -> RootStatus.NO_ROOT
            }
        } catch (e: Exception) {
            RootStatus.NO_ROOT
        }
    }

    suspend fun executeAsRoot(command: String): String = withContext(Dispatchers.IO) {
        try {
            nativeExecuteRoot(command)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // JNI Native Methods
    private external fun nativeStartRootNamespace(rootDir: String, suPath: String): Int
    private external fun nativeStopRootNamespace(): Int
    private external fun nativeGetEffectiveUid(): Int
    private external fun nativeExecuteRoot(command: String): String

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
