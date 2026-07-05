package com.trinityvirtual

import android.app.Application
import android.util.Log
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.module.ModuleManager
import com.trinityvirtual.spoof.PropHookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrinityApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // ── Step 0: Crash reporter (paling awal, tangkap semua exception) ────
        try { CrashReporter.init(this) } catch (e: Exception) {
            Log.e("TrinityApp", "CrashReporter init failed", e)
        }

        // ── Step 1: Integrity bypass hooks (HARUS PERTAMA sebelum semua engine)
        //
        //   PropHookManager.init() melakukan:
        //     • Load Samsung S23 Ultra prop profile ke tabel fakeroot_preload
        //     • Register semua suspicious patterns (qemu/goldfish/magisk/frida/…)
        //     • Hook __system_property_get + fopen(/proc/self/maps) sudah aktif
        //
        //   Ini KRUSIAL: semua engine di bawah + proses guest yang di-spawn
        //   (termasuk Free Fire) akan mewarisi hook ini via LD_PRELOAD / RTLD_GLOBAL.
        // ─────────────────────────────────────────────────────────────────────
        try {
            PropHookManager.init()
            Log.i("TrinityApp", "PropHookManager: integrity hooks active ✓")
        } catch (e: Throwable) {
            Log.e("TrinityApp", "PropHookManager init failed (non-fatal): ${e.message}")
        }

        // ── Step 2: VirtualCore (container dir setup, tidak spawn proses) ────
        try { VirtualCore.init(this) } catch (e: Throwable) {
            Log.e("TrinityApp", "VirtualCore init failed (non-fatal)", e)
        }

        // ── Step 3: RootEngine (setup LD_PRELOAD, deploy fake-su, set PATH) ──
        try { RootEngine.init(this) } catch (e: Throwable) {
            Log.e("TrinityApp", "RootEngine init failed (non-fatal)", e)
        }

        // ── Step 4: ModuleManager + stealth preloader (background, non-blocking)
        try {
            ModuleManager.init(this)
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

        // ── Step 5: Database ─────────────────────────────────────────────────
        try { TrinityDatabase.getInstance(this) } catch (e: Exception) {
            Log.e("TrinityApp", "Database init failed", e)
        }
    }
}
