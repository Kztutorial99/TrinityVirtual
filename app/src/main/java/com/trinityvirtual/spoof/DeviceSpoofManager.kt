package com.trinityvirtual.spoof

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.trinityvirtual.model.SpoofProfile

object DeviceSpoofManager {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    var activeProfile: SpoofProfile = SpoofProfile()
        private set
    var isSpoofEnabled: Boolean = false
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("trinity_spoof", Context.MODE_PRIVATE)
        loadProfile()
        isSpoofEnabled = prefs.getBoolean("spoof_enabled", false)
        if (isSpoofEnabled) applySpoof()
    }

    fun enableSpoof(profile: SpoofProfile) {
        activeProfile = profile
        isSpoofEnabled = true
        saveProfile(profile)
        prefs.edit().putBoolean("spoof_enabled", true).apply()
        applySpoof()
    }

    fun disableSpoof() {
        isSpoofEnabled = false
        prefs.edit().putBoolean("spoof_enabled", false).apply()
        nativeResetSpoof()
    }

    fun updateProfile(profile: SpoofProfile) {
        activeProfile = profile
        saveProfile(profile)
        if (isSpoofEnabled) applySpoof()
    }

    private fun applySpoof() {
        with(activeProfile) {
            nativeApplyDeviceSpoof(
                manufacturer, model, brand, device,
                product, fingerprint, androidVersion,
                sdkInt, buildId, serialNumber
            )
            if (gpsEnabled) nativeApplyGpsSpoof(latitude, longitude)
            if (imei.isNotEmpty()) nativeApplyImeiSpoof(imei)
            if (androidId.isNotEmpty()) nativeApplyAndroidIdSpoof(androidId)
        }
    }

    private fun saveProfile(profile: SpoofProfile) {
        prefs.edit().putString("active_profile", gson.toJson(profile)).apply()
    }

    private fun loadProfile() {
        val json = prefs.getString("active_profile", null)
        activeProfile = if (json != null) gson.fromJson(json, SpoofProfile::class.java)
        else SpoofProfile()
    }

    fun getPresetProfiles(): List<SpoofProfile> = listOf(
        SpoofProfile(profileName = "Samsung Galaxy S23 Ultra",
            manufacturer = "Samsung", model = "SM-S918B", brand = "samsung",
            device = "dm3q", product = "dm3qxx",
            fingerprint = "samsung/dm3qxx/dm3q:14/UP1A.231005.007/S918BXXS3CXA1:user/release-keys",
            androidVersion = "14", sdkInt = 34, buildId = "UP1A.231005.007"),
        SpoofProfile(profileName = "Google Pixel 8 Pro",
            manufacturer = "Google", model = "Pixel 8 Pro", brand = "google",
            device = "husky", product = "husky",
            fingerprint = "google/husky/husky:14/AD1A.240905.004/12147586:user/release-keys",
            androidVersion = "14", sdkInt = 34, buildId = "AD1A.240905.004"),
        SpoofProfile(profileName = "OnePlus 12",
            manufacturer = "OnePlus", model = "CPH2573", brand = "OnePlus",
            device = "CPH2573", product = "CPH2573",
            fingerprint = "OnePlus/CPH2573/CPH2573:14/UKQ1.230924.001/R.1700e2:user/release-keys",
            androidVersion = "14", sdkInt = 34, buildId = "UKQ1.230924.001"),
        SpoofProfile(profileName = "Xiaomi 14 Pro",
            manufacturer = "Xiaomi", model = "23116PN5BC", brand = "Xiaomi",
            device = "shennong", product = "shennong_cn",
            fingerprint = "Xiaomi/shennong_cn/shennong:14/UKQ1.230917.001/V14.0.9.0.UNACNXM:user/release-keys",
            androidVersion = "14", sdkInt = 34, buildId = "UKQ1.230917.001")
    )

    // JNI
    private external fun nativeApplyDeviceSpoof(
        manufacturer: String, model: String, brand: String, device: String,
        product: String, fingerprint: String, androidVersion: String,
        sdkInt: Int, buildId: String, serial: String
    ): Boolean
    private external fun nativeApplyGpsSpoof(lat: Double, lon: Double): Boolean
    private external fun nativeApplyImeiSpoof(imei: String): Boolean
    private external fun nativeApplyAndroidIdSpoof(androidId: String): Boolean
    private external fun nativeResetSpoof(): Boolean

    init {
        try { System.loadLibrary("trinity_spoof") } catch (e: UnsatisfiedLinkError) { }
    }
}
