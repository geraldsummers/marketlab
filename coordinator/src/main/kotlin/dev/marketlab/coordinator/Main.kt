package dev.marketlab.coordinator

import dev.marketlab.contracts.TheoryId
import dev.marketlab.data.hyperliquid.HyperliquidDataIngestor
import dev.marketlab.data.hyperliquid.StoredHyperliquidSnapshotReader
import dev.marketlab.data.storage.ContentAddressedDataStore
import dev.marketlab.persistence.Database
import dev.marketlab.persistence.DatabaseConfig
import dev.marketlab.persistence.JobRepository
import dev.marketlab.theories.AcademicTheoryRegistry
import dev.marketlab.theory.TheoryPlanHasher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() =
    runBlocking {
        val logger = LoggerFactory.getLogger("dev.marketlab.coordinator.Main")
        val coordinatorConfig = CoordinatorConfig.fromEnvironment()
        val databaseConfig = DatabaseConfig.fromEnvironment()
        val parentJob = coroutineContext.job
        val shutdownHook =
            Thread(
                {
                    logger.info("Coordinator shutdown requested")
                    parentJob.cancel("JVM shutdown")
                },
                "marketlab-coordinator-shutdown",
            )
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            Database.open(databaseConfig).use { database ->
                val store = ContentAddressedDataStore(coordinatorConfig.rawDataRoot)
                HyperliquidDataIngestor.mainnet(store).use { ingestor ->
                    val jobs = JobRepository(database.dataSource)
                    val registrar =
                        PostgreSqlSnapshotRegistrar(
                            dataSource = database.dataSource,
                            rawDataRoot = coordinatorConfig.rawDataRoot,
                        )
                    val ingestion =
                        HyperliquidIngestionJobHandler(
                            ingestor = ingestor,
                            registrar = registrar,
                            defaultCandleInterval = coordinatorConfig.defaultCandleInterval,
                            maximumRange = coordinatorConfig.maximumRange,
                        )
                    val experimentPersistence =
                        PostgreSqlExperimentPersistence(
                            database.dataSource,
                        )
                    val snapshotReader = StoredHyperliquidSnapshotReader(store)
                    val artifactStore =
                        ExperimentArtifactStore(
                            coordinatorConfig.artifactRoot,
                        )
                    val controls =
                        ControlExperimentJobHandler(
                            persistence = experimentPersistence,
                            snapshotReader = snapshotReader,
                            artifacts = artifactStore,
                            sourceRevision = coordinatorConfig.sourceRevision,
                        )
                    val momentumPlan =
                        requireNotNull(
                            AcademicTheoryRegistry.find(
                                TheoryId(HYPERLIQUID_BTC_MOMENTUM_THEORY_ID),
                            ),
                        ) {
                            "The coordinator momentum executor has no registered theory plan"
                        }.compile()
                    require(momentumPlan.descriptor.version == "1.0.0") {
                        "The coordinator momentum executor supports only theory version 1.0.0"
                    }
                    val momentum =
                        MomentumExperimentJobHandler(
                            persistence = experimentPersistence,
                            snapshotReader = snapshotReader,
                            artifacts = artifactStore,
                            sourceRevision = coordinatorConfig.sourceRevision,
                            expectedPlanHash = TheoryPlanHasher.hash(momentumPlan).hex,
                        )
                    val harVariancePlan =
                        requireNotNull(
                            AcademicTheoryRegistry.find(
                                TheoryId(
                                    HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID,
                                ),
                            ),
                        ) {
                            "The coordinator HAR variance executor has no registered theory plan"
                        }.compile()
                    require(harVariancePlan.descriptor.version == "1.0.0") {
                        "The coordinator HAR variance executor supports only theory version 1.0.0"
                    }
                    val harVariance =
                        HarVarianceExperimentJobHandler(
                            persistence = experimentPersistence,
                            snapshotReader = snapshotReader,
                            artifacts = artifactStore,
                            sourceRevision = coordinatorConfig.sourceRevision,
                            expectedPlanHash = TheoryPlanHasher.hash(harVariancePlan).hex,
                        )
                    val hourlyReversalPlan =
                        requireNotNull(
                            AcademicTheoryRegistry.find(
                                TheoryId(HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID),
                            ),
                        ) {
                            "The coordinator hourly reversal executor has no registered theory plan"
                        }.compile()
                    require(hourlyReversalPlan.descriptor.version == "1.0.0") {
                        "The coordinator hourly reversal executor supports only theory version 1.0.0"
                    }
                    val hourlyReversal =
                        HourlyReversalExperimentJobHandler(
                            persistence = experimentPersistence,
                            snapshotReader = snapshotReader,
                            artifacts = artifactStore,
                            sourceRevision = coordinatorConfig.sourceRevision,
                            expectedPlanHash = TheoryPlanHasher.hash(hourlyReversalPlan).hex,
                        )
                    val hourlyVolatilityPeriodicityPlan =
                        requireNotNull(
                            AcademicTheoryRegistry.find(
                                TheoryId(
                                    HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID,
                                ),
                            ),
                        ) {
                            "The coordinator volatility periodicity executor has no registered theory plan"
                        }.compile()
                    require(hourlyVolatilityPeriodicityPlan.descriptor.version == "1.0.0") {
                        "The volatility periodicity executor supports only theory version 1.0.0"
                    }
                    val hourlyVolatilityPeriodicity =
                        HourlyVolatilityPeriodicityExperimentJobHandler(
                            persistence = experimentPersistence,
                            snapshotReader = snapshotReader,
                            artifacts = artifactStore,
                            sourceRevision = coordinatorConfig.sourceRevision,
                            expectedPlanHash =
                                TheoryPlanHasher.hash(hourlyVolatilityPeriodicityPlan).hex,
                        )
                    val experiments =
                        RoutingExperimentJobHandler(
                            controls = controls,
                            handlers =
                                mapOf(
                                    HYPERLIQUID_BTC_MOMENTUM_THEORY_ID to momentum,
                                    HYPERLIQUID_BTC_FOUR_HOUR_LOG_HAR_VARIANCE_THEORY_ID to
                                        harVariance,
                                    HYPERLIQUID_BTC_HOURLY_RETURN_REVERSAL_THEORY_ID to
                                        hourlyReversal,
                                    HYPERLIQUID_ETH_HOURLY_VOLATILITY_PERIODICITY_THEORY_ID to
                                        hourlyVolatilityPeriodicity,
                                ),
                        )
                    JobCoordinator(
                        config = coordinatorConfig,
                        jobs = jobs,
                        ingestion = ingestion,
                        experiments = experiments,
                    ).run()
                }
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
