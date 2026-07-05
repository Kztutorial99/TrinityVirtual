package com.trinityvirtual.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trinityvirtual.databinding.ActivityLauncherBinding
import com.trinityvirtual.engine.RootEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    private var apkPath    = ""
    private var appName    = ""
    private var pkgName    = ""
    private var mainAct    = ""
    private var metaReady  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        appName = intent.getStringExtra("app_name") ?: "Virtual App"
        apkPath = intent.getStringExtra("apk_path") ?: ""

        supportActionBar?.title = appName
        binding.tvAppName.text  = appName
        binding.tvApkPath.text  = if (apkPath.isNotEmpty()) File(apkPath).name else "—"

        updateRootBadge()
        readApkMeta()

        binding.btnLaunchApp.setOnClickListener     { attemptVirtualLaunch() }
        binding.btnInstallSystem.setOnClickListener { askInstallSystem() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── APK metadata ──────────────────────────────────────────────────

    private fun readApkMeta() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (apkPath.isEmpty() || !File(apkPath).exists()) return@withContext null
                try {
                    @Suppress("DEPRECATION")
                    val pi = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
                    pi?.applicationInfo?.sourceDir = apkPath
                    pi?.applicationInfo?.publicSourceDir = apkPath
                    val pkg  = pi?.packageName ?: ""
                    val acts = pi?.activities ?: emptyArray()
                    val main = acts.firstOrNull { a ->
                        a.name.contains("Main",   ignoreCase = true) ||
                        a.name.contains("Launch", ignoreCase = true) ||
                        a.name.contains("Splash", ignoreCase = true)
                    }?.name ?: acts.firstOrNull()?.name ?: ""
                    Pair(pkg, main)
                } catch (e: Exception) {
                    Log.e("LauncherAct", "getPackageArchiveInfo failed: ${e.message}")
                    null
                }
            }
            if (result != null) {
                pkgName  = result.first
                mainAct  = result.second
                metaReady = true
                binding.tvApkPath.text = if (pkgName.isNotEmpty())
                    "$pkgName\n${File(apkPath).name}"
                else
                    File(apkPath).name
            }
        }
    }

    private fun updateRootBadge() {
        val status = RootEngine.checkRootStatus()
        binding.tvRootInfo.text = when (status) {
            RootEngine.RootStatus.VIRTUAL_ROOT ->
                "Virtual Root: Aktif — virtual launch tersedia"
            RootEngine.RootStatus.ROOTED ->
                "Root Nyata: Aktif — virtual launch tersedia"
            RootEngine.RootStatus.NO_ROOT ->
                "Root: Tidak Aktif — gunakan Install Sistem sebagai fallback"
        }
        val canVirtual = status != RootEngine.RootStatus.NO_ROOT
        binding.btnLaunchApp.isEnabled = canVirtual
        binding.btnLaunchApp.alpha     = if (canVirtual) 1f else 0.38f
    }

    // ── Virtual Launch ────────────────────────────────────────────────

    private fun attemptVirtualLaunch() {
        if (apkPath.isEmpty() || !File(apkPath).exists()) {
            showStatus("File APK tidak ditemukan:\n$apkPath", isError = true)
            return
        }
        if (!metaReady) {
            showStatus("Masih membaca metadata APK, coba lagi sebentar…")
            lifecycleScope.launch { delay(900); attemptVirtualLaunch() }
            return
        }
        lifecycleScope.launch {
            binding.btnLaunchApp.isEnabled = false
            showStatus("Memulai $appName di virtual environment…")

            val cmd = buildString {
                append("am start ")
                if (pkgName.isNotEmpty() && mainAct.isNotEmpty())
                    append("-n $pkgName/$mainAct")
                else if (pkgName.isNotEmpty())
                    append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkgName")
                else
                    append("--activity-clear-task -a android.intent.action.MAIN")
            }

            Log.d("LauncherAct", "Executing: $cmd")

            val result = withContext(Dispatchers.IO) {
                try { RootEngine.executeAsRoot(cmd) }
                catch (e: Exception) { "Exception: ${e.message}" }
            }

            Log.d("LauncherAct", "am start result: $result")

            val isError = result.contains("error", ignoreCase = true) ||
                          result.contains("exception", ignoreCase = true) ||
                          result.contains("not found", ignoreCase = true)

            showStatus(
                if (isError) "Virtual launch gagal:\n$result\n\nCoba gunakan Install Sistem."
                else         "$appName sedang berjalan di virtual environment.\n\nHasil: $result",
                isError
            )
            binding.btnLaunchApp.isEnabled = true
        }
    }

    // ── System Install fallback ───────────────────────────────────────

    private fun askInstallSystem() {
        if (apkPath.isEmpty() || !File(apkPath).exists()) {
            showStatus("File APK tidak ditemukan di:\n$apkPath", isError = true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Install ke Sistem?")
            .setMessage(
                "$appName akan diinstall sebagai app reguler menggunakan installer sistem Android.\n\n" +
                "APK tetap tersimpan aman di container private TrinityVirtual."
            )
            .setPositiveButton("Install") { _, _ -> doSystemInstall() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doSystemInstall() {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                File(apkPath)
            )
            startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            showStatus("Membuka installer sistem untuk $appName…")
        } catch (e: Exception) {
            showStatus("Install gagal: ${e.message}", isError = true)
            Log.e("LauncherAct", "System install failed", e)
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────

    private fun showStatus(msg: String, isError: Boolean = false) {
        binding.tvStatus.text       = msg
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.setTextColor(
            getColor(if (isError) com.trinityvirtual.R.color.error
                     else         com.trinityvirtual.R.color.on_surface_variant)
        )
    }
}
