package dev.marketlab.service

import dev.marketlab.persistence.Database
import dev.marketlab.persistence.DatabaseConfig
import dev.marketlab.persistence.TheoryRepository
import dev.marketlab.theories.AcademicTheoryRegistry
import dev.marketlab.theory.TheoryPlanHasher
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class TheoryRegistrationIntegrationTest {
    @Test
    fun `academic catalog registration is complete immutable and idempotent`() {
        val url = System.getenv("MARKETLAB_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "MARKETLAB_TEST_DATABASE_URL is not configured")
        val config =
            DatabaseConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_DATABASE_URL" to requireNotNull(url),
                    "MARKETLAB_DATABASE_USER" to
                        (System.getenv("MARKETLAB_TEST_DATABASE_USER") ?: "marketlab"),
                    "MARKETLAB_DATABASE_PASSWORD" to
                        (System.getenv("MARKETLAB_TEST_DATABASE_PASSWORD") ?: "marketlab"),
                    "MARKETLAB_DATABASE_POOL_SIZE" to "2",
                ),
            )

        Database.open(config).use { database ->
            registerAcademicTheories(database.dataSource)
            registerAcademicTheories(database.dataSource)

            val repository = TheoryRepository(database.dataSource)
            AcademicTheoryRegistry.compileAll().forEach { plan ->
                val stored =
                    requireNotNull(
                        repository.get(plan.descriptor.id.value, plan.descriptor.version),
                    )
                assertEquals(TheoryPlanHasher.hash(plan).hex, stored.planHash)
                assertEquals(
                    TheoryPlanHasher.canonicalJson(plan),
                    stored.planCanonical,
                )
            }
        }
    }
}
