package dev.marketlab.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseConfigTest {
    @Test
    fun `parses standard PostgreSQL URL without losing credentials`() {
        val config =
            DatabaseConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_DATABASE_URL" to
                        "postgresql://marketlab:secret@db.internal:5544/control?sslmode=require",
                ),
            )

        assertEquals(
            "jdbc:postgresql://db.internal:5544/control?sslmode=require",
            config.jdbcUrl,
        )
        assertEquals("marketlab", config.username)
        assertEquals("secret", config.password)
    }

    @Test
    fun `explicit credentials override URL credentials`() {
        val config =
            DatabaseConfig.fromEnvironment(
                mapOf(
                    "MARKETLAB_DATABASE_URL" to "postgresql://url-user:url-pass@db/control",
                    "MARKETLAB_DATABASE_USER" to "explicit-user",
                    "MARKETLAB_DATABASE_PASSWORD" to "explicit-pass",
                ),
            )

        assertEquals("explicit-user", config.username)
        assertEquals("explicit-pass", config.password)
    }

    @Test
    fun `rejects non PostgreSQL databases`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:marketlab",
                username = null,
                password = null,
            )
        }
    }
}

