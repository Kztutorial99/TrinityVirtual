package com.trinityvirtual.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.trinityvirtual.R
import com.trinityvirtual.adapter.ModuleAdapter
import com.trinityvirtual.adapter.VirtualAppAdapter
import com.trinityvirtual.crash.CrashReporter
import com.trinityvirtual.databinding.ActivityMainBinding
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.model.VirtualApp
import com.trinityvirtual.module.ModuleManager
import com.trinityvirtual.spoof.DeviceSpoofManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var virtualAppAdapter: VirtualAppAdapter
    private lateinit var moduleAdapter: ModuleAdapter
    private lateinit var db: TrinityDatabase

    // ── File pickers ────────────────────────────────────────────────

    /**
     * Opens a system file picker — user manually selects .apk / .apks / .xapk
     * from local storage. No PackageManager dynamic loading is used.
     */
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                var name = "import.apk"
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) name = cursor.getString(idx)
                            }
                        }
                } catch (e: Exception) { }
                name
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext !in listOf("apk", "apks", "xapk")) {
                showSnackbar("Format tidak didukung. Gunakan .apk, .apks, atau .xapk")
                return@launch
            }

            showProgress("Mengimpor $fileName…")
            try {
                val app = VirtualCore.importFromUri(uri, fileName)
                if (app != null) {
                    db.virtualAppDao().insert(app)
                    showSnackbar(getString(R.string.clone_success, app.appName))
                    loadVirtualApps()
                } else {
                    showSnackbar("Gagal impor — pastikan file valid dan tidak rusak")
                }
            } catch (e: Exception) {
                Log.e("TrinityMain", "Import error", e)
                showSnackbar("Error: ${e.message ?: "Unknown"}")
            } finally {
                hideProgress()
            }
        }
    }

    private val pickModuleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            binding.progressModules.visibility = View.VISIBLE
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "module_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                    file
                }
                val module = ModuleManager.installModule(tempFile.absolutePath)
                if (module != null) {
                    db.moduleDao().insert(module)
                    showSnackbar("${module.name} berhasil diinstall!")
                    loadModules()
                } else {
                    showSnackbar("Gagal install module — pastikan ada file module.prop di ZIP")
                }
            } catch (e: Exception) {
                showSnackbar("Error: ${e.message}")
            } finally {
                binding.progressModules.visibility = View.GONE
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* silently handled */ }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = TrinityDatabase.getInstance(this)
        ModuleManager.init(this)
        try { DeviceSpoofManager.init(this) } catch (e: Throwable) {
            Log.e("TrinityMain", "Spoof init error (non-fatal)", e)
        }

        setupBottomNav()
        setupHomePage()
        setupModulesPage()
        setupSpoofPage()
        setupSettingsPage()
        checkAndRequestPermissions()
        loadVirtualApps()
        loadModules()
        updateRootStatus()
        checkPendingCrashReport()
    }

    override fun onResume() {
        super.onResume()
        loadVirtualApps()
        updateRootStatus()
        if (binding.pageSpoof.visibility == View.VISIBLE) refreshSpoofPage()
    }

    // ── Navigation ───────────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        binding.chipRootToggle.setOnClickListener { toggleRoot() }
    }

    private fun showPage(itemId: Int) {
        binding.pageHome.visibility     = View.GONE
        binding.pageModules.visibility  = View.GONE
        binding.pageSpoof.visibility    = View.GONE
        binding.pageSettings.visibility = View.GONE
        when (itemId) {
            R.id.nav_home     -> binding.pageHome.visibility = View.VISIBLE
            R.id.nav_modules  -> binding.pageModules.visibility = View.VISIBLE
            R.id.nav_spoof    -> { binding.pageSpoof.visibility = View.VISIBLE; refreshSpoofPage() }
            R.id.nav_settings -> binding.pageSettings.visibility = View.VISIBLE
        }
    }

    // ── Home page ─────────────────────────────────────────────────────

    private fun setupHomePage() {
        virtualAppAdapter = VirtualAppAdapter(
            onAppClick     = { app -> launchVirtualApp(app) },
            onAppLongClick = { app -> showAppMenu(app) }
        )
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter       = virtualAppAdapter

        binding.btnImportFile.setOnClickListener {
            importFileLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun loadVirtualApps() {
        lifecycleScope.launch {
            try {
                val apps = db.virtualAppDao().getAll()
                virtualAppAdapter.submitList(apps)
                binding.tvAppCount.text    = getString(R.string.app_count, apps.size)
                binding.layoutEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e("TrinityMain", "loadVirtualApps error", e)
            }
        }
    }

    private fun launchVirtualApp(app: VirtualApp) {
        startActivity(Intent(this, LauncherActivity::class.java).apply {
            putExtra("app_id",   app.id)
            putExtra("app_name", app.appName)
            putExtra("apk_path", app.apkPath)
        })
    }

    private fun showAppMenu(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(app.appName)
            .setMessage("v${app.versionName}  |  ${formatSize(app.sizeBytes)}  |  .${app.sourceType.uppercase()}")
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
                loadVirtualApps()
            } catch (e: Exception) { Log.e("TrinityMain", "toggleAppRoot", e) }
        }
    }

    private fun deleteApp(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus ${app.appName}?")
            .setMessage("File APK dan data sandbox akan dihapus permanen dari penyimpanan TrinityVirtual.")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.virtualAppDao().delete(app)
                        VirtualCore.deleteApp(app.apkPath)
                        loadVirtualApps()
                        showSnackbar("${app.appName} dihapus")
                    } catch (e: Exception) { Log.e("TrinityMain", "deleteApp", e) }
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    // ── Modules page ──────────────────────────────────────────────────

    private fun setupModulesPage() {
        moduleAdapter = ModuleAdapter(
            onToggle = { module, enabled ->
                lifecycleScope.launch {
                    if (enabled) ModuleManager.enableModule(module)
                    else ModuleManager.disableModule(module)
                    db.moduleDao().setEnabled(module.id, enabled)
                }
            },
            onDelete = { module ->
                lifecycleScope.launch {
                    ModuleManager.uninstallModule(module)
                    db.moduleDao().delete(module)
                    loadModules()
                }
            }
        )
        binding.rvModules.layoutManager = LinearLayoutManager(this)
        binding.rvModules.adapter       = moduleAdapter

        binding.btnAddModule.setOnClickListener {
            pickModuleLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    private fun loadModules() {
        lifecycleScope.launch {
            try {
                val modules = db.moduleDao().getAll()
                moduleAdapter.submitList(modules)
                binding.tvModulesEmpty.visibility = if (modules.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) { Log.e("TrinityMain", "loadModules", e) }
        }
    }

    // ── Spoof page ────────────────────────────────────────────────────

    private fun setupSpoofPage() {
        val presets = DeviceSpoofManager.getPresetProfiles()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets.map { it.profileName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSpoofPreset.adapter = adapter

        binding.switchSpoof.setOnCheckedChangeListener { _, checked ->
            try {
                if (checked) {
                    val sel = presets[binding.spinnerSpoofPreset.selectedItemPosition]
                    DeviceSpoofManager.enableSpoof(sel)
                    showSnackbar("Spoof diaktifkan: ${sel.profileName}")
                } else {
                    DeviceSpoofManager.disableSpoof()
                    showSnackbar("Spoof dinonaktifkan")
                }
            } catch (e: Throwable) { showSnackbar("Spoof error: ${e.message}") }
            refreshSpoofPage()
        }

        binding.btnApplySpoofPreset.setOnClickListener {
            try {
                val sel = presets[binding.spinnerSpoofPreset.selectedItemPosition]
                DeviceSpoofManager.enableSpoof(sel)
                binding.switchSpoof.isChecked = true
                showSnackbar("Preset diterapkan: ${sel.profileName}")
                refreshSpoofPage()
            } catch (e: Throwable) { showSnackbar("Gagal terapkan preset") }
        }

        binding.btnFullSpoofSettings.setOnClickListener {
            startActivity(Intent(this, SpoofSettingsActivity::class.java))
        }
    }

    private fun refreshSpoofPage() {
        try {
            val profile = DeviceSpoofManager.activeProfile
            val enabled = DeviceSpoofManager.isSpoofEnabled
            binding.switchSpoof.isChecked = enabled
            binding.tvSpoofStatus.text    = if (enabled) getString(R.string.spoof_status_on)
                                            else getString(R.string.spoof_status_off)
            binding.tvSpoofModel.text       = "${profile.manufacturer} ${profile.model}".trim().ifBlank { "—" }
            binding.tvSpoofFingerprint.text = profile.fingerprint.ifBlank { "—" }
            binding.switchGpsSpoof.isChecked = profile.gpsEnabled
            if (profile.gpsEnabled) {
                binding.etLatitude.setText(profile.latitude.toString())
                binding.etLongitude.setText(profile.longitude.toString())
            }
        } catch (e: Throwable) { Log.e("TrinityMain", "refreshSpoofPage error", e) }
    }

    // ── Settings page ─────────────────────────────────────────────────

    private fun setupSettingsPage() {
        binding.btnSettingsLauncher.setOnClickListener {
            startActivity(Intent(this, LauncherActivity::class.java))
        }
        binding.btnSettingsFullSpoof.setOnClickListener {
            startActivity(Intent(this, SpoofSettingsActivity::class.java))
        }
        binding.btnSettingsGeneral.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ── Root ──────────────────────────────────────────────────────────

    private fun toggleRoot() {
        lifecycleScope.launch {
            try {
                if (RootEngine.isRootActive) RootEngine.stopRootEnvironment()
                else RootEngine.startRootEnvironment()
                updateRootStatus()
            } catch (e: Exception) { showSnackbar("Root toggle gagal: ${e.message}") }
        }
    }

    private fun updateRootStatus() {
        try {
            val status = RootEngine.checkRootStatus()
            val (label, chipLabel, chipColor) = when (status) {
                RootEngine.RootStatus.ROOTED        -> Triple("Root: Aktif (Real)",    "Real Root",    getColor(R.color.root_active))
                RootEngine.RootStatus.VIRTUAL_ROOT  -> Triple("Root: Aktif (Virtual)", "Virtual Root", getColor(R.color.root_active))
                RootEngine.RootStatus.NO_ROOT       -> Triple("Root: Tidak Aktif",     "Virtual Root", getColor(R.color.root_inactive))
            }
            binding.tvRootStatus.text = label
            binding.chipRootToggle.text = chipLabel
            binding.chipRootToggle.chipBackgroundColor =
                android.content.res.ColorStateList.valueOf(chipColor)
        } catch (e: Exception) { Log.e("TrinityMain", "updateRootStatus", e) }
    }

    // ── Crash report ──────────────────────────────────────────────────

    private fun checkPendingCrashReport() {
        if (!CrashReporter.hasPendingCrash()) return
        val report  = CrashReporter.getLastCrashReport() ?: return
        val gistUrl = getSharedPreferences("trinity_prefs", MODE_PRIVATE).getString("last_gist_url", null)
        val msg = if (gistUrl != null) "Crash terdeteksi!\n\nReport: $gistUrl" else "Crash terdeteksi! Tersimpan lokal."
        MaterialAlertDialogBuilder(this)
            .setTitle("Crash Terdeteksi")
            .setMessage(msg)
            .setPositiveButton("Hapus") { _, _ -> CrashReporter.clearCrash() }
            .setNeutralButton("Lihat Log") { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle("Crash Log")
                    .setMessage(report.take(2000))
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }.show()
            }
            .setNegativeButton("Nanti", null).show()
    }

    // ── Permissions ───────────────────────────────────────────────────

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

    // ── Utils ─────────────────────────────────────────────────────────

    private fun showProgress(msg: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.visibility    = View.VISIBLE
        binding.tvStatus.text          = msg
    }

    private fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.visibility    = View.GONE
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val exp   = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(units.size - 1)
        return "%.1f %s".format(bytes / Math.pow(1024.0, exp.toDouble()), units[exp])
    }
}
