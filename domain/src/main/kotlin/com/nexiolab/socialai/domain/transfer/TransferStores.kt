package com.nexiolab.socialai.domain.transfer

import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.i18n.Msg
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import com.nexiolab.socialai.domain.Hashing.toHex

/**
 * Protects the one-time key while it sits on the destination between two attempts. Android wraps it
 * with a device-bound Keystore key; tests use [None]. [aad] ties the wrapped key to its transfer.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray

    object None : KeyWrapper {
        override fun wrap(plain: ByteArray, aad: ByteArray) = plain.copyOf()
        override fun unwrap(wrapped: ByteArray, aad: ByteArray) = wrapped.copyOf()
    }
}

private fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.path + ".tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(target)) {
        target.delete()
        if (!temp.renameTo(target)) throw IOException("Could not write ${target.name}")
    }
}

private fun folderFor(root: File, migrationId: String): File {
    // The id comes from the wire or from disk. Only a strict 32-hex name may become a path.
    if (!TransferNames.isMigrationId(migrationId)) throw BackupException(Msg.TRANSFER_PLAN_INVALID())
    return File(root, migrationId)
}

/** The sealed archive kept on the source, with its manifest, until the transfer finishes or is cancelled. */
class OutgoingTransfer internal constructor(val dir: File, val manifest: ChunkManifest) {
    val archive: File get() = File(dir, OutgoingTransfers.ARCHIVE)

    /** Re-reads the chunk from the kept archive, so damage on the source is caught before it is sent. */
    fun verifyChunk(index: Int): Boolean {
        val info = manifest.chunks[index]
        if (archive.length() != manifest.totalSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        openChunk(index).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
            return ChunkVerifier.matches(info, size, digest.digest().toHex())
        }
    }

    fun openChunk(index: Int): InputStream = RangeInputStream(archive, manifest.offsets[index], manifest.chunks[index].size)

    fun delete() {
        dir.deleteRecursively()
    }

    private class RangeInputStream(file: File, offset: Long, private var left: Long) : InputStream() {
        private val raf = RandomAccessFile(file, "r").also { it.seek(offset) }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val read = raf.read(b, off, minOf(len.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        override fun close() = raf.close()
    }
}

/** Layout on the source: <root>/<migration id>/archive.nexiobak and manifest.txt. The key is not kept here. */
class OutgoingTransfers(private val root: File) {
    /** Where the sealed archive for [migrationId] is to be written. */
    fun archiveFile(migrationId: String): File {
        val dir = folderFor(root, migrationId).apply { mkdirs() }
        return File(dir, ARCHIVE)
    }

    /** Cuts the written archive into chunks and keeps the manifest. After this the key is not needed again. */
    fun seal(migrationId: String, key: ByteArray, chunkSize: Long? = null, onBytes: (Long) -> Unit = {}): OutgoingTransfer {
        val dir = folderFor(root, migrationId)
        val archive = File(dir, ARCHIVE)
        val check = KeyCheck.of(key, migrationId)
        val manifest = if (chunkSize == null) ChunkManifest.build(migrationId, archive, check, onBytes = onBytes)
        else ChunkManifest.build(migrationId, archive, check, chunkSize, onBytes)
        writeAtomically(File(dir, MANIFEST), manifest.encode())
        return OutgoingTransfer(dir, manifest)
    }

    fun load(migrationId: String): OutgoingTransfer? {
        val dir = folderFor(root, migrationId)
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        val manifest = try {
            ChunkManifest.parse(file.readBytes())
        } catch (e: BackupException) {
            return null
        }
        if (manifest.migrationId != migrationId || File(dir, ARCHIVE).length() != manifest.totalSize) return null
        return OutgoingTransfer(dir, manifest)
    }

    /** Unfinished transfers that can still be resumed. Only strictly named folders are considered. */
    fun pending(): List<OutgoingTransfer> =
        root.listFiles { f -> f.isDirectory && TransferNames.isMigrationId(f.name) }.orEmpty().mapNotNull { load(it.name) }

    fun deleteAll() {
        root.deleteRecursively()
    }

    companion object {
        const val ARCHIVE = "archive.nexiobak"
        const val MANIFEST = "manifest.txt"
    }
}

/** What the owner sees about an unfinished receive. */
data class PendingIncoming(val migrationId: String, val totalSize: Long, val chunksKept: Int, val chunksTotal: Int)

/** Verified chunks of one transfer on the destination: chunk-<index>.part files plus the manifest and the wrapped key. */
class IncomingTransfer internal constructor(val dir: File, val manifest: ChunkManifest, internal val key: ByteArray) {
    /** The key the chunks were sealed with: the first pairing's key, even after a retry with a new code. */
    fun sealKey(): ByteArray = key.copyOf()

