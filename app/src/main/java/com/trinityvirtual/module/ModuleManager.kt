package com.trinityvirtual.module

import android.content.Context
import com.trinityvirtual.model.TrinityModule
import com.trinityvirtual.model.ModuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object ModuleManager {

    private lateinit var ctx: Context
    private lateinit var modulesDir: File
    private val loadedModules = mutableListOf<TrinityModule>()

    fun init(context: Context) {
        ctx = context.applicationContext
        modulesDir = File(ctx.filesDir, "modules").also { it.mkdirs() }
    }

    suspend fun installModule(zipPath: String): TrinityModule? = withContext(Dispatchers.IO) {
        try {
            val zipFile = ZipFile(zipPath)
            val propEntry = zipFile.getEntry("module.prop") ?: return@withContext null
            val props = mutableMapOf<String, String>()

            zipFile.getInputStream(propEntry).bufferedReader().forEachLine { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) props[parts[0].trim()] = parts[1].trim()
            }

            val id = props["id"] ?: return@withContext null
            val name = props["name"] ?: id
            val version = props["version"] ?: "1.0"
            val description = props["description"] ?: ""
            val author = props["author"] ?: "Unknown"

            val moduleDir = File(modulesDir, id).also { it.mkdirs() }
            File(zipPath).copyTo(File(moduleDir, "module.zip"), overwrite = true)

            val module = TrinityModule(
                name = name,
                version = version,
                description = description,
                author = author,
                modulePath = moduleDir.absolutePath,
                type = detectModuleType(props)
            )

            zipFile.close()
            module
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun enableModule(module: TrinityModule): Boolean = withContext(Dispatchers.IO) {
        try {
            val disableFile = File(module.modulePath, "disable")
            if (disableFile.exists()) disableFile.delete()
            nativeLoadModule(module.modulePath)
            true
        } catch (e: Exception) { false }
    }

    suspend fun disableModule(module: TrinityModule): Boolean = withContext(Dispatchers.IO) {
        try {
            File(module.modulePath, "disable").createNewFile()
            nativeUnloadModule(module.modulePath)
            true
        } catch (e: Exception) { false }
    }

    suspend fun uninstallModule(module: TrinityModule) = withContext(Dispatchers.IO) {
        File(module.modulePath).deleteRecursively()
        loadedModules.remove(module)
    }

    fun getLoadedModules(): List<TrinityModule> = loadedModules.toList()

    private fun detectModuleType(props: Map<String, String>): ModuleType {
        return when {
            props.containsKey("xposed") -> ModuleType.XPOSED
            props["type"] == "spoof" -> ModuleType.SPOOF
            props["type"] == "hook" -> ModuleType.HOOK
            else -> ModuleType.SYSTEM
        }
    }

    private external fun nativeLoadModule(modulePath: String): Boolean
    private external fun nativeUnloadModule(modulePath: String): Boolean

    init {
        try { System.loadLibrary("trinity_module") } catch (e: UnsatisfiedLinkError) { }
    }
}
