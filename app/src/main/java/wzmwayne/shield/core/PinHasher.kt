package wzmwayne.shield.core

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN 哈希（纯逻辑，可单测）：PBKDF2-HmacSHA1 + 随机盐。
 * 只存「盐 + 哈希」，不存明文。
 */
object PinHasher {

    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun randomSalt(size: Int = 16): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded
    }

    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean {
        val actual = hash(pin, salt)
        // 定长比较，避免时序侧信道
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}
