package com.nexiolab.socialai.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nexiolab.socialai.domain.repository.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encrypted secrets, each in its own file under noBackupFilesDir. The key is generated
 * inside Android Keystore and never leaves it, which is why secrets cannot move to another device.
 *
 * This protects data at rest. It does not make a client-side API key impossible to extract from a
 * compromised device; commercial multi-user use must move these keys to a trusted backend.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val dir = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }

    private fun fileFor(alias: String): File {
        require(alias.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid credential alias" }
        return File(dir, "$alias.enc")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override suspend fun put(alias: String, secret: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(alias.toByteArray())
        val sealed = cipher.iv + cipher.doFinal(secret.toByteArray())
        val temp = File(dir, "$alias.tmp")
        temp.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
        check(temp.renameTo(fileFor(alias))) { "Could not store the credential" }
    }

    override suspend fun get(alias: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(alias)
        if (!file.exists()) return@withContext null
        val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.updateAAD(alias.toByteArray())
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }

    override suspend fun has(alias: String) = withContext(Dispatchers.IO) { fileFor(alias).exists() }

    override suspend fun delete(alias: String) {
        withContext(Dispatchers.IO) { fileFor(alias).delete() }
    }

    override suspend fun masked(alias: String): String? = get(alias)?.let(::mask)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nexio.credentials.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun mask(secret: String): String =
            if (secret.length <= 8) "••••••••" else "••••••••" + secret.takeLast(4)
    }
}
