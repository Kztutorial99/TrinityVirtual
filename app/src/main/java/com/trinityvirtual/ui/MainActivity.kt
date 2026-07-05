package com.trinityvirtual.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.trinityvirtual.R
import com.trinityvirtual.adapter.VirtualAppAdapter
import com.trinityvirtual.databinding.ActivityMainBinding
import com.trinityvirtual.engine.RootEngine
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.engine.VirtualCore
import com.trinityvirtual.model.VirtualApp
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VirtualAppAdapter
    private lateinit var db: TrinityDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        db = TrinityDatabase.getInstance(this)
        setupRecycler()
        setupButtons()
        loadApps()
        updateRootStatus()
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
        binding.fabAddApk.setOnClickListener { pickApk() }
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

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Pilih APK"), REQ_APK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_APK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch {
                    val tempFile = java.io.File(cacheDir, "install_temp.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    installApk(tempFile.absolutePath)
                }
            }
        }
    }

    private suspend fun installApk(path: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.installing)
        val app = VirtualCore.installApk(path)
        if (app != null) {
            db.virtualAppDao().insert(app)
            Snackbar.make(binding.root, "${app.appName} berhasil diinstall!", Snackbar.LENGTH_SHORT).show()
            loadApps()
        } else {
            Snackbar.make(binding.root, getString(R.string.install_failed), Snackbar.LENGTH_SHORT).show()
        }
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = ""
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = db.virtualAppDao().getAll()
            adapter.submitList(apps)
            binding.tvEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            binding.tvAppCount.text = getString(R.string.app_count, apps.size)
        }
    }

    private fun toggleRoot() {
        lifecycleScope.launch {
            if (RootEngine.isRootActive) {
                RootEngine.stopRootEnvironment()
            } else {
                RootEngine.startRootEnvironment()
            }
            updateRootStatus()
        }
    }

    private fun updateRootStatus() {
        val status = RootEngine.checkRootStatus()
        binding.tvRootStatus.text = when (status) {
            RootEngine.RootStatus.ROOTED -> "Root: Aktif (Real)"
            RootEngine.RootStatus.VIRTUAL_ROOT -> "Root: Aktif (Virtual)"
            RootEngine.RootStatus.NO_ROOT -> "Root: Tidak Aktif"
        }
        val color = when (status) {
            RootEngine.RootStatus.NO_ROOT -> getColor(R.color.root_inactive)
            else -> getColor(R.color.root_active)
        }
        binding.cardRootStatus.setCardBackgroundColor(color)
    }

    private fun launchVirtualApp(app: VirtualApp) {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            putExtra("app_id", app.id)
            putExtra("app_name", app.appName)
            putExtra("apk_path", app.apkPath)
        }
        startActivity(intent)
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
            db.virtualAppDao().setRootEnabled(app.id, !app.rootEnabled)
            loadApps()
        }
    }

    private fun deleteApp(app: VirtualApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus ${app.appName}?")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    db.virtualAppDao().delete(app)
                    VirtualCore.deleteApp(app.apkPath)
                    loadApps()
                }
            }.setNegativeButton("Batal", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() { super.onResume(); loadApps(); updateRootStatus() }

    companion object { private const val REQ_APK = 1001 }
}
