package com.trinityvirtual.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VirtualAppAdapter
    private lateinit var db: TrinityDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        try {
            db = TrinityDatabase.getInstance(this)
            VirtualCore.init(this)
            RootEngine.init(this)
        } catch (e: Exception) {
            Log.e("TrinityMain", "Init error: ${e.message}", e)
        }

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
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                ))
            }
            startActivityForResult(Intent.createChooser(intent, "Pilih APK"), REQ_APK)
        } catch (e: Exception) {
            showSnackbar("Tidak bisa membuka file picker: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_APK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                try {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.installing)

                    val tempFile = withContext(Dispatchers.IO) {
                        val file = File(cacheDir, "install_temp_${System.currentTimeMillis()}.apk")
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        file
                    }

                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        showSnackbar("File APK kosong atau tidak valid!")
                        return@launch
                    }

                    val app = VirtualCore.installApk(tempFile.absolutePath)
                    if (app != null) {
                        db.virtualAppDao().insert(app)
                        showSnackbar("${app.appName} berhasil diinstall!")
                        loadApps()
                    } else {
                        showSnackbar(getString(R.string.install_failed))
                    }
                } catch (e: Exception) {
                    Log.e("TrinityMain", "Install error: ${e.message}", e)
                    showSnackbar("Error: ${e.message ?: "Unknown error"}")
                } finally {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = ""
                }
            }
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
                Log.e("TrinityMain", "Load error: ${e.message}", e)
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
                Log.e("TrinityMain", "Root toggle error: ${e.message}", e)
            }
        }
    }

    private fun updateRootStatus() {
        try {
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
        } catch (e: Exception) {
            Log.e("TrinityMain", "Status update error: ${e.message}", e)
        }
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
            try {
                db.virtualAppDao().setRootEnabled(app.id, !app.rootEnabled)
                loadApps()
            } catch (e: Exception) {
                Log.e("TrinityMain", "Toggle root error: ${e.message}", e)
            }
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
                    } catch (e: Exception) {
                        Log.e("TrinityMain", "Delete error: ${e.message}", e)
                    }
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

    companion object { private const val REQ_APK = 1001 }
}
