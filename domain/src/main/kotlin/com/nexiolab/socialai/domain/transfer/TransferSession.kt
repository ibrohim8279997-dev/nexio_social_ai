package com.nexiolab.socialai.domain.transfer

import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.i18n.Msg
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Why one phone stopped the transfer, as a fixed code so the other phone can show its own words. */
enum class AbortReason(val message: () -> String) {
    DECLINED({ Msg.TRANSFER_DECLINED() }),
    NO_SPACE({ Msg.TRANSFER_PEER_NO_SPACE() }),
    RESTART_NEEDED({ Msg.TRANSFER_RESTART_NEEDED() }),
    DAMAGED({ Msg.TRANSFER_INTEGRITY_FAILED() }),
    FAILED({ Msg.TRANSFER_UNEXPECTED() }),
}

/**
 * Control messages, each one a small ASCII text payload (well under Nearby's 32 KiB BYTES limit).
 * Source to destination: [Manifest], [Abort]. Destination to source: [Have], [Ack], [Nak], [Want], [Done], [Abort].
 * Chunk data travels as separate STREAM payloads, never inside these messages.
 */
sealed interface TransferMessage {
    fun encode(): ByteArray

    data class Manifest(val manifest: ChunkManifest) : TransferMessage {
        override fun encode() = manifest.encode()
    }

    /** Sent once the owner accepted: the verified chunks (index and hash) already kept from earlier attempts. */
    data class Have(val migrationId: String, val chunks: Map<Int, String>) : TransferMessage {
        override fun encode() = buildString {
            append("HAVE ").append(migrationId).append('\n')
            chunks.toSortedMap().forEach { (index, sha) -> append(index).append(' ').append(sha).append('\n') }
        }.toByteArray(Charsets.US_ASCII)
    }

    data class Ack(val migrationId: String, val index: Int) : TransferMessage {
        override fun encode() = "ACK $migrationId $index".toByteArray(Charsets.US_ASCII)
    }

    /** The chunk failed its size or checksum, was discarded and must be sent again. */
    data class Nak(val migrationId: String, val index: Int) : TransferMessage {
        override fun encode() = "NAK $migrationId $index".toByteArray(Charsets.US_ASCII)
    }

    /** Chunks that went bad after they were acknowledged (found while assembling). */
    data class Want(val migrationId: String, val indices: List<Int>) : TransferMessage {
        override fun encode() = "WANT $migrationId ${indices.joinToString(",")}".toByteArray(Charsets.US_ASCII)
    }

    /** The whole archive was assembled and its SHA-256 matched. */
    data class Done(val migrationId: String) : TransferMessage {
        override fun encode() = "DONE $migrationId".toByteArray(Charsets.US_ASCII)
    }

    data class Abort(val migrationId: String, val reason: AbortReason) : TransferMessage {
        override fun encode() = "ABORT $migrationId ${reason.name}".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        const val MAX_BYTES = 30 * 1024

        fun parse(bytes: ByteArray): TransferMessage {
            fun bad(): Nothing = throw BackupException(Msg.TRANSFER_UNEXPECTED())
            if (bytes.size > MAX_BYTES) bad()
            if (bytes.startsWith(ChunkManifest.HEADER)) return Manifest(ChunkManifest.parse(bytes))
            val text = String(bytes, Charsets.US_ASCII)
            if (text.any { it != '\n' && (it < ' ' || it > '~') }) bad()
            val lines = text.split('\n')
            val head = lines[0].split(' ')
            val id = head.getOrNull(1)?.takeIf(TransferNames::isMigrationId) ?: bad()
            fun index(value: String?): Int =
                value?.takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toInt()?.takeIf { it < ChunkManifest.MAX_CHUNKS } ?: bad()
            return when (head[0]) {
                "HAVE" -> {
                    if (head.size != 2 || lines.last().isNotEmpty()) bad()
                    val chunks = LinkedHashMap<Int, String>()
                    for (line in lines.drop(1).dropLast(1)) {
                        val parts = line.split(' ')
                        if (parts.size != 2 || !TransferNames.isSha256(parts[1])) bad()
                        if (chunks.put(index(parts[0]), parts[1]) != null) bad()
                    }
                    Have(id, chunks)
                }
                "ACK" -> if (head.size == 3 && lines.size == 1) Ack(id, index(head[2])) else bad()
                "NAK" -> if (head.size == 3 && lines.size == 1) Nak(id, index(head[2])) else bad()
                "WANT" -> if (head.size == 3 && lines.size == 1) Want(id, head[2].split(',').map { index(it) }.distinct()) else bad()
                "DONE" -> if (head.size == 2 && lines.size == 1) Done(id) else bad()
                "ABORT" -> if (head.size == 3 && lines.size == 1) Abort(id, AbortReason.entries.firstOrNull { it.name == head[2] } ?: bad()) else bad()
                else -> bad()
            }
        }

        private fun ByteArray.startsWith(prefix: String): Boolean =
            size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }
    }
}

/** What the source can do on the wire. [sendChunk] streams one chunk's bytes (the adapter adds framing). */
interface SourceLink {
    fun send(message: TransferMessage)
    fun sendChunk(index: Int, data: InputStream, size: Long)
}

