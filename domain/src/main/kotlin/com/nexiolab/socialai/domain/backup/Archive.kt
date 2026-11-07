package com.nexiolab.socialai.domain.backup

import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.Hashing.toHex
import com.nexiolab.socialai.domain.i18n.Msg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ManifestFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val files: List<ManifestFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Names from another device are untrusted. Only plain relative paths under known folders pass. */
object PathValidator {
    private val allowedRoots = setOf("db", "media")
    private val segment = Regex("[A-Za-z0-9._-]+")

    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.length > 512) return false
        val parts = path.split('/')
        if (parts.size < 2 || parts.first() !in allowedRoots) return false
        return parts.all { it != "." && it != ".." && segment.matches(it) }
    }

    /** Second line of defence: the resolved file must still sit inside [root]. */
    fun resolve(root: File, path: String): File {
        if (!isSafe(path)) throw BackupException(Msg.ARCHIVE_UNSAFE_NAME())
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator)) {
            throw BackupException(Msg.ARCHIVE_UNSAFE_NAME())
        }
        return target
    }
}

object ArchiveWriter {
    private const val MANIFEST = "manifest.json"
    private val json = Json { encodeDefaults = true }

    fun manifest(root: File, paths: List<String>, schemaVersion: Int, appVersion: String, now: Long): BackupManifest =
        BackupManifest(
            schemaVersion = schemaVersion,
            appVersion = appVersion,
            createdAt = now,
            files = paths.map { path ->
                val file = PathValidator.resolve(root, path)
                ManifestFile(path, file.length(), file.inputStream().use(Hashing::sha256))
            },
        )

    /** Writes the manifest first so a reader can reject an archive before unpacking anything. */
    fun write(output: OutputStream, root: File, manifest: BackupManifest, onBytes: (Long) -> Unit = {}) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            var done = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            for (entry in manifest.files) {
                zip.putNextEntry(ZipEntry(entry.path))
                PathValidator.resolve(root, entry.path).inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        done += read
                        onBytes(done)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    const val MANIFEST_NAME = MANIFEST
}

object ArchiveReader {
    private const val MAX_MANIFEST = 16 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Unpacks into [staging] only. It refuses unexpected, duplicate or oversized entries, stops
     * writing at the declared size (so a compressed bomb cannot fill the disk) and verifies every
     * SHA-256. Nothing outside [staging] is touched.
     */
    fun extract(input: InputStream, staging: File, freeBytes: Long, onBytes: (Long, Long) -> Unit = { _, _ -> }): BackupManifest {
        ZipInputStream(input).use { zip ->
            val first = zip.nextEntry
            if (first?.name != ArchiveWriter.MANIFEST_NAME) throw BackupException(Msg.ARCHIVE_NO_MANIFEST())
            val manifest = readManifest(zip)
            if (manifest.formatVersion > BackupManifest.FORMAT_VERSION) throw BackupException(Msg.BACKUP_NEWER_VERSION())
            if (manifest.files.map { it.path }.toSet().size != manifest.files.size) throw BackupException(Msg.ARCHIVE_DUPLICATE_FILE())
            manifest.files.forEach { if (!PathValidator.isSafe(it.path) || it.size < 0) throw BackupException(Msg.ARCHIVE_UNSAFE_NAME()) }
            if (manifest.totalBytes > freeBytes) throw BackupException(Msg.NOT_ENOUGH_STORAGE())
            val expected = manifest.files.associateBy { it.path }.toMutableMap()
            val total = manifest.totalBytes
            var done = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val declared = expected.remove(entry.name) ?: throw BackupException(Msg.ARCHIVE_UNEXPECTED_FILE())
                val target = PathValidator.resolve(staging, entry.name)
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > declared.size) throw BackupException(Msg.ARCHIVE_FILE_TOO_LARGE())
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        onBytes(done, total)
                    }
                }
                if (written != declared.size || digest.digest().toHex() != declared.sha256) {
                    throw BackupException(Msg.ARCHIVE_INTEGRITY_FAILED(declared.path))
                }
            }
            if (expected.isNotEmpty()) throw BackupException(Msg.ARCHIVE_FILES_MISSING(expected.size))
            return manifest
        }
    }

    private fun readManifest(input: InputStream): BackupManifest {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes.write(buffer, 0, read)
            if (bytes.size() > MAX_MANIFEST) throw BackupException(Msg.MANIFEST_TOO_LARGE())
        }
        return try {
            json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8.name()))
        } catch (e: IllegalArgumentException) {
            throw BackupException(Msg.MANIFEST_UNREADABLE())
        }
    }
}

/**
 * What the transfer QR code holds: a session name, one random key and an expiry. It carries no API
 * key, token, password or user data; the key only opens this one transfer and dies with it.
 */
@Serializable
data class PairingCode(val version: Int = 1, val session: String, val key: String, val expiresAt: Long) {
    val keyBytes: ByteArray get() = Base64.getDecoder().decode(key)

    fun encode(): String = PREFIX + Json.encodeToString(serializer(), this)

    companion object {
        private const val PREFIX = "NEXIOPAIR:"
        private const val KEY_BYTES = 32
        private const val SESSION_BYTES = 8
        const val LIFETIME_MILLIS = 10 * 60 * 1000L

        fun create(now: Long, random: SecureRandom = SecureRandom()): PairingCode = PairingCode(
            session = ByteArray(SESSION_BYTES).also(random::nextBytes).toHex(),
            key = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes)),
            expiresAt = now + LIFETIME_MILLIS,
        )

        fun parse(text: String, now: Long): PairingCode {
            if (!text.startsWith(PREFIX)) throw BackupException(Msg.NOT_TRANSFER_CODE())
            val code = try {
                Json.decodeFromString(serializer(), text.removePrefix(PREFIX))
            } catch (e: IllegalArgumentException) {
                throw BackupException(Msg.TRANSFER_CODE_UNREADABLE())
            }
            if (code.version != 1 || !code.session.matches(Regex("[0-9a-f]{16}"))) throw BackupException(Msg.TRANSFER_CODE_UNREADABLE())
            if (runCatching { code.keyBytes.size }.getOrNull() != KEY_BYTES) throw BackupException(Msg.TRANSFER_CODE_UNREADABLE())
            if (now >= code.expiresAt) throw BackupException(Msg.TRANSFER_CODE_EXPIRED())
            return code
        }
    }
}
