package com.trinityvirtual.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trinity_modules")
data class TrinityModule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val modulePath: String,
    val isEnabled: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
    val type: ModuleType = ModuleType.XPOSED
)

enum class ModuleType {
    XPOSED, SYSTEM, HOOK, SPOOF
}
