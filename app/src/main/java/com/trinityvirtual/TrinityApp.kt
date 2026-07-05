package com.trinityvirtual

import android.app.Application
import android.content.Context
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.spoof.DeviceSpoofManager

class TrinityApp : Application() {

    companion object {
        lateinit var instance: TrinityApp
            private set

        fun get(): TrinityApp = instance
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initEngines()
    }

    private fun initEngines() {
        VirtualCore.init(this)
        RootEngine.init(this)
        DeviceSpoofManager.init(this)
    }
}
