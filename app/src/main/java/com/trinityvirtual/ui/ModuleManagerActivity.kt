package com.trinityvirtual.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.trinityvirtual.R
import com.trinityvirtual.adapter.ModuleAdapter
import com.trinityvirtual.databinding.ActivityModuleManagerBinding
import com.trinityvirtual.engine.TrinityDatabase
import com.trinityvirtual.model.TrinityModule
import com.trinityvirtual.module.ModuleManager
import kotlinx.coroutines.launch

class ModuleManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModuleManagerBinding
    private lateinit var adapter: ModuleAdapter
    private lateinit var db: TrinityDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModuleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Module Manager"

        db = TrinityDatabase.getInstance(this)
        ModuleManager.init(this)

        setupRecycler()
        loadModules()

        binding.fabAddModule.setOnClickListener { pickModule() }
    }

    private fun setupRecycler() {
        adapter = ModuleAdapter(
            onToggle = { module, enabled -> toggleModule(module, enabled) },
            onDelete = { module -> deleteModule(module) }
        )
        binding.rvModules.layoutManager = LinearLayoutManager(this)
        binding.rvModules.adapter = adapter
    }

    private fun pickModule() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Pilih Module ZIP"), REQ_MODULE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MODULE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    val tempFile = java.io.File(cacheDir, "module_temp.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val module = ModuleManager.installModule(tempFile.absolutePath)
                    if (module != null) {
                        db.moduleDao().insert(module)
                        Snackbar.make(binding.root, "${module.name} berhasil diinstall!", Snackbar.LENGTH_SHORT).show()
                        loadModules()
                    } else {
                        Snackbar.make(binding.root, "Gagal install module", Snackbar.LENGTH_SHORT).show()
                    }
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun loadModules() {
        lifecycleScope.launch {
            val modules = db.moduleDao().getAll()
            adapter.submitList(modules)
            binding.tvEmpty.visibility = if (modules.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun toggleModule(module: TrinityModule, enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) ModuleManager.enableModule(module)
            else ModuleManager.disableModule(module)
            db.moduleDao().setEnabled(module.id, enabled)
        }
    }

    private fun deleteModule(module: TrinityModule) {
        lifecycleScope.launch {
            ModuleManager.uninstallModule(module)
            db.moduleDao().delete(module)
            loadModules()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    companion object { private const val REQ_MODULE = 2001 }
}
