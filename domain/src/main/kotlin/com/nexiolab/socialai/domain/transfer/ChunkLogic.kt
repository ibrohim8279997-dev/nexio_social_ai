package com.nexiolab.socialai.domain.transfer

import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.Hashing.toHex
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Where a transfer stands, counted in chunks and bytes. */
data class TransferProgress(val chunksDone: Int, val chunksTotal: Int, val bytesDone: Long, val bytesTotal: Long) {
    val bytesLeft: Long get() = (bytesTotal - bytesDone).coerceAtLeast(0)
}

/** Decides what still has to travel. A held chunk only counts when its hash equals the manifest's. */
object ChunkPlanner {
    fun missing(manifest: ChunkManifest, held: Map<Int, String>): List<Int> =
        manifest.chunks.filter { held[it.index] != it.sha256 }.map { it.index }

    fun progress(manifest: ChunkManifest, held: Map<Int, String>): TransferProgress {
        val have = manifest.chunks.filter { held[it.index] == it.sha256 }
        return TransferProgress(have.size, manifest.chunks.size, have.sumOf { it.size }, manifest.totalSize)
    }
}

/** Accepts a chunk only if its size and SHA-256 are exactly what the manifest promised. */
object ChunkVerifier {
    fun matches(info: ChunkInfo, size: Long, sha256: String) = size == info.size && sha256 == info.sha256

    fun verify(info: ChunkInfo, bytes: ByteArray) = matches(info, bytes.size.toLong(), Hashing.sha256(bytes))

    fun verify(info: ChunkInfo, file: File): Boolean =
        file.isFile && file.length() == info.size && file.inputStream().use(Hashing::sha256) == info.sha256

    /**
     * Copies at most size + 1 bytes of [input] into [target]; the extra byte exposes an oversized chunk
     * without letting it fill the disk. A read error counts as a short chunk. Write errors propagate.
     */
    fun receive(info: ChunkInfo, input: InputStream, target: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val limit = info.size + 1
            while (written < limit) {
                val read = try {
                    input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                } catch (e: IOException) {
                    -1
                }
                if (read < 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
            }
        }
        return matches(info, written, digest.digest().toHex())
    }
}

sealed interface AssemblyResult {
    data class Assembled(val file: File) : AssemblyResult
    data class Missing(val indices: List<Int>) : AssemblyResult
    data class Corrupt(val indices: List<Int>) : AssemblyResult

    /** Every chunk matched yet the whole did not: the manifest contradicts itself. */
    data object ArchiveMismatch : AssemblyResult
}

/** Joins chunks into the sealed archive, refusing unless the order, sizes and final hash all agree. */
object ChunkAssembler {
    fun assemble(manifest: ChunkManifest, chunkFile: (Int) -> File, target: File): AssemblyResult {
        val absent = manifest.chunks.filter { chunkFile(it.index).let { f -> !f.isFile || f.length() != it.size } }.map { it.index }
        if (absent.isNotEmpty()) return AssemblyResult.Missing(absent)
        val whole = MessageDigest.getInstance("SHA-256")
        val corrupt = ArrayList<Int>()
        var total = 0L
        try {
            target.outputStream().use { out ->
                for (info in manifest.chunks) {
                    if (!copyVerified(info, chunkFile(info.index), out, whole)) corrupt += info.index
                    total += info.size
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        if (corrupt.isNotEmpty()) {
            target.delete()
            return AssemblyResult.Corrupt(corrupt)
        }
        if (total != manifest.totalSize || target.length() != manifest.totalSize || whole.digest().toHex() != manifest.archiveSha256) {
            target.delete()
            return AssemblyResult.ArchiveMismatch
        }
        return AssemblyResult.Assembled(target)
    }

    private fun copyVerified(info: ChunkInfo, file: File, out: OutputStream, whole: MessageDigest): Boolean {
        val part = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                part.update(buffer, 0, read)
                whole.update(buffer, 0, read)
            }
        }
        return part.digest().toHex() == info.sha256
    }
}
