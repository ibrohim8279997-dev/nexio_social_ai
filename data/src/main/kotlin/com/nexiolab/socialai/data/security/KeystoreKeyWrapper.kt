package com.nexiolab.socialai.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nexiolab.socialai.domain.transfer.KeyWrapper
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the one-time transfer key with a device-bound Android Keystore key while a half-finished
 * transfer waits on the destination. The wrapped bytes sit in noBackupFilesDir next to the kept
 * chunks and are useless on any other device. The key itself never goes into logs, backups or a QR code.
 */
class KeystoreKeyWrapper : KeyWrapper {
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

    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nexio.transfer.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
