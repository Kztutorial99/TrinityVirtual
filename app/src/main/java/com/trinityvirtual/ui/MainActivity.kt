package com.trinityvirtual.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.trinityvirtual.R
import com.trinityvirtual.adapter.VirtualAppAdapter
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.databinding.ActivityMainBinding
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.model.VirtualApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VirtualAppAdapter
    private lateinit var db: TrinityDatabase

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.installing)

                val tempFile = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    showSnackbar("File tidak valid atau kosong!")
                    return@launch
                }

                val app = VirtualCore.installApk(tempFile.absolutePath)
                if (app != null) {
                    db.virtualAppDao().insert(app)
                    showSnackbar("✅ ${app.appName} berhasil diinstall!")
                    loadApps()
                } else {
                    showSnackbar(getString(R.string.install_failed))
                }
            } catch (e: Exception) {
                Log.e("TrinityMain", "Install error", e)
                showSnackbar("Error install: ${e.message ?: "Unknown"}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = ""
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showSnackbar("Beberapa izin tidak diberikan — fitur tertentu mungkin tidak berfungsi")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        db = TrinityDatabase.getInstance(this)

        setupRecycler()
        setupButtons()
        checkAndRequestPermissions()
        loadApps()
        updateRootStatus()
        checkPendingCrashReport()
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermissionLauncher.launch(needed.toTypedArray())
    }

    private fun checkPendingCrashReport() {
        if (!CrashReporter.hasPendingCrash()) return
        val report = CrashReporter.getLastCrashReport() ?: return
        val gistUrl = getSharedPreferences("trinity_prefs", MODE_PRIVATE)
            .getString("last_gist_url", null)

        val msg = if (gistUrl != null)
            "App crash terdeteksi!\n\nReport telah dikirim ke:\n$gistUrl\n\nHapus report?"
        else
            "App crash terdeteksi! Report tersimpan lokal.\n\nHapus report?"

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Crash Terdeteksi")
            .setMessage(msg)
            .setPositiveButton("Hapus") { _, _ -> CrashReporter.clearCrash() }
            .setNeutralButton("Lihat Log") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Crash Log")
                    .setMessage(report.take(2000))
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun setupRecycler() {
        adapter = VirtualAppAdapter(
            onAppClick = { app -> launchVirtualApp(app) },
            onAppLongClick = { app -> showAppMenu(app) }
        )
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabAddApk.setOnClickListener { pickApkLauncher.launch("*/*") }
        binding.cardRootStatus.setOnClickListener { toggleRoot() }
        binding.btnModules.setOnClickListener {
            startActivity(Intent(this, ModuleManagerActivity::class.java))
        }
        binding.btnSpoof.setOnClickListener {
            startActivity(Intent(this, SpoofSettingsActivity::class.java))
        }
        binding.btnLauncher.setOnClickListener {
            startActivity(Intent(this, LauncherActivity::class.java))
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            try {
                val apps = db.virtualAppDao().getAll()
                adapter.submitList(apps)
                binding.tvEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                binding.tvAppCount.text = getString(R.string.app_count, apps.size)
            } catch (e: Exception) {
                Log.e("TrinityMain", "Load error", e)
            }
        }
    }

    private fun toggleRoot() {
        lifecycleScope.launch {
            try {
                if (RootEngine.isRootActive) RootEngine.stopRootEnvironment()
                else RootEngine.startRootEnvironment()
                updateRootStatus()
            } catch (e: Exception) {
                Log.e("TrinityMain", "Root toggle error", e)
                showSnackbar("Root toggle gagal: ${e.message}")
            }
        }
    }

    private fun updateRootStatus() {
        try {
            val status = RootEngine.checkRootStatus()
            binding.tvRootStatus.text = when (status) {
                RootEngine.RootStatus.ROOTED        -> "Root: Aktif (Real)"
                RootEngine.RootStatus.VIRTUAL_ROOT  -> "Root: Aktif (Virtual)"
                RootEngine.RootStatus.NO_ROOT       -> "Root: Tidak Aktif"
            }
            val color = when (status) {
                RootEngine.RootStatus.NO_ROOT -> getColor(R.color.root_inactive)
                else -> getColor(R.color.root_active)
            }
            binding.cardRootStatus.setCardBackgroundColor(color)
        } catch (e: Exception) {
            Log.e("TrinityMain", "Status update error", e)
        }
    }

    private fun launchVirtualApp(app: VirtualApp) {
        startActivity(Intent(this, LauncherActivity::class.java).apply {
            putExtra("app_id", app.id)
            putExtra("app_name", app.appName)
            putExtra("apk_path", app.apkPath)
        })
    }

    private fun showAppMenu(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(app.appName)
            .setItems(arrayOf("Buka", "Toggle Root", "Hapus")) { _, which ->
                when (which) {
                    0 -> launchVirtualApp(app)
                    1 -> toggleAppRoot(app)
                    2 -> deleteApp(app)
                }
            }.show()
    }

    private fun toggleAppRoot(app: VirtualApp) {
        lifecycleScope.launch {
            try {
                db.virtualAppDao().setRootEnabled(app.id, !app.rootEnabled)
                loadApps()
            } catch (e: Exception) { Log.e("TrinityMain", "toggleAppRoot error", e) }
        }
    }

    private fun deleteApp(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus ${app.appName}?")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.virtualAppDao().delete(app)
                        VirtualCore.deleteApp(app.apkPath)
                        loadApps()
                    } catch (e: Exception) { Log.e("TrinityMain", "deleteApp error", e) }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        updateRootStatus()
    }
}
