package com.brick.app.core.security

import java.security.MessageDigest

/**
 * Cryptographic utilities for hashing and securely comparing physical and QR token identifiers.
 *
 * All token identifiers (hardware NFC tag UIDs and generated QR payloads) are salted with
 * [APP_SALT] and hashed using SHA-256 before storage in Room database.
 */
object CryptoUtils {

    /**
     * Fixed application-level salt for offline token hashing.
     */
    const val APP_SALT: String = "BRICK_OFFLINE_SALT_V1_SECURE"

    /**
     * Hashes a raw byte array (e.g. NFC tag hardware UID) using SHA-256 with [APP_SALT].
     *
     * @param input Raw byte array from tag hardware UID
     * @return Lowercase 64-character hexadecimal SHA-256 digest
     */
    fun sha256(input: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(APP_SALT.toByteArray(Charsets.UTF_8))
        val digest = md.digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Hashes a string payload (e.g. printed QR token string) using SHA-256 with [APP_SALT].
     *
     * @param input String payload to hash
     * @return Lowercase 64-character hexadecimal SHA-256 digest
     */
    fun sha256(input: String): String {
        return sha256(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * Constant-time string comparison to prevent timing side-channel attacks during token lookup.
     *
     * @param a First string to compare
     * @param b Second string to compare
     * @return true if both strings are non-null and identical, or both are null; false otherwise
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) {
            return a == b
        }
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     *
     * @param a First byte array
     * @param b Second byte array
     * @return true if both arrays are non-null and identical, or both are null; false otherwise
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) {
            return a === b
        }
        return MessageDigest.isEqual(a, b)
    }
}
