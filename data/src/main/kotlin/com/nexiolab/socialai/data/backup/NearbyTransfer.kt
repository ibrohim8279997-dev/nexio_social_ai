package com.nexiolab.socialai.data.backup

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nexiolab.socialai.data.security.KeystoreKeyWrapper
import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.backup.PairingCode
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.transfer.AbortReason
import com.nexiolab.socialai.domain.transfer.DestinationLink
import com.nexiolab.socialai.domain.transfer.DestinationSession
import com.nexiolab.socialai.domain.transfer.IncomingTransfers
import com.nexiolab.socialai.domain.transfer.OutgoingTransfer
import com.nexiolab.socialai.domain.transfer.OutgoingTransfers
import com.nexiolab.socialai.domain.transfer.SourceLink
import com.nexiolab.socialai.domain.transfer.SourceSession
import com.nexiolab.socialai.domain.transfer.TransferMessage
import com.nexiolab.socialai.domain.transfer.TransferObserver
import com.nexiolab.socialai.domain.transfer.TransferOffer
import com.nexiolab.socialai.domain.transfer.TransferProgress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

sealed interface TransferEvent {
    data object Waiting : TransferEvent
    /** Both phones show these digits; the owner compares them and confirms on each. */
    data class Confirm(val digits: String) : TransferEvent
    /** [keptChunks] of [totalChunks] are already on this phone from an earlier attempt; [remainingBytes] still have to travel. */
    data class Offer(val bytes: Long, val keptChunks: Int, val totalChunks: Int, val remainingBytes: Long) : TransferEvent
    /** [done] and [total] count bytes of the whole archive, including chunks kept from earlier attempts. */
    data class Progress(
        val done: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val chunksDone: Int = 0,
        val chunksTotal: Int = 0,
    ) : TransferEvent {
        val remaining: Long get() = (total - done).coerceAtLeast(0)
    }
    data object Sent : TransferEvent
    /**
     * The assembled sealed archive, whole-archive SHA-256 already checked, and the key that opens it.
     * That is the key of the first pairing, which can differ from the current QR's after a retry. Not
     * a data class on purpose: nothing may print the key.
     */
    class Received(val file: File, val key: ByteArray) : TransferEvent
    /** [resumable]: nothing is lost; the kept chunks stay and the owner can retry from Data & Migration. */
    data class Failed(val message: String, val resumable: Boolean = false) : TransferEvent
}

/** Where unfinished transfers live: noBackupFilesDir, so they never reach a backup. */
class TransferStorage(context: Context) {
    val outgoing = OutgoingTransfers(File(context.noBackupFilesDir, "transfer-out"))
    val incoming = IncomingTransfers(File(context.noBackupFilesDir, "transfer-in"), KeystoreKeyWrapper())
}

/**
 * Direct phone-to-phone transfer over Google Nearby Connections (Bluetooth and Wi-Fi), with no server.
 * The session id in the QR code keeps strangers out of discovery, both owners compare the Nearby
 * digits, and the archive is sealed with the QR's one-time key on top of Nearby's own link encryption.
 *
 * Nearby Connections cannot resume one payload, so the sealed archive is cut into ordered chunks
 * (see ChunkManifest in :domain, 4 MiB or more). The manifest and every control message are BYTES
 * payloads; each chunk is its own STREAM payload, sent one at a time and acknowledged. The
 * destination checks each chunk's size and SHA-256, keeps verified ones on disk, and assembles the
 * archive only when all are present and the whole-archive hash matches. If the link drops, the
 * source's data is untouched and the finished chunks stay on both phones. A retry is a new pairing
 * (new QR code, both owners confirm the digits again); the destination reports the chunk hashes it
 * already holds and the source sends only the rest. A chunk that is in flight when the link drops
 * starts over, because a payload cannot continue in the middle. The chunks stay sealed with the
 * first key, which the destination keeps wrapped by the Keystore beside them; it is never re-sent.
 *
 * The protocol and its checks live in :domain (TransferSession); this class only moves bytes. Every
 * session call runs on one worker thread, because the sessions are not thread safe.
 */
