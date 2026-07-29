package dev.marketlab.persistence

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class MigrationContractTest {
    @Test
    fun `migration contains durable leases and append only paper ledger`() {
        val sql =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("db/migration/V1__control_plane.sql"),
            ).bufferedReader().use { it.readText() }

        assertContains(sql, "jobs_claim_idx")
        assertContains(sql, "lease_token UUID")
        assertContains(sql, "idempotency_records")
        assertContains(sql, "paper_events is append-only")
        assertContains(sql, "data_quality_findings")
        assertContains(sql, "experiment_runs")
    }
}
