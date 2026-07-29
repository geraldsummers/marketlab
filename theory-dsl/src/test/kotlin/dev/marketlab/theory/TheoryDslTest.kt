package dev.marketlab.theory

import dev.marketlab.contracts.DataSourceId
import dev.marketlab.contracts.FiniteDouble
import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.DataRequirement
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TheoryDslTest {
    @Test
    fun `compiled plans hash stably despite builder insertion order`() {
        val forward = fixturePlan(reverseFeatures = false)
        val reverse = fixturePlan(reverseFeatures = true)

        assertEquals(TheoryPlanHasher.canonicalJson(forward), TheoryPlanHasher.canonicalJson(reverse))
        assertEquals(TheoryPlanHasher.hash(forward), TheoryPlanHasher.hash(reverse))
        assertTrue(TheoryPlanHasher.canonicalJson(forward).contains("\"type\""))
        assertTrue("$" + "Lambda" !in TheoryPlanHasher.canonicalJson(forward))
    }

    @Test
    fun `unknown fields and feature cycles fail validation`() {
        val base = fixturePlan()
        val unknownField = base.copy(
            featureGraph = FeatureGraph(
                listOf(
                    FeatureDefinition(
                        "bad",
                        field("candles", "not_declared"),
                        "Invalid undeclared field.",
                    ),
                ),
            ),
        )
        assertTrue(TheoryPlanValidator.validate(unknownField).any { "not declared" in it.message })

        val cyclic = base.copy(
            featureGraph = FeatureGraph(
                listOf(
                    FeatureDefinition("cycle_a", feature("cycle_b"), "First cycle node."),
                    FeatureDefinition("cycle_b", feature("cycle_a"), "Second cycle node."),
                ),
            ),
        )
        assertFailsWith<InvalidTheoryPlanException> { TheoryPlanHasher.hash(cyclic) }
    }

    @Test
    fun `unbounded parameter grids are rejected before execution`() {
        val plan = fixturePlan().copy(
            parameterSpace = ParameterSpace(
                listOf(ParameterSpec.IntegerRange("lookback", 1, 100_001, 1)),
            ),
        )

        assertTrue(TheoryPlanValidator.validate(plan).any { "safety bound" in it.message })
    }

    @Test
    fun `fixed inactive universe does not require historical active metadata`() {
        val base = fixturePlan()
        val withoutMetadata = base.copy(
            researchCard = base.researchCard.copy(
                dataRequirements = base.researchCard.dataRequirements.filterNot {
                    it.observation == ObservationKind.INSTRUMENT_METADATA
                },
            ),
            universe = base.universe.copy(
                selection = UniverseSelection.Explicit(
                    listOf(InstrumentId("hyperliquid:perpetual:BTC")),
                ),
                minimumListingAgeMillis = 0,
                requireActiveAtDecisionTime = false,
            ),
        )

        assertTrue(TheoryPlanValidator.validate(withoutMetadata).isEmpty())

        val activeAtDecision = withoutMetadata.copy(
            universe = withoutMetadata.universe.copy(requireActiveAtDecisionTime = true),
        )
        assertTrue(
            TheoryPlanValidator.validate(activeAtDecision).any {
                it.path == "universe" && "active instrument metadata" in it.message
            },
        )
    }

    @Test
    fun `log realized variance has explicit canonical semantics and requires QLIKE`() {
        val base = fixturePlan()
        val withoutQlike = base.copy(
            target = ForecastTarget.LogRealizedVariance(
                returnExpression = field("candles", "close"),
                horizon = Horizon.Duration(86_400_000),
            ),
            metrics = listOf(Metric.MAE),
        )

        assertTrue(
            TheoryPlanValidator.validate(withoutQlike).any {
                it.path == "metrics" && "must report QLIKE" in it.message
            },
        )

        val valid = withoutQlike.copy(metrics = listOf(Metric.MAE, Metric.QLIKE))
        assertTrue(TheoryPlanValidator.validate(valid).isEmpty())
        assertTrue(
            TheoryPlanHasher.canonicalJson(valid)
                .contains("\"type\":\"log_realized_variance\""),
        )
    }

    @Test
    fun `UTC hour indicators are explicit validated deterministic expressions`() {
        val base = fixturePlan()
        val requirements =
            base.researchCard.dataRequirements.map {
                if (it.key == "candles") {
                    it.copy(requiredFields = (it.requiredFields + "close_time").sorted())
                } else {
                    it
                }
            }
        val plan =
            base.copy(
                researchCard = base.researchCard.copy(dataRequirements = requirements),
                featureGraph =
                    FeatureGraph(
                        listOf(
                            FeatureDefinition(
                                "hour_16_utc",
                                utcHourIndicator(field("candles", "close_time"), 16),
                                "UTC hour 16 indicator.",
                            ),
                        ),
                    ),
            )

        assertTrue(TheoryPlanValidator.validate(plan).isEmpty())
        assertTrue(
            TheoryPlanHasher.canonicalJson(plan)
                .contains("\"type\":\"utc_hour_indicator\""),
        )
        assertFailsWith<IllegalArgumentException> {
            utcHourIndicator(field("candles", "close_time"), 24)
        }
    }

    private fun fixturePlan(reverseFeatures: Boolean = false): TheoryPlan {
        val descriptor = TheoryDescriptor(
            id = TheoryId("dsl-test-control"),
            version = "1.0.0",
            name = "DSL contract test",
            summary = "Exercises deterministic compilation without market observations.",
            family = TheoryFamily.NULL_BASELINE,
            maturity = TheoryMaturity.CONTROL,
            tags = listOf("control", "test"),
        )
        return theory(descriptor) {
            research {
                hypothesis = "No conditional predictability is assumed."
                mechanism = "This is a contract-only control."
                falsifiableClaim = "Candidate forecasts must beat the control."
                expectedSign = ExpectedSign.LOWER_ERROR_THAN_BASELINE
                horizon = Horizon.Duration(86_400_000)
                reproductionKind = ReproductionKind.CONTROL
                confirmationPeriodPolicy = "Chronological sealed holdout."
                cite(
                    Citation(
                        key = "fama-1970",
                        authors = listOf("Eugene F. Fama"),
                        year = 1970,
                        title = "Efficient Capital Markets: A Review of Theory and Empirical Work",
                        venue = "The Journal of Finance",
                        locator = "https://doi.org/10.2307/2325486",
                        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
                        role = EvidenceRole.METHODOLOGY,
                        relevance = "Defines the weak-form efficiency control.",
                    ),
                )
                requireData(
                    DataRequirement(
                        key = "candles",
                        observation = ObservationKind.CANDLE,
                        sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                        instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                        sampling = Sampling.FixedDuration(3_600_000),
                        requiredFields = listOf("close"),
                        minimumHistoryMillis = 31_536_000_000,
                    ),
                )
                requireData(
                    DataRequirement(
                        key = "universe_metadata",
                        observation = ObservationKind.INSTRUMENT_METADATA,
                        sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                        instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                        sampling = Sampling.EventTime,
                        requiredFields = listOf("active"),
                        minimumHistoryMillis = 31_536_000_000,
                    ),
                )
                requireData(
                    DataRequirement(
                        key = "universe_volume",
                        observation = ObservationKind.CANDLE,
                        sourcePreference = listOf(DataSourceId("hyperliquid-mainnet")),
                        instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                        sampling = Sampling.FixedDuration(86_400_000),
                        requiredFields = listOf("base_volume", "close"),
                        minimumHistoryMillis = 31_536_000_000,
                    ),
                )
            }
            universe(
                PointInTimeUniverse(
                    selection = UniverseSelection.TopByTrailingNotional(
                        limit = 10,
                        trailingWindowMillis = 2_592_000_000,
                    ),
                    venues = listOf(DataSourceId("hyperliquid-mainnet")),
                    instrumentKinds = listOf(InstrumentKind.PERPETUAL),
                    minimumListingAgeMillis = 7_776_000_000,
                ),
            )
            val definitions = listOf(
                Triple("a_close", "Latest completed close.", field("candles", "close")),
                Triple(
                    "z_lagged_close",
                    "One-bar lagged completed close.",
                    lag(field("candles", "close"), Window.Bars(1)),
                ),
            )
            (if (reverseFeatures) definitions.reversed() else definitions).forEach { (name, description, expression) ->
                feature(name, description, expression)
            }
            target(ForecastTarget.Return(field("candles", "close"), Horizon.Duration(86_400_000)))
            estimator(EstimatorSpec.HistoricalMean)
            parameters(
                ParameterSpec.DecimalChoices(
                    "ridge",
                    listOf(FiniteDouble(0.0), FiniteDouble(0.1)),
                ),
            )
            validation(
                ValidationSpec(
                    scheme = FoldScheme.EXPANDING,
                    foldCount = 3,
                    minimumTrainingMillis = 15_552_000_000,
                    testMillis = 2_592_000_000,
                    purgeMillis = 86_400_000,
                    embargoMillis = 86_400_000,
                    sealedFinalHoldoutMillis = 2_592_000_000,
                ),
            )
            execution(
                ExecutionProfile(
                    mode = ExecutionMode.FORECAST_ONLY,
                    targetVenue = DataSourceId("hyperliquid-mainnet"),
                    decisionLatencyMillis = 0,
                    requireObservedQuotes = false,
                    requireObservedDepth = false,
                    includeFees = false,
                    includeFunding = false,
                ),
            )
            metrics(Metric.RMSE, Metric.MAE)
            failWhen("Required production observations are incomplete.")
            promotion(PromotionGate(criteria = emptyList(), eligibleForPaper = false))
        }
    }
}
