package com.trinityvirtual.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.trinityvirtual.databinding.ActivityLauncherBinding
import com.trinityvirtual.engine.RootEngine

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val appName = intent.getStringExtra("app_name") ?: "Virtual App"
        val apkPath = intent.getStringExtra("apk_path") ?: ""

        supportActionBar?.title = appName
        binding.tvAppName.text = appName
        binding.tvApkPath.text = "APK: $apkPath"

        val rootStatus = RootEngine.checkRootStatus()
        binding.tvRootInfo.text = when (rootStatus) {
            RootEngine.RootStatus.VIRTUAL_ROOT -> "Virtual Root: Aktif"
            RootEngine.RootStatus.ROOTED -> "Root: Aktif"
            else -> "Root: Tidak Aktif"
        }

        binding.btnLaunchApp.setOnClickListener {
            binding.tvStatus.text = "Memulai $appName di virtual environment..."
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
