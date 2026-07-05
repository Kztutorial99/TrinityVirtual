package com.trinityvirtual.model

data class SpoofProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val profileName: String = "Default",
    val manufacturer: String = "Samsung",
    val model: String = "SM-S918B",
    val brand: String = "samsung",
    val device: String = "dm3q",
    val product: String = "dm3qxx",
    val fingerprint: String = "samsung/dm3qxx/dm3q:14/UP1A.231005.007/S918BXXS3CXA1:user/release-keys",
    val androidVersion: String = "14",
    val sdkInt: Int = 34,
    val buildId: String = "UP1A.231005.007",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsEnabled: Boolean = false,
    val imei: String = "",
    val androidId: String = "",
    val serialNumber: String = "unknown"
)
