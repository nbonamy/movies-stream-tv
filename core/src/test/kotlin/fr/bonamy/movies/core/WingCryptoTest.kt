package fr.bonamy.movies.core

import fr.bonamy.movies.core.extractors.WingCrypto
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WingCryptoTest {
    @Test fun `native request interoperates with independent server and rejects corrupted response`() {
        val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = server.public as ECPublicKey
        fun coordinate(n: BigInteger): ByteArray = n.toByteArray().let { value ->
            if (value.size >= 32) value.takeLast(32).toByteArray() else ByteArray(32 - value.size) + value
        }
        val crypto = WingCrypto(byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY), 2)
        val input = """{"path":"/Nebula/movie","payload":{"tmdb":"348"}}""".toByteArray()
        val sealed = crypto.seal(input)
        val body = crypto.requestBody(sealed)
        assertArrayEquals(byteArrayOf(2, 2), body.copyOfRange(0, 2))
        val ephemeral = body.copyOfRange(2, 67)
        val clientPoint = ECPoint(BigInteger(1, ephemeral.copyOfRange(1, 33)), BigInteger(1, ephemeral.copyOfRange(33, 65)))
        val clientKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(clientPoint, public.params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(server.private); doPhase(clientKey, true); generateSecret() }
        fun key(direction: String): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ephemeral, "HmacSHA256")) }.doFinal(shared)
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
                .doFinal("lumen-gate-v2|$direction".toByteArray() + byteArrayOf(1))
        }
        val decoded = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key("c2s"), "AES"), GCMParameterSpec(128, body.copyOfRange(67, 79)))
            updateAAD("lumen-gate-v2".toByteArray() + byteArrayOf(0, 1, 2) + ephemeral)
            doFinal(body.copyOfRange(79, body.size))
        }
        assertArrayEquals(input, decoded)
        val reply = """{"status":200,"data":{"stream":[]}}""".toByteArray()
        val nonce = ByteArray(12) { (it + 30).toByte() }
        val response = nonce + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key("s2c"), "AES"), GCMParameterSpec(128, nonce))
            updateAAD("lumen-gate-v2".toByteArray() + byteArrayOf(0, 2, 2) + ephemeral)
            doFinal(reply)
        }
        assertArrayEquals(reply, crypto.decryptResponse(response, sealed))
        response[response.lastIndex] = (response.last().toInt() xor 1).toByte()
        try { crypto.decryptResponse(response, sealed); fail("Tampered response authenticated") }
        catch (_: AEADBadTagException) { }
    }
}
