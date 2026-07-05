package com.trinityvirtual.crash

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val TAG = "TrinityCrash"
    private const val CRASH_FILE = "last_crash.txt"
    private const val PREF_HAS_CRASH = "has_pending_crash"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.d(TAG, "CrashReporter initialized")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildCrashReport(thread, throwable)
            saveCrashToFile(report)
            sendToGistAsync(report)
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
        } catch (e: Exception) {
            Log.e(TAG, "CrashReporter itself crashed: ${e.message}")
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("=== TrinityVirtual Crash Report ===")
            appendLine("Timestamp   : $timestamp")
            appendLine("Thread      : ${thread.name}")
            appendLine("App Version : ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrDefault("unknown")}")
            appendLine("Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("CPU ABI     : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine()
            appendLine("=== Stack Trace ===")
            appendLine(sw.toString())
        }
    }

    private fun saveCrashToFile(report: String) {
        try {
            File(app.filesDir, CRASH_FILE).writeText(report)
            app.getSharedPreferences("trinity_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_HAS_CRASH, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash: ${e.message}")
        }
    }

    fun hasPendingCrash(): Boolean {
        return app.getSharedPreferences("trinity_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_HAS_CRASH, false)
    }

    fun getLastCrashReport(): String? {
        return try {
            val file = File(app.filesDir, CRASH_FILE)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) { null }
    }

    fun clearCrash() {
        app.getSharedPreferences("trinity_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HAS_CRASH, false).apply()
        File(app.filesDir, CRASH_FILE).delete()
    }

    private fun sendToGistAsync(report: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.github.com/gists")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "TrinityVirtual-CrashReporter/1.0")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val escaped = report.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                val body = """{"description":"TrinityVirtual Crash Report","public":false,"files":{"crash_report.txt":{"content":"$escaped"}}}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code == 201) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val gistUrl = Regex("\"html_url\":\"([^\"]+)\"").find(resp)?.groupValues?.getOrNull(1) ?: ""
                    Log.d(TAG, "Crash sent to Gist: $gistUrl")
                    app.getSharedPreferences("trinity_prefs", Context.MODE_PRIVATE)
                        .edit().putString("last_gist_url", gistUrl).apply()
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send crash to Gist: ${e.message}")
            }
        }
    }
}
