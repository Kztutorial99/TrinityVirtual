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

        // Install crash handler before anything else
        try {
            CrashReporter.install(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "CrashReporter init failed", e)
        }

        // Initialize private container storage for APK imports
        try {
            VirtualCore.init(this)
        } catch (e: Throwable) {
            Log.e("TrinityApp", "VirtualCore init failed (non-fatal)", e)
        }

        // Initialize root engine (loads native libs)
        try {
            RootEngine.init(this)
        } catch (e: Throwable) {
            Log.e("TrinityApp", "RootEngine init failed (non-fatal)", e)
        }

        // Pre-warm database on background thread
        try {
            TrinityDatabase.getInstance(this)
        } catch (e: Exception) {
            Log.e("TrinityApp", "Database init failed", e)
        }
    }
}
