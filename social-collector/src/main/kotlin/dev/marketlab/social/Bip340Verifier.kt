package dev.marketlab.social

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint

internal object Bip340Verifier {
    private val parameters = CustomNamedCurves.getByName("secp256k1")
    private val curve = parameters.curve
    private val fieldPrime = curve.field.characteristic
    private val groupOrder = parameters.n
    private val sqrtExponent = fieldPrime.add(BigInteger.ONE).shiftRight(2)

    fun verify(
        publicKeyHex: String,
        messageHex: String,
        signatureHex: String,
    ): Boolean =
        runCatching {
            if (!HEX_64.matches(publicKeyHex) ||
                !HEX_64.matches(messageHex) ||
                !HEX_128.matches(signatureHex)
            ) {
                return false
            }
            val publicKey = decodeHex(publicKeyHex)
            val message = decodeHex(messageHex)
            val signature = decodeHex(signatureHex)
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (r >= fieldPrime || s >= groupOrder) return false
            val point = liftEvenY(BigInteger(1, publicKey)) ?: return false
            val challenge =
                BigInteger(
                    1,
                    taggedHash(
                        "BIP0340/challenge",
                        signature.copyOfRange(0, 32) + publicKey + message,
                    ),
                ).mod(groupOrder)
            val reconstructed =
                parameters.g
                    .multiply(s)
                    .subtract(point.multiply(challenge))
                    .normalize()
            !reconstructed.isInfinity &&
                !reconstructed.affineYCoord.toBigInteger().testBit(0) &&
                reconstructed.affineXCoord.toBigInteger() == r
        }.getOrDefault(false)

    private fun liftEvenY(x: BigInteger): ECPoint? {
        if (x >= fieldPrime) return null
        val c = x.modPow(BigInteger.valueOf(3L), fieldPrime).add(BigInteger.valueOf(7L)).mod(fieldPrime)
        var y = c.modPow(sqrtExponent, fieldPrime)
        if (y.multiply(y).mod(fieldPrime) != c) return null
        if (y.testBit(0)) y = fieldPrime.subtract(y)
        return curve.createPoint(x, y).normalize()
    }

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.US_ASCII))
        return sha256(tagHash + tagHash + message)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun decodeHex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private val HEX_64 = Regex("[0-9a-f]{64}")
    private val HEX_128 = Regex("[0-9a-f]{128}")
}
