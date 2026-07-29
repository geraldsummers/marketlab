package dev.marketlab.engine

import dev.marketlab.contracts.MarketTimestamp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogTargetEstimatorTest {
    @Test
    fun `delegated estimator receives natural log training labels`() {
        val delegate = CapturingEstimator(logPrediction = ln(3.0))
        val estimator = LogTargetEstimator(delegate)

        val fitted = estimator.fit(listOf(row("one", 1L, 1.0), row("four", 3L, 4.0)))

        assertEquals(listOf(0.0, ln(4.0)), delegate.labels)
        assertEquals(3.0, fitted.predict(features("forecast")), absoluteTolerance = 1e-12)
    }

    @Test
    fun `zero and negative training labels are rejected`() {
        val estimator = LogTargetEstimator(CapturingEstimator(0.0))

        assertFailsWith<IllegalArgumentException> {
            estimator.fit(listOf(row("zero", 1L, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            estimator.fit(listOf(row("negative", 1L, -1.0)))
        }
    }

    @Test
    fun `overflow underflow and non-finite log predictions are rejected`() {
        listOf(1_000.0, -1_000.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { prediction ->
            val fitted =
                LogTargetEstimator(CapturingEstimator(prediction))
                    .fit(listOf(row("positive", 1L, 1.0)))

            assertFailsWith<IllegalArgumentException> {
                fitted.predict(features("forecast-$prediction"))
            }
        }
    }

    @Test
    fun `features and timing are unchanged for delegated fit`() {
        val delegate = CapturingEstimator(0.0)
        val source = row("source", 7L, 2.0)

        LogTargetEstimator(delegate).fit(listOf(source))

        val captured = requireNotNull(delegate.rows.singleOrNull())
        assertEquals(source.rowId, captured.rowId)
        assertEquals(source.decisionTime, captured.decisionTime)
        assertEquals(source.labelFrom, captured.labelFrom)
        assertEquals(source.labelTo, captured.labelTo)
        assertTrue(source.features === captured.features)
    }

    private fun row(
        id: String,
        decision: Long,
        label: Double,
    ): LabeledObservation =
        LabeledObservation(
            rowId = id,
            decisionTime = MarketTimestamp(decision),
            labelFrom = MarketTimestamp(decision),
            labelTo = MarketTimestamp(decision + 1L),
            features = features(id),
            label = label,
        )

    private fun features(id: String): FeatureVector =
        FeatureVector(id, mapOf("lag_variance" to 1.0))

    private class CapturingEstimator(
        private val logPrediction: Double,
    ) : Estimator {
        var rows: List<LabeledObservation> = emptyList()
        val labels: List<Double>
            get() = rows.map(LabeledObservation::label)

        override fun fit(rows: List<LabeledObservation>): FittedEstimator {
            this.rows = rows
            return object : FittedEstimator {
                override fun predict(features: FeatureVector): Double = logPrediction
            }
        }
    }
}
