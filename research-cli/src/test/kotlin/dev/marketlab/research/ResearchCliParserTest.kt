package dev.marketlab.research

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResearchCliParserTest {
    @Test
    fun `command line overrides environment and canonicalizes coin ordering`() {
        val result = ResearchCliParser.parse(
            arguments = arrayOf(
                "--data-root=/tmp/marketlab-data",
                "--artifact-root",
                "/tmp/marketlab-artifacts",
                "--coins",
                "ETH,BTC,ETH",
                "--start",
                "2026-07-18T00:00:00Z",
                "--end",
                "2026-07-28T00:00:00Z",
                "--seed",
                "41",
                "--source-revision",
                SOURCE_REVISION,
            ),
            environment = mapOf(
                "MARKETLAB_COINS" to "SOL",
                "MARKETLAB_START" to "2026-01-01T00:00:00Z",
                "MARKETLAB_END" to "2026-01-02T00:00:00Z",
            ),
        )

        assertEquals(Path.of("/tmp/marketlab-data"), result.dataRoot)
        assertEquals(Path.of("/tmp/marketlab-artifacts"), result.artifactRoot)
        assertEquals(listOf("BTC", "ETH"), result.coins)
        assertEquals(Instant.parse("2026-07-18T00:00:00Z"), result.startInclusive)
        assertEquals(Instant.parse("2026-07-28T00:00:00Z"), result.endExclusive)
        assertEquals(41L, result.deterministicSeed)
        assertEquals(SOURCE_REVISION, result.sourceRevision)
        assertEquals(SourceRevisionOrigin.CLI, result.sourceRevisionOrigin)
        assertEquals(true, result.productionMode)
        assertEquals(240, result.requestedHours)
    }

    @Test
    fun `unaligned research range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ResearchCliParser.parse(
                arrayOf(
                    "--start",
                    "2026-07-18T00:01:00Z",
                    "--end",
                    "2026-07-28T00:00:00Z",
                ),
                mapOf("MARKETLAB_SOURCE_REVISION" to SOURCE_REVISION),
            )
        }
    }

    @Test
    fun `storage defaults match the cold raw and hot artifact tiers`() {
        val result = ResearchCliParser.parse(
            arguments = emptyArray(),
            environment = mapOf(
                "MARKETLAB_START" to "2026-07-18T00:00:00Z",
                "MARKETLAB_END" to "2026-07-28T00:00:00Z",
                "MARKETLAB_ALLOW_UNVERSIONED" to "1",
            ),
        )

        assertEquals(Path.of("/mnt/media/marketlab/raw"), result.dataRoot)
        assertEquals(
            Path.of("/mnt/stack/marketlab/active-artifacts"),
            result.artifactRoot,
        )
        assertEquals(false, result.productionMode)
        assertEquals(SourceRevisionOrigin.LOCAL_OVERRIDE, result.sourceRevisionOrigin)
    }

    @Test
    fun `production invocation rejects a missing source revision`() {
        assertFailsWith<IllegalArgumentException> {
            ResearchCliParser.parse(
                arguments = arrayOf(
                    "--start",
                    "2026-07-18T00:00:00Z",
                    "--end",
                    "2026-07-28T00:00:00Z",
                ),
                environment = emptyMap(),
            )
        }
    }

    private companion object {
        const val SOURCE_REVISION =
            "78f953c50a27a5e81a6f058fcf4bc1de0e983cc49b4d1ed8c06c7808b695c506"
    }
}