class NearbyTransfer(context: Context, private val onEvent: (TransferEvent) -> Unit) {
    private val client = Nearby.getConnectionsClient(context)
    private val storage = TransferStorage(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "nexio-transfer") }
    private var endpoint: String? = null
    private var source: SourceSession? = null
    private var destination: DestinationSession? = null
    private var isSource = false
    private val chunkStreams = ConcurrentHashMap<Long, InputStream>()
    private val incomingIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile private var lastProgress: TransferProgress? = null
    @Volatile private var finished = false
    private var startedAt = 0L
    private var baseline = 0L

    private fun service(code: PairingCode) = "com.nexiolab.socialai.migration.${code.session}"

    private fun later(task: () -> Unit) {
        try {
            worker.execute {
                try {
                    task()
                } catch (e: Exception) {
                    fail(e.message ?: e.javaClass.simpleName, resumable = false)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Already stopped.
        }
    }

    private fun sendBytes(message: TransferMessage) {
        endpoint?.let { client.sendPayload(it, Payload.fromBytes(message.encode())) }
    }

    private val sourceLink = object : SourceLink {
        override fun send(message: TransferMessage) = sendBytes(message)

        /** The stream is the chunk index (4 bytes) followed by the chunk. The index travels with the data, so order never matters. */
        override fun sendChunk(index: Int, data: InputStream, size: Long) {
            val id = endpoint ?: return
            val framed = SequenceInputStream(ByteArrayInputStream(ByteBuffer.allocate(HEADER).putInt(index).array()), data)
            val payload = Payload.fromStream(framed)
            chunkStreams[payload.id] = framed
            client.sendPayload(id, payload)
        }
    }

    private val destinationLink = object : DestinationLink {
        override fun send(message: TransferMessage) = sendBytes(message)
    }

    private val observer = object : TransferObserver {
        override fun onOffer(offer: TransferOffer) {
            val manifest = offer.manifest
            onEvent(TransferEvent.Offer(manifest.totalSize, offer.keptChunks, manifest.chunks.size, offer.remainingBytes))
        }

        override fun onProgress(progress: TransferProgress) = report(progress, extra = 0, remember = true)

        override fun onSourceFinished() {
            if (finished) return
            finished = true
            onEvent(TransferEvent.Sent)
            stop()
        }

        override fun onArchiveReady(archive: File, key: ByteArray) {
            if (finished) return
            finished = true
            onEvent(TransferEvent.Received(archive, key))
            // The DONE message is already on its way; give it a moment before the link closes.
            try {
                Thread.sleep(CLOSE_DELAY_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            stop()
        }

        override fun onFailed(message: String, resumable: Boolean) = fail(message, resumable)
    }

    private fun report(progress: TransferProgress, extra: Long, remember: Boolean) {
        if (remember) lastProgress = progress
        val now = System.currentTimeMillis()
        if (startedAt == 0L) {
            startedAt = now
            baseline = progress.bytesDone
        }
        val done = (progress.bytesDone + extra).coerceAtMost(progress.bytesTotal)
        val seconds = ((now - startedAt) / 1000).coerceAtLeast(1)
        onEvent(TransferEvent.Progress(done, progress.bytesTotal, (done - baseline).coerceAtLeast(0) / seconds, progress.chunksDone, progress.chunksTotal))
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    later {
                        val message = try {
                            TransferMessage.parse(bytes)
                        } catch (e: BackupException) {
                            return@later fail(e.message ?: Msg.TRANSFER_UNEXPECTED(), resumable = false)
                        }
                        val sending = source
                        if (sending != null) sending.onMessage(message) else destination?.onMessage(message)
                    }
                }
                Payload.Type.STREAM -> {
                    val input = payload.asStream()?.asInputStream() ?: return fail(Msg.TRANSFER_NOT_ARRIVED(), resumable = false)
                    chunkStreams[payload.id] = input
                    incomingIds += payload.id
                    later { receiveChunk(payload.id, input) }
                }
                else -> fail(Msg.TRANSFER_UNEXPECTED(), resumable = false)
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            val isChunk = chunkStreams.containsKey(update.payloadId)
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> if (isChunk) {
                    lastProgress?.let { report(it, extra = (update.bytesTransferred - HEADER).coerceAtLeast(0), remember = false) }
                }
                // An incoming stream is closed by its reader once everything is read, never earlier.
                PayloadTransferUpdate.Status.SUCCESS -> if (isChunk && update.payloadId !in incomingIds) release(update.payloadId)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    release(update.payloadId)
                    linkLost()
                }
            }
        }
    }

    private fun release(payloadId: Long) {
        incomingIds -= payloadId
        chunkStreams.remove(payloadId)?.let { runCatching { it.close() } }
    }

    /** Runs on the worker. The first 4 bytes name the chunk; the domain checks the rest against the manifest. */
    private fun receiveChunk(payloadId: Long, input: InputStream) {
        try {
            val header = ByteArray(HEADER)
            var read = 0
            try {
                while (read < HEADER) {
                    val n = input.read(header, read, HEADER - read)
                    if (n < 0) return linkLost()
                    read += n
                }
            } catch (e: IOException) {
                return linkLost()
            }
            destination?.onChunk(ByteBuffer.wrap(header).int, input)
        } finally {
            release(payloadId)
        }
    }

    private fun linkLost() = later {
        if (!finished) {
            source?.onLinkLost()
            destination?.onLinkLost()
        }
    }

    /** Receiver's go-ahead after it checked it has room. */
    fun acceptOffer() = later { destination?.accept() }

    /** Receiver says no, or has no room. The kept chunks stay where they are. */
    fun decline(noSpace: Boolean) {
        later {
            destination?.decline(if (noSpace) AbortReason.NO_SPACE else AbortReason.DECLINED)
            Thread.sleep(CLOSE_DELAY_MILLIS)
            stop()
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            endpoint = id
            client.stopAdvertising()
            client.stopDiscovery()
            onEvent(TransferEvent.Confirm(info.authenticationDigits))
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                return fail(Msg.TRANSFER_CANNOT_CONNECT(), resumable = isSource)
            }
            later { source?.start() }
        }

        override fun onDisconnected(id: String) {
            if (!finished) linkLost()
        }
    }

    /** Source phone: waits for the phone that scanned the code. [transfer] is the kept, already sealed archive. */
    fun host(code: PairingCode, transfer: OutgoingTransfer, deviceName: String) {
        isSource = true
        source = SourceSession(transfer, sourceLink, observer)
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(deviceName, service(code), lifecycle, options)
            .addOnSuccessListener { onEvent(TransferEvent.Waiting) }
            .addOnFailureListener { fail(Msg.TRANSFER_START_FAILED(it.message), resumable = true) }
    }

    /** Destination phone: finds the source named in the QR code. Kept chunks are matched by the manifest, not by the code. */
    fun join(code: PairingCode, deviceName: String) {
        destination = DestinationSession(storage.incoming, code.keyBytes, destinationLink, observer)
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                client.requestConnection(deviceName, id, lifecycle)
                    .addOnFailureListener { fail(Msg.TRANSFER_REACH_FAILED(it.message), resumable = false) }
            }

            override fun onEndpointLost(id: String) = Unit
        }
        client.startDiscovery(service(code), callback, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { onEvent(TransferEvent.Waiting) }
            .addOnFailureListener { fail(Msg.TRANSFER_LOOKUP_FAILED(it.message), resumable = false) }
    }

    fun confirm() {
        endpoint?.let { client.acceptConnection(it, payloads) }
    }

    fun reject() {
        endpoint?.let { client.rejectConnection(it) }
        stop()
    }

    private fun fail(message: String, resumable: Boolean) {
        if (finished) return
        finished = true
        onEvent(TransferEvent.Failed(message, resumable))
        stop()
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        chunkStreams.keys.toList().forEach(::release)
        worker.shutdown()
    }

    private companion object {
        const val HEADER = 4
        const val CLOSE_DELAY_MILLIS = 1_500L
    }
}
