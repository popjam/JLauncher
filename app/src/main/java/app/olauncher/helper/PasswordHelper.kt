package app.olauncher.helper

import java.security.MessageDigest

object PasswordHelper {
    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPassword(input: String, storedHash: String): Boolean {
        return hashPassword(input) == storedHash
    }
}
