package com.ahorro.vpn

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Config cifrada (.ahorro), pedido por Sam 18 nov 2026:
 * el archivo de config no se puede leer abriéndolo, va cifrado.
 * - AES-256-GCM, clave = SHA-256 de una frase fija compilada en la app.
 * - Formato: "AHORRO1" (7 bytes) + IV de 12 bytes + cifrado + tag.
 * - La app descifra al importar y luego detecta el tipo igual que antes
 *   ([Interface]/[Peer] = WireGuard, [Local] = local).
 * - El servidor cifra con wireguard-final/server/cifrar-config.sh,
 *   que usa la misma frase y el mismo formato.
 *
 * HONESTO: la clave vive dentro de la app, así que esto protege contra
 * quien abre el archivo por curiosidad o lo intercepta por el camino,
 * no contra quien descompile la APK buscando la clave. Para seguridad
 * real haría falta una contraseña que cada amigo teclee al importar.
 */
object ConfigCrypto {
    private const val MAGIC = "AHORRO1"
    private const val PASSPHRASE = "AhorroVPN-config-2026"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun key(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, "AES")
    }

    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.length) return false
        return String(data, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC
    }

    fun encrypt(plainText: String): ByteArray {
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return MAGIC.toByteArray(Charsets.US_ASCII) + iv + cipherText
    }

    /** Null si no es un archivo .ahorro válido o está dañado. */
    fun decrypt(data: ByteArray): String? {
        return try {
            if (!isEncrypted(data)) return null
            val iv = data.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
            val cipherText = data.copyOfRange(MAGIC.length + IV_BYTES, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(cipherText)
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
