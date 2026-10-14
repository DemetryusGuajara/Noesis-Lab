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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    // AI Provider API Key Slots (Fase 2: múltiplas chaves por provedor)
    @Query("SELECT * FROM ai_provider_keys WHERE providerId = :providerId ORDER BY sortOrder ASC, id ASC")
    fun observeProviderKeys(providerId: Long): Flow<List<AiProviderKeyEntity>>

    @Query("SELECT * FROM ai_provider_keys WHERE providerId = :providerId ORDER BY sortOrder ASC, id ASC")
    suspend fun getProviderKeysOnce(providerId: Long): List<AiProviderKeyEntity>

    @Query("SELECT * FROM ai_provider_keys")
    suspend fun getAllKeysOnce(): List<AiProviderKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProviderKey(key: AiProviderKeyEntity): Long

    @Update
    suspend fun updateProviderKey(key: AiProviderKeyEntity)

    @Query("UPDATE ai_provider_keys SET isActive = 0 WHERE providerId = :providerId")
    suspend fun deactivateProviderKeys(providerId: Long)

    @Query("DELETE FROM ai_provider_keys WHERE id = :id")
    suspend fun deleteProviderKey(id: Long)

    @Query("DELETE FROM ai_provider_keys WHERE providerId = :providerId")
    suspend fun deleteProviderKeysOf(providerId: Long)

    @Query("DELETE FROM ai_provider_keys")
    suspend fun clearAllProviderKeys()

    // Journal Entries ("Diário de Bordo")
    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC")
    fun observeJournalEntries(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC")
    suspend fun getAllJournalEntriesOnce(): List<JournalEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournalEntry(entry: JournalEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournalEntries(entries: List<JournalEntryEntity>)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteJournalEntry(id: Long)

    @Query("DELETE FROM journal_entries")
    suspend fun clearAllJournalEntries()
}

@Database(
    entities = [
        ConceptParticleEntity::class,
        ArchitecturalZoneEntity::class,
        SimulationLogEntity::class,
        AiProviderEntity::class,
        AiProviderKeyEntity::class,
        JournalEntryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NoesisDatabase : RoomDatabase() {
    abstract fun noesisDao(): NoesisDao

    companion object {
        @Volatile
        private var INSTANCE: NoesisDatabase? = null

        // Migração 2 -> 3 sem perda: novas colunas + tabela de slots de chave.
        // Chaves únicas legadas viram "Chave 1" do provedor.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_providers ADD COLUMN isCustom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_providers ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_provider_keys (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "providerId INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "apiKey TEXT NOT NULL, " +
                        "accountId TEXT NOT NULL DEFAULT '', " +
                        "isActive INTEGER NOT NULL DEFAULT 0, " +
                        "sortOrder INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(providerId) REFERENCES ai_providers(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_provider_keys_providerId ON ai_provider_keys(providerId)")
                db.execSQL(
                    "INSERT INTO ai_provider_keys (providerId, label, apiKey, accountId, isActive, sortOrder) " +
                        "SELECT id, 'Chave 1', apiKey, '', CASE WHEN apiKey <> '' THEN 1 ELSE 0 END, 0 " +
                        "FROM ai_providers WHERE apiKey <> ''"
                )
            }
        }

        fun getInstance(context: Context): NoesisDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoesisDatabase::class.java,
                    "noesis_conscious_lab.db"
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
