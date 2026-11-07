package com.nexiolab.socialai.domain.backup

import com.nexiolab.socialai.domain.i18n.Msg
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/** What unlocks an archive: the owner's password, or a one-time key from a pairing QR code. */
sealed interface BackupSecret {
    class Password(val value: CharArray) : BackupSecret
    class RawKey(val value: ByteArray) : BackupSecret
}

/**
 * Streaming authenticated encryption: AES-256-GCM over fixed chunks, using the STREAM construction.
 * Each nonce holds a counter and a last-chunk flag and the header is authenticated with every chunk,
 * so reordering, truncating or editing any part of the file fails to decrypt. The password never
 * enters the file; only the salt and the iteration count do.
 */
object BackupCrypto {
    private val MAGIC = "NEXIOBK1".toByteArray()
    private const val FORMAT = 1
    private const val KDF_NONE = 0
    private const val KDF_PBKDF2 = 1
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 7
    private const val TAG_BITS = 128
    private const val CHUNK = 1 shl 20
    private const val MAX_ITERATIONS = 5_000_000
    private const val HEADER_BYTES = 8 + 1 + 1 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES
    const val ITERATIONS = 600_000

    private fun key(secret: BackupSecret, salt: ByteArray, iterations: Int): SecretKeySpec = when (secret) {
        is BackupSecret.RawKey -> SecretKeySpec(secret.value, "AES")
        is BackupSecret.Password -> {
            val spec = PBEKeySpec(secret.value, salt, iterations, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            SecretKeySpec(bytes, "AES")
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    fun encrypting(output: OutputStream, secret: BackupSecret, iterations: Int = ITERATIONS): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(FORMAT.toByte())
            .put((if (secret is BackupSecret.Password) KDF_PBKDF2 else KDF_NONE).toByte())
            .putInt(iterations).put(salt).put(prefix).array()
        output.write(header)
        return EncryptingStream(DataOutputStream(output), key(secret, salt, iterations), prefix, header)
    }

    fun decrypting(input: InputStream, secret: BackupSecret): InputStream {
        val header = ByteArray(HEADER_BYTES)
        val data = DataInputStream(input)
        try {
            data.readFully(header)
        } catch (e: EOFException) {
            throw BackupException(Msg.NOT_NEXIO_BACKUP())
        }
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException(Msg.NOT_NEXIO_BACKUP())
        val format = buffer.get().toInt()
        if (format != FORMAT) throw BackupException(Msg.BACKUP_NEWER_VERSION())
        val kdf = buffer.get().toInt()
        val iterations = buffer.getInt()
        if (kdf !in KDF_NONE..KDF_PBKDF2 || iterations !in 1..MAX_ITERATIONS) throw BackupException(Msg.BACKUP_DAMAGED())
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { buffer.get(it) }
        return DecryptingStream(data, key(secret, salt, iterations), prefix, header)
    }

    private class EncryptingStream(
        private val out: DataOutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var used = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var left = len
            while (left > 0) {
                // A full buffer is held back until more data proves it is not the last chunk.
                if (used == CHUNK) flushChunk(last = false)
                val take = minOf(left, CHUNK - used)
                System.arraycopy(b, offset, buffer, used, take)
                used += take
                offset += take
                left -= take
            }
        }

        private fun flushChunk(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(buffer, 0, used)
            out.writeInt(sealed.size)
            out.write(sealed)
            counter++
            used = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(last = true)
            out.close()
        }
    }

    private class DecryptingStream(
        private val input: DataInputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var counter = 0
        private var finished = false
        private var pending: ByteArray? = null

        private fun readSealed(): ByteArray? {
            val size = try {
                input.readInt()
            } catch (e: EOFException) {
                return null
            }
            if (size < TAG_BITS / 8 || size > CHUNK + TAG_BITS / 8) throw BackupException(Msg.BACKUP_DAMAGED())
            return ByteArray(size).also {
                try {
                    input.readFully(it)
                } catch (e: EOFException) {
                    throw BackupException(Msg.BACKUP_INCOMPLETE())
                }
            }
        }

        /** The last chunk can only be recognised by looking one chunk ahead. */
        private fun fill(): Boolean {
            if (finished) return false
            val sealed = pending ?: readSealed() ?: throw BackupException(Msg.BACKUP_INCOMPLETE())
            val next = readSealed()
            pending = next
            val last = next == null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            plain = try {
                cipher.doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw BackupException(Msg.BACKUP_WRONG_PASSWORD())
            }
            counter++
            position = 0
            finished = last
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (position >= plain.size) {
                if (!fill()) return -1
            }
            val take = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, take)
            position += take
            return take
        }

        override fun close() = input.close()
    }
}
