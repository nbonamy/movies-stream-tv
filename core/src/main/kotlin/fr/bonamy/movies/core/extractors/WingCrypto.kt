package fr.bonamy.movies.core.extractors

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wing v2 envelope using platform EC key generation, ECDH, HKDF and AES-GCM. */
internal class WingCrypto(serverPublicSec1: ByteArray, private val keyId: Int) {
    private val marker = "lumen-gate-v2".toByteArray(Charsets.UTF_8)
    private val parameters = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
    private val prime = (parameters.curve.field as java.security.spec.ECFieldFp).p
    private val a = parameters.curve.a
    private val keyFactory = KeyFactory.getInstance("EC")
    private val serverPublic = keyFactory.generatePublic(ECPublicKeySpec(decodePoint(serverPublicSec1), parameters))

    init { require(keyId in 0..255) }

    /** Result matches seal_request: responseKey32 + keyId1 + ephemeral65 + HTTP body. */
    fun seal(plaintext: ByteArray): ByteArray {
        val random = SecureRandom()
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()
        // The server sees only the public point: platform key generation replaces
        // the website's seed hashing while preserving the same wire protocol.
        val ephemeral = encodePoint((pair.public as ECPublicKey).w)
        val privateKey = pair.private
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(serverPublic, true)
            generateSecret()
        }
        val requestKey = hkdf(shared, ephemeral, marker + "|c2s".toByteArray())
        val responseKey = hkdf(shared, ephemeral, marker + "|s2c".toByteArray())
        val nonce = ByteArray(12).also(random::nextBytes)
        val aad = marker + byteArrayOf(0, 1, keyId.toByte()) + ephemeral
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(requestKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }
        shared.fill(0)
        requestKey.fill(0)
        val body = byteArrayOf(2, keyId.toByte()) + ephemeral + nonce + ciphertext
        return responseKey + byteArrayOf(keyId.toByte()) + ephemeral + body
    }

    fun requestBody(sealed: ByteArray): ByteArray {
        require(sealed.size >= 98 + 95) { "Invalid sealed request" }
        return sealed.copyOfRange(98, sealed.size)
    }

    /** Returns authenticated plaintext JSON. Caller validates status/data envelope. */
    fun decryptResponse(response: ByteArray, sealed: ByteArray): ByteArray {
        require(response.size >= 28) { "Invalid response envelope" }
        require(sealed.size >= 98) { "Invalid sealed request" }
        val responseKey = sealed.copyOfRange(0, 32)
        val aad = marker + byteArrayOf(0, 2, sealed[32]) + sealed.copyOfRange(33, 98)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(responseKey, "AES"), GCMParameterSpec(128, response.copyOfRange(0, 12)))
                updateAAD(aad)
                doFinal(response, 12, response.size - 12)
            }
        } finally { responseKey.fill(0) }
    }

    private fun hkdf(shared: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(shared)
        return try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.doFinal(info + byteArrayOf(1))
        } finally { prk.fill(0) }
    }

    private fun decodePoint(bytes: ByteArray): ECPoint {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Expected uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        require(x < prime && y < prime) { "Point coordinates out of range" }
        require(y.multiply(y).mod(prime) == (x.pow(3) + a * x + parameters.curve.b).mod(prime)) { "Point not on curve" }
        return ECPoint(x, y)
    }

    private fun encodePoint(point: ECPoint): ByteArray = byteArrayOf(4) + coordinate(point.affineX) + coordinate(point.affineY)

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size >= 32) bytes.copyOfRange(bytes.size - 32, bytes.size)
        else ByteArray(32 - bytes.size) + bytes
    }

}
