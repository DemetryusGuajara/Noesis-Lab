package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "concept_particles")
data class ConceptParticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archetypeKey: String,
    val mass: Float,          // 0.4f .. 2.5f (Tamanho e Importância -> Defines Hue from Cold Blue to Warm Red)
    val charge: Float,        // 0.0f .. 1.0f (Sensação: 0.0 = Leve, 1.0 = Pesada -> Defines Saturation & Acoustic Tension)
    val frequencyHz: Float,   // Automatically computed from mass + charge + proximity
    val spin: Float,
    val posX: Float,          // Normalized 0.08f .. 0.92f
    val posY: Float,          // Normalized 0.10f .. 0.90f
    val velX: Float = 0f,
    val velY: Float = 0f,
    val colorHex: Long,
    val philosophicalNote: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "architectural_zones")
data class ArchitecturalZoneEntity(
    @PrimaryKey val zoneId: String,
    val titleKey: String,
    val customTitle: String,
    val conceptDomain: String,
    val geometryType: String,
    val elevationLevel: Float,
    val luminosity: Float,     // Dynamically diagnosed from Tab 1 spheres
    val tensionIndex: Float,   // Dynamically diagnosed from Tab 1 spheres
    val resonantHz: Float,
    val mapX: Float,
    val mapY: Float,
    val connectedZonesCsv: String,
    val architecturalDescription: String,
    val lastVisitedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "simulation_logs")
data class SimulationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userPrompt: String,
    val activeConceptsCsv: String,
    val biofeedbackArousal: Float,
    val biofeedbackValence: Float,
    val biofeedbackFocus: Float,
    val wikiSourcesJson: String,
    val academicSourcesJson: String,
    val narrativeResponse: String,
    val somaticSuggestion: String,
    val providerUsed: String,
    val languageCode: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_providers")
data class AiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKey: String, // Legado (Fase 2 usa ai_provider_keys); mantido p/ migração
    val verifiedModelsCsv: String,
    val selectedModel: String,
    val isActive: Boolean,
    val isCustom: Boolean = false, // Hardcoded (false) tem nome/endpoint imutáveis
    val sortOrder: Int = 0,
    val lastVerifiedAt: Long = 0L
)

/**
 * Slots de chave/API por provedor (Fase 2: rotação de free tier).
 * Cloudflare usa accountId por slot; endpoint com {account_id} é resolvido na chamada.
 */
@Entity(
    tableName = "ai_provider_keys",
    foreignKeys = [ForeignKey(
        entity = AiProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("providerId")]
)
data class AiProviderKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val label: String,
    val apiKey: String,
    val accountId: String = "",
    val isActive: Boolean = false,
    val sortOrder: Int = 0
)

/**
 * Immutable Article Snapshot saved in the User's Logbook ("Diário de Bordo" - Item 16).
 * Captures the gravitational field snapshot, architectural diagnosis snapshot, user notes,
 * and generated reflections/exercises with date and time.
 */
@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val titleSummary: String,
    val particlesSnapshotJson: String,
    val zonesSnapshotJson: String,
    val userNotesPrompt: String,
    val narrativeText: String,
    val somaticExerciseText: String,
    val bioArousal: Float,
    val bioValence: Float,
    val bioFocus: Float
)
