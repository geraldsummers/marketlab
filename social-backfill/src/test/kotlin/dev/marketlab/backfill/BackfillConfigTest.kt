package dev.marketlab.backfill

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackfillConfigTest {
    @Test
    fun `production period and sources parse explicitly`() {
        val config =
            BackfillConfig.parse(
                arrayOf(
                    "--output-root",
                    "/mnt/media/marketlab/raw/social-backfill",
                    "--models-root",
                    "/mnt/stack/marketlab/models",
                    "--model-lock",
                    "/opt/marketlab/research/sentiment-models.lock.json",
                    "--program-lock",
                    "/opt/marketlab/research/social-backfill-program.lock.json",
                    "--start",
                    "2025-10-04T00:00:00Z",
                    "--end",
                    "2026-07-01T00:00:00Z",
                    "--sources",
                    "market,social",
                ),
            )

        assertEquals(Path.of("/mnt/media/marketlab/raw/social-backfill"), config.outputRoot)
        assertEquals(setOf("market", "social"), config.sources)
        assertEquals(ASSETS.map(AssetSpec::symbol).toSet(), config.assets)
        assertEquals(null, config.analysisLock)
    }

    @Test
    fun `unknown sources and broad roots fail closed`() {
        val base =
            arrayOf(
                "--output-root", "/",
                "--models-root", "/mnt/models",
                "--model-lock", "/mnt/model-lock.json",
                "--program-lock", "/mnt/program-lock.json",
                "--start", "2025-10-04T00:00:00Z",
                "--end", "2026-07-01T00:00:00Z",
                "--sources", "fake",
            )
        assertFailsWith<IllegalArgumentException> { BackfillConfig.parse(base) }
    }
}
