package com.nexiolab.socialai.domain.transfer

import com.nexiolab.socialai.domain.Hashing.toHex
import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.i18n.Msg
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One ordered slice of the sealed archive. [sha256] is lowercase hex. */
data class ChunkInfo(val index: Int, val size: Long, val sha256: String)

/**
 * Strict patterns for every name that comes from another phone. A name that does not match is never
 * used to build a path, so nothing can point outside the transfer folder.
 */
object TransferNames {
    private val migrationId = Regex("[0-9a-f]{32}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val chunkFile = Regex("chunk-([0-9]{1,5})\\.part")

    fun isMigrationId(value: String) = migrationId.matches(value)

    fun isSha256(value: String) = sha256.matches(value)

    fun newMigrationId(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).toHex()

    fun chunkFileName(index: Int): String {
        require(index in 0 until ChunkManifest.MAX_CHUNKS) { "chunk index out of range" }
        return "chunk-$index.part"
    }

    /** The index a kept chunk file name stands for, or null for any other name. */
    fun parseChunkFileName(name: String): Int? =
        chunkFile.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it < ChunkManifest.MAX_CHUNKS && chunkFileName(it) == name }
}

/**
 * Proves that two phones hold the same one-time key without sending it: an HMAC of the migration id.
 * It lets a destination notice at once that kept chunks were sealed with a different key.
 */
object KeyCheck {
    fun of(key: ByteArray, migrationId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("nexio-transfer-v1|$migrationId".toByteArray()).toHex()
    }

    fun matches(key: ByteArray, migrationId: String, check: String): Boolean =
        MessageDigest.isEqual(of(key, migrationId).toByteArray(), check.toByteArray())
}

/**
 * The plan for one transfer: the sealed archive cut into ordered chunks. Every instance is valid,
 * because the checks run in the constructor, whether it was built locally or parsed from the wire.
 * Chunks are laid out back to back, so the sizes alone give each chunk's offset.
 */
data class ChunkManifest(
    val migrationId: String,
    val totalSize: Long,
    val archiveSha256: String,
    val keyCheck: String,
    val chunks: List<ChunkInfo>,
) {
    init {
        fun bad(): Nothing = throw BackupException(Msg.TRANSFER_PLAN_INVALID())
        if (!TransferNames.isMigrationId(migrationId) || !TransferNames.isSha256(archiveSha256) || !TransferNames.isSha256(keyCheck)) bad()
        if (totalSize < 1 || chunks.isEmpty() || chunks.size > MAX_CHUNKS) bad()
        var sum = 0L
        chunks.forEachIndexed { position, chunk ->
            if (chunk.index != position || chunk.size !in 1..MAX_CHUNK_BYTES || !TransferNames.isSha256(chunk.sha256)) bad()
            sum += chunk.size
        }
        if (sum != totalSize) bad()
    }

    val offsets: LongArray by lazy(LazyThreadSafetyMode.NONE) {
        LongArray(chunks.size).also { out ->
            var at = 0L
            chunks.forEachIndexed { i, chunk -> out[i] = at; at += chunk.size }
        }
    }

    /** Text form that fits one Nearby BYTES payload (32 KiB) for up to [MAX_CHUNKS] chunks. */
    fun encode(): ByteArray {
        val text = buildString {
            append(HEADER).append('\n')
            append("id ").append(migrationId).append('\n')
            append("total ").append(totalSize).append('\n')
            append("sha256 ").append(archiveSha256).append('\n')
            append("keycheck ").append(keyCheck).append('\n')
            append("chunks ").append(chunks.size).append('\n')
            chunks.forEach { append(it.index).append(' ').append(it.size).append(' ').append(it.sha256).append('\n') }
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    companion object {
        const val HEADER = "NEXIO-TRANSFER/1"
        const val DEFAULT_CHUNK_BYTES = 4L * 1024 * 1024
        const val MAX_CHUNKS = 256
        const val MAX_CHUNK_BYTES = 512L * 1024 * 1024
        const val MAX_WIRE_BYTES = 30 * 1024
        private const val MIB = 1024L * 1024

        /** 4 MiB normally; larger archives get larger chunks so the plan stays under [MAX_CHUNKS]. */
        fun chunkSizeFor(total: Long): Long {
            val needed = (total + MAX_CHUNKS - 1) / MAX_CHUNKS
            val rounded = (needed + MIB - 1) / MIB * MIB
            return maxOf(DEFAULT_CHUNK_BYTES, rounded)
        }

        /** Reads the whole archive once: per-chunk hashes and the whole-archive hash come from the same pass. */
        fun build(
            migrationId: String,
            archive: File,
            keyCheck: String,
            chunkSize: Long = chunkSizeFor(archive.length()),
            onBytes: (Long) -> Unit = {},
        ): ChunkManifest {
            require(chunkSize > 0) { "chunk size must be positive" }
            val whole = MessageDigest.getInstance("SHA-256")
            val chunks = ArrayList<ChunkInfo>()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var done = 0L
            archive.inputStream().use { input ->
                while (true) {
                    val part = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    while (size < chunkSize) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), chunkSize - size).toInt())
                        if (read < 0) break
                        part.update(buffer, 0, read)
                        whole.update(buffer, 0, read)
                        size += read
                        done += read
                        onBytes(done)
                    }
                    if (size == 0L) break
                    chunks += ChunkInfo(chunks.size, size, part.digest().toHex())
                }
            }
            return ChunkManifest(migrationId, done, whole.digest().toHex(), keyCheck, chunks)
        }

        /** Every field is checked; anything unexpected is rejected rather than guessed at. */
        fun parse(bytes: ByteArray): ChunkManifest {
            fun bad(): Nothing = throw BackupException(Msg.TRANSFER_PLAN_INVALID())
            if (bytes.size > MAX_WIRE_BYTES) bad()
            val text = String(bytes, Charsets.US_ASCII)
            if (text.any { it != '\n' && (it < ' ' || it > '~') }) bad()
            val lines = text.split('\n')
            if (lines.size < 7 || lines.last().isNotEmpty() || lines[0] != HEADER) bad()
            fun field(line: String, name: String): String {
                if (!line.startsWith("$name ")) bad()
                return line.removePrefix("$name ")
            }
            fun number(value: String): Long = if (value.matches(Regex("[0-9]{1,15}"))) value.toLong() else bad()
            val count = number(field(lines[5], "chunks"))
            if (count < 1 || count > MAX_CHUNKS || lines.size != 7 + count.toInt()) bad()
            val chunks = (0 until count.toInt()).map { i ->
                val parts = lines[6 + i].split(' ')
                if (parts.size != 3) bad()
                ChunkInfo(number(parts[0]).let { if (it > Int.MAX_VALUE) bad() else it.toInt() }, number(parts[1]), parts[2])
            }
            return ChunkManifest(
                migrationId = field(lines[1], "id"),
                totalSize = number(field(lines[2], "total")),
                archiveSha256 = field(lines[3], "sha256"),
                keyCheck = field(lines[4], "keycheck"),
                chunks = chunks,
            )
        }
    }
}
