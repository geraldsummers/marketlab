package dev.marketlab.research

import java.nio.file.Path
import java.time.Instant

data class ResearchCliConfig(
    val dataRoot: Path,
    val artifactRoot: Path,
    val coins: List<String>,
    val startInclusive: Instant,
    val endExclusive: Instant,
    val deterministicSeed: Long,
    val sourceRevision: String,
    val sourceRevisionOrigin: SourceRevisionOrigin,
    val productionMode: Boolean,
) {
    init {
        require(coins.isNotEmpty()) { "at least one coin is required" }
        require(coins == coins.distinct().sorted()) { "coins must be unique and sorted" }
        require(coins.all(COIN_PATTERN::matches)) { "invalid Hyperliquid coin" }
        require(startInclusive.isBefore(endExclusive)) { "start must precede end" }
        require(startInclusive.toEpochMilli() % HOUR_MILLIS == 0L) {
            "start must be aligned to a UTC hour"
        }
        require(endExclusive.toEpochMilli() % HOUR_MILLIS == 0L) {
            "end must be aligned to a UTC hour"
        }
        require((endExclusive.toEpochMilli() - startInclusive.toEpochMilli()) % HOUR_MILLIS == 0L) {
            "research interval must contain whole UTC hours"
        }
        require(sourceRevision.isNotBlank()) { "source revision cannot be blank" }
        require(!productionMode || SOURCE_REVISION_PATTERN.matches(sourceRevision)) {
            "production source revision must be a lowercase 40- or 64-character hash"
        }
        require((sourceRevisionOrigin == SourceRevisionOrigin.LOCAL_OVERRIDE) == !productionMode) {
            "non-production mode and local-override provenance must agree"
        }
    }

    val requestedHours: Int
        get() = Math.toIntExact(
            (endExclusive.toEpochMilli() - startInclusive.toEpochMilli()) / HOUR_MILLIS,
        )

    companion object {
        internal const val HOUR_MILLIS = 3_600_000L
        private val COIN_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        private val SOURCE_REVISION_PATTERN = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    }
}

enum class SourceRevisionOrigin {
    CLI,
    ENVIRONMENT,
    LOCAL_OVERRIDE,
}

object ResearchCliParser {
    val USAGE: String =
        """
        Usage: marketlab-research --start <UTC-hour> --end <UTC-hour> [options]

        Required (or equivalent environment variable):
          --start <ISO-8601>          MARKETLAB_START
          --end <ISO-8601>            MARKETLAB_END

        Options:
          --data-root <path>          MARKETLAB_DATA_ROOT (default: /mnt/media/marketlab/raw)
          --artifact-root <path>      MARKETLAB_ARTIFACT_ROOT (default: /mnt/stack/marketlab/active-artifacts)
          --coins <BTC,ETH,...>       MARKETLAB_COINS (default: BTC)
          --seed <signed-long>        MARKETLAB_SEED (default: 21745394912678988)
          --source-revision <hash>    MARKETLAB_SOURCE_REVISION (required in production)
          --allow-unversioned         MARKETLAB_ALLOW_UNVERSIONED (local/test only)
        """.trimIndent()

    fun parse(
        arguments: Array<String>,
        environment: Map<String, String> = System.getenv(),
    ): ResearchCliConfig {
        val options = parseOptions(arguments)
        fun value(option: String, environmentName: String, default: String? = null): String =
            options[option]
                ?: environment[environmentName]?.takeIf(String::isNotBlank)
                ?: default
                ?: throw IllegalArgumentException("missing --$option\n$USAGE")

        val allowUnversioned = parseBoolean(
            "allow-unversioned",
            options["allow-unversioned"]
                ?: environment["MARKETLAB_ALLOW_UNVERSIONED"]
                ?: "false",
        )
        val cliRevision = options["source-revision"]
        val environmentRevision = environment["MARKETLAB_SOURCE_REVISION"]?.takeIf(String::isNotBlank)
        val sourceRevision = cliRevision ?: environmentRevision ?: if (allowUnversioned) {
            LOCAL_OVERRIDE_REVISION
        } else {
            throw IllegalArgumentException(
                "missing --source-revision (or MARKETLAB_SOURCE_REVISION); " +
                    "use --allow-unversioned only for local/test runs\n$USAGE",
            )
        }
        val revisionOrigin = when {
            allowUnversioned -> SourceRevisionOrigin.LOCAL_OVERRIDE
            cliRevision != null -> SourceRevisionOrigin.CLI
            else -> SourceRevisionOrigin.ENVIRONMENT
        }
        val coins = value("coins", "MARKETLAB_COINS", "BTC")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        return ResearchCliConfig(
            dataRoot = Path.of(
                value("data-root", "MARKETLAB_DATA_ROOT", "/mnt/media/marketlab/raw"),
            ).toAbsolutePath().normalize(),
            artifactRoot = Path.of(
                value(
                    "artifact-root",
                    "MARKETLAB_ARTIFACT_ROOT",
                    "/mnt/stack/marketlab/active-artifacts",
                ),
            ).toAbsolutePath().normalize(),
            coins = coins,
            startInclusive = parseInstant(
                "start",
                value("start", "MARKETLAB_START"),
            ),
            endExclusive = parseInstant(
                "end",
                value("end", "MARKETLAB_END"),
            ),
            deterministicSeed = value(
                "seed",
                "MARKETLAB_SEED",
                DEFAULT_SEED.toString(),
            ).toLongOrNull() ?: throw IllegalArgumentException("--seed must be a signed 64-bit integer"),
            sourceRevision = sourceRevision,
            sourceRevisionOrigin = revisionOrigin,
            productionMode = !allowUnversioned,
        )
    }

    private fun parseOptions(arguments: Array<String>): Map<String, String> {
        if (arguments.any { it == "--help" || it == "-h" }) {
            throw HelpRequestedException(USAGE)
        }
        val allowed = setOf(
            "allow-unversioned",
            "artifact-root",
            "coins",
            "data-root",
            "end",
            "seed",
            "source-revision",
            "start",
        )
        val flags = setOf("allow-unversioned")
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < arguments.size) {
            val token = arguments[index]
            require(token.startsWith("--")) { "unexpected positional argument '$token'\n$USAGE" }
            val withoutPrefix = token.removePrefix("--")
            val separator = withoutPrefix.indexOf('=')
            val key: String
            val value: String
            if (separator >= 0) {
                key = withoutPrefix.substring(0, separator)
                value = withoutPrefix.substring(separator + 1)
                index += 1
            } else {
                key = withoutPrefix
                if (key in flags) {
                    value = "true"
                    index += 1
                } else {
                    require(index + 1 < arguments.size) { "missing value for --$key\n$USAGE" }
                    value = arguments[index + 1]
                    index += 2
                }
            }
            require(key in allowed) { "unknown option --$key\n$USAGE" }
            require(value.isNotBlank()) { "--$key cannot be blank" }
            require(result.put(key, value) == null) { "--$key was specified more than once" }
        }
        return result
    }

    private fun parseInstant(name: String, value: String): Instant =
        try {
            Instant.parse(value)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("--$name must be an ISO-8601 UTC instant", exception)
        }

    private fun parseBoolean(name: String, value: String): Boolean =
        when (value.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> throw IllegalArgumentException("--$name must be true/false or 1/0")
        }

    private const val DEFAULT_SEED = 0x4D41524B45544CL
    private const val LOCAL_OVERRIDE_REVISION = "unversioned-local-override"
}

class HelpRequestedException(message: String) : IllegalArgumentException(message)