    /** The assembled sealed archive appears here, and only here. */
    val archive: File get() = File(dir, IncomingTransfers.ARCHIVE)

    fun chunkFile(index: Int) = File(dir, TransferNames.chunkFileName(index))

    /** Hashes every kept chunk again. A chunk that no longer matches is deleted, so it is asked for again. */
    fun verifiedChunks(): Map<Int, String> {
        val held = LinkedHashMap<Int, String>()
        for (info in manifest.chunks) {
            val file = chunkFile(info.index)
            if (ChunkVerifier.verify(info, file)) held[info.index] = info.sha256 else file.delete()
        }
        return held
    }

    /** Stores a chunk only if it matches the manifest. Otherwise nothing is kept and false is returned. */
    fun accept(index: Int, input: InputStream): Boolean {
        val info = manifest.chunks.getOrNull(index) ?: return false
        val temp = File(dir, IncomingTransfers.RECEIVING)
        val ok = try {
            ChunkVerifier.receive(info, input, temp)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        if (!ok) {
            temp.delete()
            return false
        }
        val target = chunkFile(index)
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not keep chunk $index")
        }
        return true
    }

    /** Joins the chunks. Chunks that fail here are deleted and listed so they can be requested again. */
    fun assemble(): AssemblyResult {
        val result = ChunkAssembler.assemble(manifest, ::chunkFile, archive)
        if (result is AssemblyResult.Corrupt) result.indices.forEach { chunkFile(it).delete() }
        if (result is AssemblyResult.ArchiveMismatch) deleteChunks()
        return result
    }

    fun deleteChunks() {
        manifest.chunks.forEach { chunkFile(it.index).delete() }
    }

    fun delete() {
        dir.deleteRecursively()
    }
}

/**
 * Layout on the destination: <root>/<migration id>/{manifest.txt, key.bin, chunk-N.part}. Only one
 * unfinished transfer is kept; starting a different one removes the old one. Everything here lives
 * in noBackupFilesDir on Android, so it is never backed up.
 */
class IncomingTransfers(private val root: File, private val wrapper: KeyWrapper) {
    /**
     * Resumes the kept transfer when the offered manifest is identical, using the key stored with the
     * chunks. Otherwise the offer must be sealed with [pairingKey] (checked without revealing it), and
     * any older kept transfer is removed. Because the stored key is reused, a new QR code on a retry
     * only has to find the right phone; the data stays sealed with the first key.
     */
    fun open(manifest: ChunkManifest, pairingKey: ByteArray): IncomingTransfer {
        val dir = folderFor(root, manifest.migrationId)
        kept(dir)?.takeIf { it.manifest == manifest }?.let { return it }
        if (!KeyCheck.matches(pairingKey, manifest.migrationId, manifest.keyCheck)) throw BackupException(Msg.TRANSFER_RESTART_NEEDED())
        deleteAll()
        dir.mkdirs()
        // The manifest goes last: a folder without it is not a transfer.
        writeAtomically(File(dir, KEY), wrapper.wrap(pairingKey, manifest.migrationId.toByteArray()))
        writeAtomically(File(dir, MANIFEST), manifest.encode())
        return IncomingTransfer(dir, manifest, pairingKey.copyOf())
    }

    private fun kept(dir: File): IncomingTransfer? {
        val manifestFile = File(dir, MANIFEST)
        val keyFile = File(dir, KEY)
        if (!manifestFile.isFile || !keyFile.isFile) return null
        return try {
            val manifest = ChunkManifest.parse(manifestFile.readBytes())
            if (manifest.migrationId != dir.name) return null
            val key = wrapper.unwrap(keyFile.readBytes(), manifest.migrationId.toByteArray())
            if (!KeyCheck.matches(key, manifest.migrationId, manifest.keyCheck)) null else IncomingTransfer(dir, manifest, key)
        } catch (e: Exception) {
            null
        }
    }

    fun pending(): List<PendingIncoming> =
        root.listFiles { f -> f.isDirectory && TransferNames.isMigrationId(f.name) }.orEmpty().mapNotNull { dir ->
            val transfer = kept(dir) ?: return@mapNotNull null
            val manifest = transfer.manifest
            val held = manifest.chunks.count { transfer.chunkFile(it.index).let { f -> f.isFile && f.length() == it.size } }
            PendingIncoming(manifest.migrationId, manifest.totalSize, held, manifest.chunks.size)
        }

    /** The owner cancelled, or the data was delivered: remove every kept chunk and key. */
    fun deleteAll() {
        root.deleteRecursively()
    }

    companion object {
        const val ARCHIVE = "archive.nexiobak"
        const val MANIFEST = "manifest.txt"
        const val KEY = "key.bin"
        const val RECEIVING = "receiving.tmp"
    }
}
