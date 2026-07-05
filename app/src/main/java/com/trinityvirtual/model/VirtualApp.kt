package com.trinityvirtual.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "virtual_apps")
data class VirtualApp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val packageName: String,
    val apkPath: String,
    val iconPath: String? = null,
    val versionName: String = "1.0",
    val installedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val isRunning: Boolean = false,
    val rootEnabled: Boolean = false,
    val spoofEnabled: Boolean = false
)
