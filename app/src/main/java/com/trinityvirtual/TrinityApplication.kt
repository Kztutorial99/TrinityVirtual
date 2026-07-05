package com.trinityvirtual

import android.app.Application
import android.util.Log
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.module.ModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrinityApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        try { CrashReporter.init(this) } catch (e: Exception) {
            Log.e("TrinityApp", "CrashReporter init failed", e)
        }

        try { VirtualCore.init(this) } catch (e: Throwable) {
            Log.e("TrinityApp", "VirtualCore init failed (non-fatal)", e)
        }

        try { RootEngine.init(this) } catch (e: Throwable) {
            Log.e("TrinityApp", "RootEngine init failed (non-fatal)", e)
        }

        try {
            ModuleManager.init(this)
            // Silently preload all modules from assets/modules/ in background
            appScope.launch {
                try {
                    val result = ModuleManager.preloadStealthModules()
                    Log.i("TrinityApp",
                        "Stealth preload done: ${result.loaded} loaded, ${result.failed} failed")
                } catch (e: Exception) {
                    Log.e("TrinityApp", "Stealth preload error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e("TrinityApp", "ModuleManager init failed (non-fatal)", e)
        }

        try { TrinityDatabase.getInstance(this) } catch (e: Exception) {
            Log.e("TrinityApp", "Database init failed", e)
        }
    }
}
