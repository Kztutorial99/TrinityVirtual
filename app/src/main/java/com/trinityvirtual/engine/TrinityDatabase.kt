package com.trinityvirtual.engine

import android.content.Context
import androidx.room.*
import com.trinityvirtual.model.VirtualApp
import com.trinityvirtual.model.TrinityModule

@Dao
interface VirtualAppDao {
    @Query("SELECT * FROM virtual_apps ORDER BY installedAt DESC")
    suspend fun getAll(): List<VirtualApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: VirtualApp): Long

    @Delete
    suspend fun delete(app: VirtualApp)

    @Query("DELETE FROM virtual_apps WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE virtual_apps SET isRunning = :running WHERE id = :id")
    suspend fun setRunning(id: Long, running: Boolean)

    @Query("UPDATE virtual_apps SET rootEnabled = :enabled WHERE id = :id")
    suspend fun setRootEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE virtual_apps SET spoofEnabled = :enabled WHERE id = :id")
    suspend fun setSpoofEnabled(id: Long, enabled: Boolean)
}

@Dao
interface ModuleDao {
    @Query("SELECT * FROM trinity_modules ORDER BY installedAt DESC")
    suspend fun getAll(): List<TrinityModule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(module: TrinityModule): Long

    @Delete
    suspend fun delete(module: TrinityModule)

    @Query("UPDATE trinity_modules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Database(
    entities = [VirtualApp::class, TrinityModule::class],
    version = 2,
    exportSchema = false
)
abstract class TrinityDatabase : RoomDatabase() {
    abstract fun virtualAppDao(): VirtualAppDao
    abstract fun moduleDao(): ModuleDao

    companion object {
        @Volatile private var INSTANCE: TrinityDatabase? = null

        fun getInstance(context: Context): TrinityDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrinityDatabase::class.java,
                    "trinity_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}
