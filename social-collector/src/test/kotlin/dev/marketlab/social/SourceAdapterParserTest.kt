package dev.marketlab.social

import dev.marketlab.contracts.data.InformationMutation
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.math.BigInteger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.bouncycastle.crypto.ec.CustomNamedCurves

class SourceAdapterParserTest {
    @Test
    fun `Bluesky parser keeps publisher and receive clocks distinct`() {
        val bytes =
            """
            {"did":"did:plc:abc","time_us":1785230000000000,"kind":"commit",
             "commit":{"operation":"create","collection":"app.bsky.feed.post","rkey":"key",
             "record":{"createdAt":"2026-07-28T09:00:00Z","langs":["en"],"text":"Bitcoin is moving"}}}
            """.trimIndent().toByteArray()
        val received = Instant.parse("2026-07-28T09:00:02Z")
        val event =
            assertNotNull(
                BlueskyJetstreamAdapter(
                    client = io.ktor.client.HttpClient(),
                    endpoints = listOf(URI.create("wss://example.test")),
                ).parse(bytes, URI.create("wss://example.test"), received),
            )

        assertEquals(Instant.parse("2026-07-28T09:00:00Z"), event.eventTime)
        assertEquals(received, event.observedAt)
        assertEquals(InformationMutation.CREATE, event.mutation)
    }

    @Test
    fun `Nostr parser validates the canonical content hash`() {
        val secret = BigInteger.valueOf(3L)
        val curve = CustomNamedCurves.getByName("secp256k1")
        val publicPoint = curve.g.multiply(secret).normalize()
        val pubkey = publicPoint.affineXCoord.toBigInteger().toString(16).padStart(64, '0')
        val createdAt = 1_785_230_000L
        val tags = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("l")); add(JsonPrimitive("en")) }) }
        val content = "Bitcoin volatility is rising"
        val canonical =
            buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(1))
                add(tags)
                add(JsonPrimitive(content))
            }.toString()
        val id =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val event =
            buildJsonObject {
                put("id", id)
                put("pubkey", pubkey)
                put("created_at", createdAt)
                put("kind", 1)
                put("tags", tags)
                put("content", content)
                put("sig", sign(secret, pubkey, id))
            }
        val frame =
            buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(JsonPrimitive("sub"))
                add(event)
            }.toString().toByteArray()
        val parser =
            NostrRelayAdapter(
                client = io.ktor.client.HttpClient(),
                relays =
                    listOf(
                        URI.create("wss://one.example"),
                        URI.create("wss://two.example"),
                    ),
            )

        val parsed =
            assertIs<NostrRelayAdapter.NostrParse.Event>(
                parser.parse(
                    frame,
                    URI.create("wss://one.example"),
                    Instant.ofEpochSecond(createdAt + 1),
                ),
            )
        assertEquals(id, parsed.value.sourceEventId)
    }

    @Test
    fun `Farcaster parser preserves signed cast identity and epoch`() {
        val received = Instant.parse("2026-07-28T09:00:02Z")
        val page =
            FarcasterSnapchainAdapter(
                client = io.ktor.client.HttpClient(),
                eventsUri = URI.create("http://snapchain.test:3381/v1/events"),
                pollIntervalMillis = 1_000L,
            ).parsePage(
                """
                {
                  "nextPageEventId":43,
                  "events":[{
                    "type":"HUB_EVENT_TYPE_MERGE_MESSAGE",
                    "mergeMessageBody":{"message":{
                      "data":{"type":"MESSAGE_TYPE_CAST_ADD","fid":123,"timestamp":60,
                        "castAddBody":{"text":"Bitcoin is moving"}},
                      "hash":"0x0123456789abcdef0123456789abcdef01234567",
                      "signature":"signed-envelope"
                    }}
                  }]
                }
                """.trimIndent().toByteArray(),
                URI.create("http://snapchain.test:3381/v1/events"),
                received,
            )

        assertEquals(43L, page.nextEventId)
        assertEquals("0123456789abcdef0123456789abcdef01234567", page.events.single().sourceEventId)
        assertEquals(Instant.parse("2021-01-01T00:01:00Z"), page.events.single().eventTime)
    }

    private fun sign(secret: BigInteger, publicKeyHex: String, messageHex: String): String {
        val curve = CustomNamedCurves.getByName("secp256k1")
        val nonce = BigInteger.ONE
        val r = curve.g.multiply(nonce).normalize().affineXCoord.toBigInteger()
        val rBytes = unsigned32(r)
        val publicBytes = decodeHex(publicKeyHex)
        val message = decodeHex(messageHex)
        val tag = MessageDigest.getInstance("SHA-256").digest("BIP0340/challenge".toByteArray())
        val challenge =
            BigInteger(
                1,
                MessageDigest.getInstance("SHA-256").digest(tag + tag + rBytes + publicBytes + message),
            ).mod(curve.n)
        val s = nonce.add(challenge.multiply(secret)).mod(curve.n)
        return (rBytes + unsigned32(s)).joinToString("") { "%02x".format(it) }
    }

    private fun unsigned32(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        return if (encoded.size == 33 && encoded[0] == 0.toByte()) {
            encoded.copyOfRange(1, 33)
        } else {
            ByteArray(32 - encoded.size) + encoded
        }
    }

    private fun decodeHex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
