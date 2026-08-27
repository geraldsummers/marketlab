package dev.marketlab.evidence

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImmutableEvidenceStoreTest {
    @Test
    fun `objects are content addressed and idempotent`() {
        val root = createTempDirectory("evidence-store")
        val store = ImmutableEvidenceStore(root)
        val bytes = "observed-real-response".toByteArray()

        val first = store.storeObject(bytes, "json")
        val second = store.storeObject(bytes, "json")

        assertEquals(first, second)
        assertEquals(first.sha256, store.sha256(root.resolve(first.uri)))
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve(first.uri)).toList())
    }

    @Test
    fun `publication rejects traversal and conflicting immutable bytes`() {
        val root = createTempDirectory("evidence-store")
        val store = ImmutableEvidenceStore(root)
        assertFailsWith<IllegalArgumentException> {
            store.publish(byteArrayOf(1), root.resolve("../escape"))
        }
        val target = root.resolve("manifests/frozen.json")
        store.publish(byteArrayOf(1), target)
        assertFailsWith<IllegalStateException> {
            store.publish(byteArrayOf(2), target)
        }
        assertTrue(Files.isRegularFile(target))
    }
}
