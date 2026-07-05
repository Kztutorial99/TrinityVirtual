package com.trinityvirtual

import android.app.Application
import android.util.Log
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore

class TrinityApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            CrashReporter.init(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "CrashReporter init failed", e)
        }

        try {
            VirtualCore.init(this)
        } catch (e: Throwable) {
            Log.e("TrinityApp", "VirtualCore init failed (non-fatal)", e)
        }

        try {
            RootEngine.init(this)
        } catch (e: Throwable) {
            Log.e("TrinityApp", "RootEngine init failed (non-fatal)", e)
        }

        try {
            TrinityDatabase.getInstance(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "Database init failed", e)
        }
    }
}