interface DestinationLink {
    fun send(message: TransferMessage)
}

/** What the destination owner is asked about: the incoming data and how much of it is already here. */
data class TransferOffer(val manifest: ChunkManifest, val keptChunks: Int, val remainingBytes: Long)

/** Callbacks with no-op defaults. All are called on the thread that drives the session. */
interface TransferObserver {
    fun onOffer(offer: TransferOffer) {}
    fun onProgress(progress: TransferProgress) {}

    /** Source: the destination confirmed the whole archive. */
    fun onSourceFinished() {}

    /** Destination: the sealed archive is assembled and its SHA-256 matched. */
    fun onArchiveReady(archive: File, key: ByteArray) {}

    /** [resumable]: the kept chunks are still good, so the owner can retry from Data & Migration. */
    fun onFailed(message: String, resumable: Boolean) {}
}

/**
 * The source's half of the protocol, one chunk at a time. It sends only the chunks the destination
 * lacks, resends a chunk the destination refused, and stops after [MAX_ATTEMPTS] refusals of one chunk.
 * No threads here: the adapter feeds it messages one by one.
 */
class SourceSession(
    private val transfer: OutgoingTransfer,
    private val link: SourceLink,
    private val observer: TransferObserver,
) {
    private val manifest = transfer.manifest
    private val held = HashSet<Int>()
    private val queue = ArrayDeque<Int>()
    private val attempts = IntArray(manifest.chunks.size)
    private var inFlight: Int? = null
    private var state = State.NEW

    private enum class State { NEW, MANIFEST_SENT, SENDING, ENDED }

    fun start() {
        check(state == State.NEW) { "already started" }
        link.send(TransferMessage.Manifest(manifest))
        state = State.MANIFEST_SENT
    }

    fun progress(inFlightBytes: Long = 0): TransferProgress {
        val done = held.sumOf { manifest.chunks[it].size }
        return TransferProgress(held.size, manifest.chunks.size, (done + inFlightBytes).coerceAtMost(manifest.totalSize), manifest.totalSize)
    }

    fun onMessage(message: TransferMessage) {
        if (state == State.ENDED) return
        try {
            handle(message)
        } catch (e: IOException) {
            end(Msg.TRANSFER_SOURCE_DAMAGED(), resumable = false)
        }
    }

    private fun handle(message: TransferMessage) {
        when (message) {
            is TransferMessage.Have -> if (state == State.MANIFEST_SENT && message.migrationId == manifest.migrationId) {
                val missing = ChunkPlanner.missing(manifest, message.chunks)
                held.clear()
                held += manifest.chunks.indices - missing.toSet()
                queue.clear()
                queue += missing
                state = State.SENDING
                observer.onProgress(progress())
                sendNext()
            }
            is TransferMessage.Ack -> if (state == State.SENDING && message.migrationId == manifest.migrationId && message.index == inFlight) {
                held += message.index
                inFlight = null
                observer.onProgress(progress())
                sendNext()
            }
            is TransferMessage.Nak -> if (state == State.SENDING && message.migrationId == manifest.migrationId && message.index == inFlight) {
                if (++attempts[message.index] >= MAX_ATTEMPTS) return end(Msg.TRANSFER_PART_FAILED(), resumable = false)
                inFlight = null
                queue.addFirst(message.index)
                sendNext()
            }
            is TransferMessage.Want -> if (state == State.SENDING && message.migrationId == manifest.migrationId) {
                for (index in message.indices) {
                    held -= index
                    if (index != inFlight && index !in queue) queue += index
                }
                sendNext()
            }
            is TransferMessage.Done -> if (state == State.SENDING && message.migrationId == manifest.migrationId) {
                state = State.ENDED
                observer.onSourceFinished()
            }
            is TransferMessage.Abort -> if (message.migrationId == manifest.migrationId) {
                end(message.reason.message(), resumable = message.reason == AbortReason.DECLINED || message.reason == AbortReason.NO_SPACE)
            }
            is TransferMessage.Manifest -> end(Msg.TRANSFER_UNEXPECTED(), resumable = false)
        }
    }

    private fun sendNext() {
        if (inFlight != null) return
        val index = queue.removeFirstOrNull() ?: return
        if (!transfer.verifyChunk(index)) return end(Msg.TRANSFER_SOURCE_DAMAGED(), resumable = false)
        inFlight = index
        link.sendChunk(index, transfer.openChunk(index), manifest.chunks[index].size)
    }

    /** The link went away. Nothing changed on disk, so the transfer can be retried. */
    fun onLinkLost() = end(Msg.TRANSFER_INTERRUPTED_RESUME(), resumable = true)

    private fun end(message: String, resumable: Boolean) {
        if (state == State.ENDED) return
        state = State.ENDED
        observer.onFailed(message, resumable)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * The destination's half. It keeps every verified chunk on disk, reports what it already has, checks
 * each arriving chunk against the manifest, and assembles the archive only when every chunk is there.
 */
class DestinationSession(
    private val incoming: IncomingTransfers,
    private val pairingKey: ByteArray,
    private val link: DestinationLink,
    private val observer: TransferObserver,
) {
    private var transfer: IncomingTransfer? = null
    private val held = HashMap<Int, String>()
    private var state = State.WAITING

    private enum class State { WAITING, OFFERED, RECEIVING, ENDED }

    private val manifest get() = transfer!!.manifest

    fun progress(inFlightBytes: Long = 0): TransferProgress {
        val base = ChunkPlanner.progress(manifest, held)
        return base.copy(bytesDone = (base.bytesDone + inFlightBytes).coerceAtMost(base.bytesTotal))
    }

    fun onMessage(message: TransferMessage) {
        if (state == State.ENDED) return
        try {
            handle(message)
        } catch (e: BackupException) {
            end(e.message ?: Msg.TRANSFER_UNEXPECTED(), resumable = false)
        } catch (e: IOException) {
            end(Msg.TRANSFER_SAVE_FAILED(e.message ?: e.javaClass.simpleName), resumable = true)
        }
    }

    private fun handle(message: TransferMessage) {
        when (message) {
            is TransferMessage.Manifest -> {
                if (state != State.WAITING) return end(Msg.TRANSFER_UNEXPECTED(), resumable = false)
                val opened = try {
                    incoming.open(message.manifest, pairingKey)
                } catch (e: BackupException) {
                    link.send(TransferMessage.Abort(message.manifest.migrationId, AbortReason.RESTART_NEEDED))
                    throw e
                }
                transfer = opened
                held.clear()
                held += opened.verifiedChunks()
                state = State.OFFERED
                val missing = ChunkPlanner.missing(opened.manifest, held)
                observer.onOffer(TransferOffer(opened.manifest, held.size, missing.sumOf { opened.manifest.chunks[it].size }))
            }
            is TransferMessage.Abort -> if (transfer == null || message.migrationId == manifest.migrationId) {
                end(message.reason.message(), resumable = transfer != null)
            }
            else -> if (state != State.RECEIVING) end(Msg.TRANSFER_UNEXPECTED(), resumable = false)
        }
    }

    /** The owner accepted the offer (and there is room). Tells the source which chunks are already here. */
    fun accept() {
        if (state != State.OFFERED) return
        state = State.RECEIVING
        link.send(TransferMessage.Have(manifest.migrationId, HashMap(held)))
        observer.onProgress(progress())
        if (ChunkPlanner.missing(manifest, held).isEmpty()) assemble()
    }

    /** The owner said no, or there is no room. The kept chunks stay. */
    fun decline(reason: AbortReason) {
        val id = transfer?.manifest?.migrationId ?: return
        if (state == State.ENDED) return
        link.send(TransferMessage.Abort(id, reason))
        state = State.ENDED
    }

    /** One chunk arrived. [data] is read here, at most chunk size + 1 bytes of it. */
    fun onChunk(index: Int, data: InputStream) {
        if (state != State.RECEIVING) return end(Msg.TRANSFER_UNEXPECTED(), resumable = false)
        val current = transfer ?: return
        if (index !in manifest.chunks.indices) return end(Msg.TRANSFER_UNEXPECTED(), resumable = false)
        val id = manifest.migrationId
        val ok = try {
            current.accept(index, data)
        } catch (e: IOException) {
            return end(Msg.TRANSFER_SAVE_FAILED(e.message ?: e.javaClass.simpleName), resumable = true)
        }
        if (!ok) {
            held.remove(index)
            link.send(TransferMessage.Nak(id, index))
            return
        }
        held[index] = manifest.chunks[index].sha256
        observer.onProgress(progress())
        link.send(TransferMessage.Ack(id, index))
        if (ChunkPlanner.missing(manifest, held).isEmpty()) assemble()
    }

    private fun assemble() {
        val current = transfer ?: return
        val id = manifest.migrationId
        try {
            when (val result = current.assemble()) {
                is AssemblyResult.Assembled -> {
                    link.send(TransferMessage.Done(id))
                    current.deleteChunks()
                    state = State.ENDED
                    observer.onArchiveReady(result.file, current.sealKey())
                }
                is AssemblyResult.Missing -> request(result.indices)
                is AssemblyResult.Corrupt -> request(result.indices)
                AssemblyResult.ArchiveMismatch -> {
                    link.send(TransferMessage.Abort(id, AbortReason.DAMAGED))
                    end(Msg.TRANSFER_INTEGRITY_FAILED(), resumable = false)
                }
            }
        } catch (e: IOException) {
            end(Msg.TRANSFER_SAVE_FAILED(e.message ?: e.javaClass.simpleName), resumable = true)
        }
    }

    private fun request(indices: List<Int>) {
        held.keys.removeAll(indices.toSet())
        observer.onProgress(progress())
        link.send(TransferMessage.Want(manifest.migrationId, indices))
    }

    fun onLinkLost() = end(Msg.TRANSFER_INTERRUPTED_RESUME(), resumable = transfer != null)

    private fun end(message: String, resumable: Boolean) {
        if (state == State.ENDED) return
        state = State.ENDED
        observer.onFailed(message, resumable)
    }
}
