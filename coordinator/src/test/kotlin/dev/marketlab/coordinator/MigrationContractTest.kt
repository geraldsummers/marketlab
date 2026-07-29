package dev.marketlab.coordinator

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class MigrationContractTest {
    @Test
    fun `ingestion operation identity is unique in PostgreSQL`() {
        val resource =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "db/migration/V2__coordinator_ingestion_idempotency.sql",
                ),
            )
        val sql = resource.bufferedReader().use { it.readText() }

        assertContains(sql, "CREATE UNIQUE INDEX")
        assertContains(sql, "ingestionOperationKey")
    }
}
