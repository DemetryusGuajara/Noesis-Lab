package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "concept_particles")
data class ConceptParticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archetypeKey: String, // FEAR, DESIRE, TIME, IDENTITY, JUSTICE, DOMINATION, POWER, ANGER, EMPTINESS, CUSTOM
    val mass: Float,          // 0.2f .. 2.5f (Semantic gravity)
    val charge: Float,        // -1.0f .. 1.0f (Affective valence: contraction vs expansion)
    val frequencyHz: Float,   // 110f .. 880f (Acoustic & neural oscillation frequency)
    val spin: Float,          // -2.0f .. 2.0f (Phenomenological vorticity)
    val posX: Float,          // Normalized 0.1f .. 0.9f
    val posY: Float,          // Normalized 0.1f .. 0.9f
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
    val geometryType: String, // LABYRINTH, ATRIUM, SPIRAL, TOWER, LATTICE, MONOLITH, VOID_GARDEN
    val elevationLevel: Float, // -1.0f (Subterranean) to +1.0f (Zenith)
    val luminosity: Float,     // 0.1f .. 1.0f
    val tensionIndex: Float,   // 0.0f .. 1.0f
    val resonantHz: Float,
    val mapX: Float,           // Normalized 0.15f .. 0.85f
    val mapY: Float,           // Normalized 0.15f .. 0.85f
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
    val apiKey: String,
    val verifiedModelsCsv: String, // Only models that passed the live "oi"/"hi" ping with 200 OK
    val selectedModel: String,
    val isActive: Boolean,
    val lastVerifiedAt: Long = 0L
)
