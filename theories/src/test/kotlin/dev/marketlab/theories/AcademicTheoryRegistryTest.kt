package dev.marketlab.theories

import dev.marketlab.contracts.InstrumentId
import dev.marketlab.contracts.TheoryId
import dev.marketlab.contracts.data.ObservationKind
import dev.marketlab.contracts.data.Sampling
import dev.marketlab.contracts.market.InstrumentKind
import dev.marketlab.theory.EvidenceRole
import dev.marketlab.theory.EstimatorSpec
import dev.marketlab.theory.ExecutionMode
import dev.marketlab.theory.FamilywiseCorrection
import dev.marketlab.theory.FoldScheme
import dev.marketlab.theory.Horizon
import dev.marketlab.theory.Metric
import dev.marketlab.theory.NumericExpression
import dev.marketlab.theory.ReproductionKind
import dev.marketlab.theory.TheoryFamily
import dev.marketlab.theory.TheoryPlanHasher
import dev.marketlab.theory.UniverseSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcademicTheoryRegistryTest {
    @Test
    fun `registry compiles every control and tier-one research card`() {
        val plans = AcademicTheoryRegistry.compileAll()

        assertEquals(20, plans.size)
        assertEquals(plans.size, plans.map { it.descriptor.id }.distinct().size)
        plans.forEach { plan ->
            assertEquals(64, TheoryPlanHasher.hash(plan).hex.length)
            assertTrue(plan.researchCard.citations.all { it.locator.startsWith("https://") })
            assertTrue(plan.researchCard.dataRequirements.all { requirement ->
                requirement.sourcePreference.all {
                    it.value.endsWith("-mainnet") ||
                        it.value in
                        setOf(
                            "bluesky-jetstream",
                            "farcaster-snapchain",
                            "gdelt-gkg-2.1",
                            "nostr-public-relays",
                            "official-crypto-rss",
                        )
                }
            })
        }
    }

    @Test
    fun `social information family shares one frozen prospective design`() {
        val social =
            AcademicTheoryRegistry.compileAll()
                .filter { it.descriptor.family == TheoryFamily.INFORMATION }

        assertEquals(
            setOf(
                "hyperliquid-top-ten-cross-sectional-social-attention-return",
                "hyperliquid-top-ten-news-negativity-daily-variance",
                "hyperliquid-top-ten-social-attention-hourly-variance",
                "hyperliquid-top-ten-social-disagreement-hourly-variance",
                "hyperliquid-top-ten-social-polarity-fifteen-minute-return",
            ),
            social.map { it.descriptor.id.value }.toSet(),
        )
        social.forEach { plan ->
            val selection = assertIs<UniverseSelection.TopByTrailingNotional>(plan.universe.selection)
            assertEquals(10, selection.limit)
            assertEquals(30L * DAY, selection.trailingWindowMillis)
            assertEquals(90L * DAY, plan.universe.minimumListingAgeMillis)
            assertEquals(4, plan.validation.foldCount)
            assertEquals(120L * DAY, plan.validation.minimumTrainingMillis)
            assertEquals(14L * DAY, plan.validation.testMillis)
            assertEquals(60L * DAY, plan.validation.sealedFinalHoldoutMillis)
            assertEquals(FamilywiseCorrection.HOLM, plan.validation.familywiseCorrection)
            assertFalse(plan.validation.tuneInsideEachFold)
            assertTrue(
                plan.researchCard.dataRequirements.any {
                    it.minimumHistoryMillis == 270L * DAY
                },
            )
            assertTrue(
                plan.failureConditions.any {
                    "Holm" in it.condition || "global" in it.condition
                },
            )
        }
    }

    @Test
    fun `mandatory null controls are permanent and never paper eligible`() {
        val controls = AcademicTheoryRegistry.compileAll()
            .filter { it.descriptor.family == TheoryFamily.NULL_BASELINE }

        assertEquals(
            setOf(
                "control-buy-and-hold",
                "control-flat",
                "control-historical-mean",
                "control-persistence",
                "control-random-walk",
            ),
            controls.map { it.descriptor.id.value }.toSet(),
        )
        assertTrue(controls.all { !it.promotionGate.eligibleForPaper })
    }

    @Test
    fun `momentum card registers published critical evidence`() {
        val momentum = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("time-series-momentum")),
        ).compile()

        assertTrue(momentum.researchCard.citations.any { it.role == EvidenceRole.CONTRADICTORY })
        assertTrue(momentum.failureConditions.any { "historical-mean" in it.condition })
    }

    @Test
    fun `Hyperliquid BTC momentum adaptation has frozen executable semantics`() {
        val parent = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("time-series-momentum")),
        ).compile()
        val adaptation = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("hyperliquid-btc-time-series-momentum")),
        ).compile()

        assertEquals("1.0.0", adaptation.descriptor.version)
        assertEquals(ReproductionKind.REGISTERED_ADAPTATION, adaptation.researchCard.reproductionKind)
        assertEquals(TheoryId("time-series-momentum"), adaptation.lineage.parentTheoryId)
        assertEquals(TheoryPlanHasher.hash(parent).hex, adaptation.lineage.parentPlanHash)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("Feasibility adaptation") == true)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("not a published-strategy reproduction") == true)

        assertEquals(
            listOf(
                "huang-li-wang-zhou-2020",
                "liu-tsyvinski-2021",
                "moskowitz-ooi-pedersen-2012",
            ),
            adaptation.researchCard.citations.map { it.key },
        )
        assertEquals(
            listOf("Dashan Huang", "Jiangyuan Li", "Liyao Wang", "Guofu Zhou"),
            adaptation.researchCard.citations.single { it.role == EvidenceRole.CONTRADICTORY }.authors,
        )

        val requirement = adaptation.researchCard.dataRequirements.single()
        assertEquals("hyperliquid_btc_daily_candles", requirement.key)
        assertEquals(ObservationKind.CANDLE, requirement.observation)
        assertEquals(listOf(HYPERLIQUID), requirement.sourcePreference)
        assertEquals(listOf(InstrumentKind.PERPETUAL), requirement.instrumentKinds)
        assertEquals(Sampling.FixedDuration(DAY), requirement.sampling)
        assertEquals(listOf("close"), requirement.requiredFields)
        assertEquals(3L * 365L * DAY, requirement.minimumHistoryMillis)

        val selection = adaptation.universe.selection
        assertTrue(selection is UniverseSelection.Explicit)
        assertEquals(
            listOf(InstrumentId("hyperliquid:perpetual:BTC")),
            selection.instruments,
        )
        assertEquals(listOf(HYPERLIQUID), adaptation.universe.venues)
        assertEquals(listOf(InstrumentKind.PERPETUAL), adaptation.universe.instrumentKinds)
        assertFalse(adaptation.universe.requireActiveAtDecisionTime)

        assertEquals(listOf("return_365d", "volatility_60d"), adaptation.featureGraph.features.map { it.name })
        assertEquals(Horizon.CalendarDays(30), adaptation.researchCard.horizon)
        assertEquals(Horizon.CalendarDays(30), adaptation.target.horizon)
        assertEquals(EstimatorSpec.Linear(intercept = true), adaptation.estimator)
        assertTrue(adaptation.parameterSpace.parameters.isEmpty())
        assertEquals(FoldScheme.EXPANDING, adaptation.validation.scheme)
        assertEquals(5, adaptation.validation.foldCount)
        assertEquals(180L * DAY, adaptation.validation.minimumTrainingMillis)
        assertEquals(60L * DAY, adaptation.validation.testMillis)
        assertEquals(30L * DAY, adaptation.validation.purgeMillis)
        assertEquals(30L * DAY, adaptation.validation.embargoMillis)
        assertEquals(90L * DAY, adaptation.validation.sealedFinalHoldoutMillis)
        assertEquals(ExecutionMode.FORECAST_ONLY, adaptation.execution.mode)
        assertFalse(adaptation.promotionGate.eligibleForPaper)
        assertTrue(adaptation.failureConditions.any { "historical-mean" in it.condition })
        assertTrue(adaptation.failureConditions.any { "random-walk" in it.condition })
        assertEquals(
            "c865419fc12b3324491130bae15fe0de1a507f25e03b268617ea352130415c2a",
            TheoryPlanHasher.hash(adaptation).hex,
        )
    }

    @Test
    fun `Hyperliquid BTC four-hour log-HAR adaptation has frozen executable semantics`() {
        val parent = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("har-realized-volatility")),
        ).compile()
        val adaptation = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("hyperliquid-btc-four-hour-log-har-variance")),
        ).compile()

        assertEquals(
            "0a386e666bfc96299774c9f5137f6753a863488e9a87a0b220c49557e547df4e",
            TheoryPlanHasher.hash(parent).hex,
        )
        assertEquals("1.0.0", adaptation.descriptor.version)
        assertEquals(ReproductionKind.REGISTERED_ADAPTATION, adaptation.researchCard.reproductionKind)
        assertEquals(TheoryId("har-realized-volatility"), adaptation.lineage.parentTheoryId)
        assertEquals(TheoryPlanHasher.hash(parent).hex, adaptation.lineage.parentPlanHash)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("5,000-candle history limit") == true)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("not a reproduction") == true)
        assertEquals(
            listOf(
                "brauneis-sahiner-2026-crypto-har",
                "corsi-2009-har-rv",
                "patton-2011-volatility-proxies",
            ),
            adaptation.researchCard.citations.map { it.key },
        )

        val requirement = adaptation.researchCard.dataRequirements.single()
        assertEquals("hyperliquid_btc_four_hour_candles", requirement.key)
        assertEquals(ObservationKind.CANDLE, requirement.observation)
        assertEquals(listOf(HYPERLIQUID), requirement.sourcePreference)
        assertEquals(listOf(InstrumentKind.PERPETUAL), requirement.instrumentKinds)
        assertEquals(Sampling.FixedDuration(4L * HOUR), requirement.sampling)
        assertEquals(listOf("close"), requirement.requiredFields)
        assertEquals(700L * DAY, requirement.minimumHistoryMillis)

        val selection = assertIs<UniverseSelection.Explicit>(adaptation.universe.selection)
        assertEquals(
            listOf(InstrumentId("hyperliquid:perpetual:BTC")),
            selection.instruments,
        )
        assertEquals(listOf(HYPERLIQUID), adaptation.universe.venues)
        assertEquals(listOf(InstrumentKind.PERPETUAL), adaptation.universe.instrumentKinds)
        assertFalse(adaptation.universe.requireActiveAtDecisionTime)

        assertEquals(
            listOf("log_rv_daily", "log_rv_monthly", "log_rv_weekly"),
            adaptation.featureGraph.features.map { it.name },
        )
        adaptation.featureGraph.features.forEach { definition ->
            assertEquals(
                dev.marketlab.theory.UnaryOperation.LOG,
                assertIs<NumericExpression.Unary>(definition.expression).operation,
            )
        }
        assertEquals(Horizon.Duration(DAY), adaptation.researchCard.horizon)
        assertEquals(
            Horizon.Duration(DAY),
            assertIs<dev.marketlab.theory.ForecastTarget.LogRealizedVariance>(adaptation.target).horizon,
        )
        assertEquals(EstimatorSpec.Linear(intercept = true), adaptation.estimator)
        assertTrue(adaptation.parameterSpace.parameters.isEmpty())
        assertEquals(FoldScheme.EXPANDING, adaptation.validation.scheme)
        assertEquals(5, adaptation.validation.foldCount)
        assertEquals(365L * DAY, adaptation.validation.minimumTrainingMillis)
        assertEquals(30L * DAY, adaptation.validation.testMillis)
        assertEquals(DAY, adaptation.validation.purgeMillis)
        assertEquals(DAY, adaptation.validation.embargoMillis)
        assertEquals(90L * DAY, adaptation.validation.sealedFinalHoldoutMillis)
        assertFalse(adaptation.validation.tuneInsideEachFold)
        assertTrue(adaptation.validation.dependenceAwareInference)
        assertEquals(FamilywiseCorrection.FDR, adaptation.validation.familywiseCorrection)
        assertEquals(
            listOf(Metric.MAE, Metric.QLIKE, Metric.RMSE, Metric.SPA_P_VALUE),
            adaptation.metrics,
        )
        assertEquals(ExecutionMode.FORECAST_ONLY, adaptation.execution.mode)
        assertFalse(adaptation.promotionGate.eligibleForPaper)
        assertEquals(
            "2fbf43d99477adadebe1e896c0f20374da6cb99c9adbe3a8e32c7a68b8a018ae",
            TheoryPlanHasher.hash(adaptation).hex,
        )
    }

    @Test
    fun `Hyperliquid ETH hourly volatility periodicity has frozen executable semantics`() {
        val plan = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("hyperliquid-eth-hourly-volatility-periodicity")),
        ).compile()

        assertEquals(ReproductionKind.REGISTERED_ADAPTATION, plan.researchCard.reproductionKind)
        assertEquals(Horizon.Duration(HOUR), plan.target.horizon)
        assertEquals(
            listOf("hansen-kim-kimbrough-2024", "patton-2011-volatility-proxies"),
            plan.researchCard.citations.map { it.key },
        )
        val requirement = plan.researchCard.dataRequirements.single()
        assertEquals("hyperliquid_eth_hourly_candles", requirement.key)
        assertEquals(listOf("close", "close_time"), requirement.requiredFields)
        assertEquals(200L * DAY, requirement.minimumHistoryMillis)
        assertEquals(25, plan.featureGraph.features.size)
        assertEquals(
            listOf("log_rv_1h", "log_rv_24h"),
            plan.featureGraph.features.take(2).map { it.name },
        )
        assertEquals(
            (1..23).map { "next_hour_${it.toString().padStart(2, '0')}_utc" },
            plan.featureGraph.features.drop(2).map { it.name },
        )
        assertEquals(11, plan.validation.foldCount)
        assertEquals(90L * DAY, plan.validation.minimumTrainingMillis)
        assertEquals(30L * DAY, plan.validation.sealedFinalHoldoutMillis)
        assertEquals(ExecutionMode.FORECAST_ONLY, plan.execution.mode)
        assertFalse(plan.promotionGate.eligibleForPaper)
        assertEquals(
            "be4ed3d9a555e431310bc58bbc7657b0b158f06c9db18b9cb3d12afbbb9e6106",
            TheoryPlanHasher.hash(plan).hex,
        )
    }

    @Test
    fun `Hyperliquid BTC hourly reversal adaptation has frozen executable semantics`() {
        val parent = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("liquidity-conditioned-reversal")),
        ).compile()
        val adaptation = assertNotNull(
            AcademicTheoryRegistry.find(TheoryId("hyperliquid-btc-hourly-return-reversal")),
        ).compile()

        assertEquals(
            "e0f91ef5cd7be0254f231d1b1f957ac8a44a37fb23347e4fdf1c27232f57f249",
            TheoryPlanHasher.hash(parent).hex,
        )
        assertEquals(ReproductionKind.REGISTERED_ADAPTATION, adaptation.researchCard.reproductionKind)
        assertEquals(TheoryId("liquidity-conditioned-reversal"), adaptation.lineage.parentTheoryId)
        assertEquals(TheoryPlanHasher.hash(parent).hex, adaptation.lineage.parentPlanHash)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("omits liquidity conditioning") == true)
        assertTrue(adaptation.lineage.adaptationNotes?.contains("not a reproduction") == true)
        assertEquals(
            listOf("nagel-2012-evaporating-liquidity", "wen-bouri-xu-zhao-2022"),
            adaptation.researchCard.citations.map { it.key },
        )

        val requirement = adaptation.researchCard.dataRequirements.single()
        assertEquals("hyperliquid_btc_hourly_candles", requirement.key)
        assertEquals(ObservationKind.CANDLE, requirement.observation)
        assertEquals(Sampling.FixedDuration(HOUR), requirement.sampling)
        assertEquals(200L * DAY, requirement.minimumHistoryMillis)
        assertEquals(listOf("close"), requirement.requiredFields)

        val selection = assertIs<UniverseSelection.Explicit>(adaptation.universe.selection)
        assertEquals(
            listOf(InstrumentId("hyperliquid:perpetual:BTC")),
            selection.instruments,
        )
        assertEquals(listOf("return_1h"), adaptation.featureGraph.features.map { it.name })
        assertEquals(Horizon.Duration(HOUR), adaptation.researchCard.horizon)
        assertEquals(Horizon.Duration(HOUR), adaptation.target.horizon)
        assertEquals(EstimatorSpec.Linear(intercept = true), adaptation.estimator)
        assertTrue(adaptation.parameterSpace.parameters.isEmpty())
        assertEquals(FoldScheme.EXPANDING, adaptation.validation.scheme)
        assertEquals(11, adaptation.validation.foldCount)
        assertEquals(90L * DAY, adaptation.validation.minimumTrainingMillis)
        assertEquals(7L * DAY, adaptation.validation.testMillis)
        assertEquals(HOUR, adaptation.validation.purgeMillis)
        assertEquals(HOUR, adaptation.validation.embargoMillis)
        assertEquals(30L * DAY, adaptation.validation.sealedFinalHoldoutMillis)
        assertEquals(FamilywiseCorrection.SPA_AND_FDR, adaptation.validation.familywiseCorrection)
        assertEquals(ExecutionMode.FORECAST_ONLY, adaptation.execution.mode)
        assertFalse(adaptation.promotionGate.eligibleForPaper)
        assertTrue(adaptation.failureConditions.any { "coefficient" in it.condition })
        assertTrue(adaptation.failureConditions.any { "historical-mean" in it.condition })
        assertTrue(adaptation.failureConditions.any { "zero-return" in it.condition })
        assertTrue(adaptation.failureConditions.any { "persistence" in it.condition })
        assertEquals(
            "b9be8214bb3834cb940e83b4dee8bbf05929290fbec10fde9810afe26e66c57c",
            TheoryPlanHasher.hash(adaptation).hex,
        )
    }

    @Test
    fun `paper eligibility requires real Hyperliquid book replay`() {
        val plans = AcademicTheoryRegistry.compileAll()
        val eligible = plans.filter { it.promotionGate.eligibleForPaper }

        assertFalse(eligible.isEmpty())
        assertTrue(eligible.all { it.execution.mode == ExecutionMode.TAKER_BOOK_REPLAY })
        assertTrue(eligible.all { it.execution.requireObservedQuotes && it.execution.requireObservedDepth })
        assertTrue(eligible.all { it.promotionGate.requireHyperliquidHoldout })
    }
}
