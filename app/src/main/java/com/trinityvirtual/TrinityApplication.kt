package com.trinityvirtual

import android.app.Application
import android.util.Log
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.VirtualCore

class TrinityApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        TrinityApp.init(this)
        CrashReporter.init(this)

        try {
            VirtualCore.init(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "VirtualCore init failed: ${e.message}", e)
        }

        try {
            RootEngine.init(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "RootEngine init failed: ${e.message}", e)
        }

        Log.d("TrinityApp", "TrinityVirtual started successfully")
    }
}
