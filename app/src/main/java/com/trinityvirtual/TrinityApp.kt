package com.trinityvirtual

import android.app.Application
import android.content.Context

/**
 * TrinityApp — singleton accessor untuk Application context.
 * Application class aktif: TrinityApplication (terdaftar di AndroidManifest.xml)
 * File ini hanya menyediakan static accessor agar komponen lain bisa akses context.
 */
object TrinityApp {

    private lateinit var _instance: Application

    fun init(app: Application) {
        _instance = app
    }

    fun get(): Application = _instance

    fun context(): Context = _instance.applicationContext
}
