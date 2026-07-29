package dev.marketlab.worker

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkerProtocolSecurityTest {
    @Test
    fun `rejects path-like CLI shape`() {
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--manifest", "/tmp/manifest.json", "--output"))
        }
    }
}

