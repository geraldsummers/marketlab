package dev.marketlab.theory

import dev.marketlab.contracts.FiniteDouble
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Window {
    @Serializable
    @SerialName("duration")
    data class Duration(val millis: Long) : Window {
        init {
            require(millis > 0) { "window duration must be positive" }
        }
    }

    @Serializable
    @SerialName("bars")
    data class Bars(val count: Int) : Window {
        init {
            require(count > 0) { "window bar count must be positive" }
        }
    }

    @Serializable
    @SerialName("events")
    data class Events(val count: Int) : Window {
        init {
            require(count > 0) { "window event count must be positive" }
        }
    }
}

@Serializable
sealed interface NumericExpression {
    @Serializable
    @SerialName("field")
    data class Field(val requirement: String, val field: String) : NumericExpression {
        init {
            require(requirement.isNotBlank() && field.isNotBlank()) {
                "field expression requires non-blank requirement and field names"
            }
        }
    }

    @Serializable
    @SerialName("feature")
    data class Feature(val name: String) : NumericExpression {
        init {
            require(name.isNotBlank()) { "feature reference cannot be blank" }
        }
    }

    @Serializable
    @SerialName("constant")
    data class Constant(val value: FiniteDouble) : NumericExpression

    @Serializable
    @SerialName("lag")
    data class Lag(val input: NumericExpression, val by: Window) : NumericExpression

    @Serializable
    @SerialName("rolling")
    data class Rolling(
        val input: NumericExpression,
        val window: Window,
        val aggregation: Aggregation,
        val minimumObservations: Int = 1,
    ) : NumericExpression {
        init {
            require(minimumObservations > 0) { "minimum observations must be positive" }
        }
    }

    @Serializable
    @SerialName("unary")
    data class Unary(val operation: UnaryOperation, val input: NumericExpression) : NumericExpression

    @Serializable
    @SerialName("binary")
    data class Binary(
        val operation: BinaryOperation,
        val left: NumericExpression,
        val right: NumericExpression,
    ) : NumericExpression

    /**
     * A deterministic UTC clock feature derived from a causally available
     * epoch-millisecond timestamp. This keeps calendar features explicit in a
     * frozen plan without pretending that one-hot indicators came from the
     * market-data source.
     */
    @Serializable
    @SerialName("utc_hour_indicator")
    data class UtcHourIndicator(
        val timestamp: NumericExpression,
        val hour: Int,
    ) : NumericExpression {
        init {
            require(hour in 0..23) { "UTC hour must be between 0 and 23" }
        }
    }

    @Serializable
    @SerialName("conditional")
    data class Conditional(
        val condition: BooleanExpression,
        val whenTrue: NumericExpression,
        val whenFalse: NumericExpression,
    ) : NumericExpression
}

@Serializable
enum class Aggregation {
    SUM,
    MEAN,
    MINIMUM,
    MAXIMUM,
    STANDARD_DEVIATION,
    REALIZED_VARIANCE,
    COUNT,
}

@Serializable
enum class UnaryOperation {
    ABSOLUTE,
    NEGATE,
    LOG,
    EXP,
    SQUARE,
    SQUARE_ROOT,
    SIGN,
}

@Serializable
enum class BinaryOperation {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MINIMUM,
    MAXIMUM,
}

@Serializable
sealed interface BooleanExpression {
    @Serializable
    @SerialName("comparison")
    data class Compare(
        val operation: NumericComparison,
        val left: NumericExpression,
        val right: NumericExpression,
    ) : BooleanExpression

    @Serializable
    @SerialName("and")
    data class And(val terms: List<BooleanExpression>) : BooleanExpression {
        init {
            require(terms.size >= 2) { "and expression requires at least two terms" }
        }
    }

    @Serializable
    @SerialName("or")
    data class Or(val terms: List<BooleanExpression>) : BooleanExpression {
        init {
            require(terms.size >= 2) { "or expression requires at least two terms" }
        }
    }

    @Serializable
    @SerialName("not")
    data class Not(val input: BooleanExpression) : BooleanExpression
}

@Serializable
enum class NumericComparison {
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    EQUAL,
    GREATER_THAN_OR_EQUAL,
    GREATER_THAN,
}

@Serializable
data class FeatureDefinition(
    val name: String,
    val expression: NumericExpression,
    val description: String,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) {
            "feature name must be portable snake_case"
        }
        require(description.isNotBlank()) { "feature description cannot be blank" }
    }
}

@Serializable
data class FeatureGraph(val features: List<FeatureDefinition>) {
    init {
        require(features.isNotEmpty()) { "feature graph cannot be empty" }
        require(features.map(FeatureDefinition::name) == features.map(FeatureDefinition::name).distinct().sorted()) {
            "feature definitions must have unique, sorted names"
        }
    }
}

fun field(requirement: String, field: String): NumericExpression =
    NumericExpression.Field(requirement, field)

fun feature(name: String): NumericExpression = NumericExpression.Feature(name)

fun constant(value: Double): NumericExpression = NumericExpression.Constant(FiniteDouble(value))

fun lag(input: NumericExpression, by: Window): NumericExpression = NumericExpression.Lag(input, by)

fun rolling(
    input: NumericExpression,
    window: Window,
    aggregation: Aggregation,
    minimumObservations: Int = 1,
): NumericExpression = NumericExpression.Rolling(input, window, aggregation, minimumObservations)

fun unary(operation: UnaryOperation, input: NumericExpression): NumericExpression =
    NumericExpression.Unary(operation, input)

fun binary(
    operation: BinaryOperation,
    left: NumericExpression,
    right: NumericExpression,
): NumericExpression = NumericExpression.Binary(operation, left, right)

fun utcHourIndicator(timestamp: NumericExpression, hour: Int): NumericExpression =
    NumericExpression.UtcHourIndicator(timestamp, hour)

fun compare(
    operation: NumericComparison,
    left: NumericExpression,
    right: NumericExpression,
): BooleanExpression = BooleanExpression.Compare(operation, left, right)
