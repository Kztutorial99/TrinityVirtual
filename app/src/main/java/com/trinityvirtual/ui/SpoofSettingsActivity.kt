package com.trinityvirtual.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.trinityvirtual.R
import com.trinityvirtual.databinding.ActivitySpoofSettingsBinding
import com.trinityvirtual.model.SpoofProfile
import com.trinityvirtual.spoof.DeviceSpoofManager

class SpoofSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpoofSettingsBinding
    private val presets by lazy { DeviceSpoofManager.getPresetProfiles() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpoofSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Device Spoof Settings"

        setupPresets()
        loadCurrentProfile()
        setupButtons()
    }

    private fun setupPresets() {
        val presetNames = presets.map { it.profileName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPreset.adapter = adapter
        binding.spinnerPreset.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                applyPresetToForm(presets[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun applyPresetToForm(profile: SpoofProfile) {
        binding.etManufacturer.setText(profile.manufacturer)
        binding.etModel.setText(profile.model)
        binding.etBrand.setText(profile.brand)
        binding.etDevice.setText(profile.device)
        binding.etFingerprint.setText(profile.fingerprint)
        binding.etAndroidVersion.setText(profile.androidVersion)
        binding.etBuildId.setText(profile.buildId)
    }

    private fun loadCurrentProfile() {
        val p = DeviceSpoofManager.activeProfile
        applyPresetToForm(p)
        binding.switchSpoof.isChecked = DeviceSpoofManager.isSpoofEnabled
        binding.switchGps.isChecked = p.gpsEnabled
        binding.etLatitude.setText(p.latitude.toString())
        binding.etLongitude.setText(p.longitude.toString())
        binding.etImei.setText(p.imei)
        binding.etAndroidId.setText(p.androidId)
    }

    private fun setupButtons() {
        binding.btnApplySpoof.setOnClickListener {
            val profile = buildProfileFromForm()
            if (binding.switchSpoof.isChecked) {
                DeviceSpoofManager.enableSpoof(profile)
                Snackbar.make(binding.root, "Spoof diaktifkan!", Snackbar.LENGTH_SHORT).show()
            } else {
                DeviceSpoofManager.disableSpoof()
                Snackbar.make(binding.root, "Spoof dinonaktifkan", Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.btnReset.setOnClickListener {
            DeviceSpoofManager.disableSpoof()
            loadCurrentProfile()
            Snackbar.make(binding.root, "Spoof direset", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun buildProfileFromForm() = SpoofProfile(
        manufacturer = binding.etManufacturer.text.toString(),
        model = binding.etModel.text.toString(),
        brand = binding.etBrand.text.toString(),
        device = binding.etDevice.text.toString(),
        fingerprint = binding.etFingerprint.text.toString(),
        androidVersion = binding.etAndroidVersion.text.toString(),
        buildId = binding.etBuildId.text.toString(),
        gpsEnabled = binding.switchGps.isChecked,
        latitude = binding.etLatitude.text.toString().toDoubleOrNull() ?: 0.0,
        longitude = binding.etLongitude.text.toString().toDoubleOrNull() ?: 0.0,
        imei = binding.etImei.text.toString(),
        androidId = binding.etAndroidId.text.toString()
    )

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
