package dev.marketlab.backfill

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class FunctionalFeatureRow(
    val schemaVersion: String = "marketlab.social-functional-feature.v1",
    val rowId: String,
    val decisionTimeEpochMillis: Long,
    val symbol: String,
    val features: Map<String, Double>,
    val next15mReturn: Double? = null,
    val next1hRealizedVariance: Double? = null,
    val next1dReturn: Double? = null,
    val next1dRealizedVariance: Double? = null,
)

@Serializable
internal data class FunctionalFeatureManifest(
    val schemaVersion: String = "marketlab.social-functional-feature-manifest.v1",
    val programLockSha256: String,
    val inputManifestSetSha256: String,
    val featureLockSha256: String,
    val startInclusive: String,
    val featureStartInclusive: String,
    val endExclusive: String,
    val rowCount: Long,
    val rowsBySymbol: Map<String, Long>,
    val outputUri: String,
    val outputSha256: String,
    val completedAtEpochMillis: Long,
)

@Serializable
internal data class FrozenFunctionalModelManifest(
    val schemaVersion: String = "marketlab.social-functional-model.v1",
    val modelId: String,
    val target: String,
    val sourcePolicy: String,
    val family: String,
    val featureManifestSha256: String,
    val searchLockSha256: String,
    val trialLedgerSha256: String,
    val modelArtifactSha256: String,
    val selectedAtEpochMillis: Long,
)

@Serializable
internal data class ShadowForecast(
    val schemaVersion: String = "marketlab.social-shadow-forecast.v1",
    val modelId: String,
    val rowId: String,
    val decisionTimeEpochMillis: Long,
    val symbol: String,
    val prediction: Double,
    val directionProbability: Double? = null,
    val producedAtEpochMillis: Long,
)

internal data class FunctionalFeatureConfig(
    val outputRoot: java.nio.file.Path,
    val programLock: java.nio.file.Path,
    val featureLock: java.nio.file.Path,
    val start: Instant,
    val endExclusive: Instant,
)
