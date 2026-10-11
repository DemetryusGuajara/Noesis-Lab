package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoesisDao {
    // Concept Particles
    @Query("SELECT * FROM concept_particles ORDER BY createdAt ASC")
    fun observeParticles(): Flow<List<ConceptParticleEntity>>

    @Query("SELECT * FROM concept_particles ORDER BY createdAt ASC")
    suspend fun getAllParticlesOnce(): List<ConceptParticleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticle(particle: ConceptParticleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticles(particles: List<ConceptParticleEntity>)

    @Update
    suspend fun updateParticle(particle: ConceptParticleEntity)

    @Query("DELETE FROM concept_particles WHERE id = :id")
    suspend fun deleteParticle(id: Long)

    @Query("DELETE FROM concept_particles")
    suspend fun clearAllParticles()

    // Architectural Zones
    @Query("SELECT * FROM architectural_zones ORDER BY elevationLevel DESC")
    fun observeZones(): Flow<List<ArchitecturalZoneEntity>>

    @Query("SELECT * FROM architectural_zones")
    suspend fun getAllZonesOnce(): List<ArchitecturalZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZones(zones: List<ArchitecturalZoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZone(zone: ArchitecturalZoneEntity)

    @Query("DELETE FROM architectural_zones")
    suspend fun clearAllZones()

    // Simulation Logs
    @Query("SELECT * FROM simulation_logs ORDER BY timestamp DESC")
    fun observeSimulationLogs(): Flow<List<SimulationLogEntity>>

    @Query("SELECT * FROM simulation_logs ORDER BY timestamp DESC")
    suspend fun getAllLogsOnce(): List<SimulationLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSimulationLog(log: SimulationLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSimulationLogs(logs: List<SimulationLogEntity>)

    @Query("DELETE FROM simulation_logs")
    suspend fun clearAllLogs()

    // AI Providers (BYOK)
    @Query("SELECT * FROM ai_providers ORDER BY id DESC")
    fun observeProviders(): Flow<List<AiProviderEntity>>

    @Query("SELECT * FROM ai_providers ORDER BY id DESC")
    suspend fun getAllProvidersOnce(): List<AiProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: AiProviderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProviders(providers: List<AiProviderEntity>)

    @Update
    suspend fun updateProvider(provider: AiProviderEntity)

    @Query("UPDATE ai_providers SET isActive = 0")
    suspend fun deactivateAllProviders()

    @Query("UPDATE ai_providers SET isActive = 1, selectedModel = :model WHERE id = :providerId")
    suspend fun activateProviderModel(providerId: Long, model: String)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: Long)

    @Query("DELETE FROM ai_providers")
    suspend fun clearAllProviders()
}

@Database(
    entities = [
        ConceptParticleEntity::class,
        ArchitecturalZoneEntity::class,
        SimulationLogEntity::class,
        AiProviderEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NoesisDatabase : RoomDatabase() {
    abstract fun noesisDao(): NoesisDao

    companion object {
        @Volatile
        private var INSTANCE: NoesisDatabase? = null

        fun getInstance(context: Context): NoesisDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoesisDatabase::class.java,
                    "noesis_conscious_lab.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
