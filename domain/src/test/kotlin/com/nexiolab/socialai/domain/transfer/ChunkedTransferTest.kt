package com.nexiolab.socialai.domain.transfer

import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.Hashing.toHex
import com.nexiolab.socialai.domain.backup.BackupCrypto
import com.nexiolab.socialai.domain.backup.BackupException
import com.nexiolab.socialai.domain.backup.BackupSecret
import com.nexiolab.socialai.domain.backup.PairingCode
import com.nexiolab.socialai.domain.i18n.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.Random

class ChunkedTransferTest {
    @get:Rule val temp = TemporaryFolder()

    private val id = "0123456789abcdef0123456789abcdef"
    private val firstKey = ByteArray(32) { 1 }
    private val secondKey = ByteArray(32) { 2 }
    private val chunkSize = 1000L

    private fun bytes(size: Int) = ByteArray(size).also(Random(11)::nextBytes)

    /** A source with a real sealed archive: 4,500 plain bytes make five chunks (the last is short). */
    private class Source(val store: OutgoingTransfers, val transfer: OutgoingTransfer, val archive: ByteArray)

    private fun source(root: File = File(temp.root, "out"), key: ByteArray = firstKey, plain: Int = 4_500): Source {
        val store = OutgoingTransfers(root)
        val sealed = ByteArrayOutputStream().also { out ->
            BackupCrypto.encrypting(out, BackupSecret.RawKey(key), 1).use { it.write(bytes(plain)) }
        }.toByteArray()
        store.archiveFile(id).writeBytes(sealed)
        return Source(store, store.seal(id, key, chunkSize), sealed)
    }

    private fun manifestOf(src: Source) = src.transfer.manifest

    private fun incoming(root: File = File(temp.root, "in")) = IncomingTransfers(root, KeyWrapper.None)

    private fun chunkBytes(src: Source, index: Int): ByteArray {
        val info = manifestOf(src).chunks[index]
        val from = manifestOf(src).offsets[index].toInt()
        return src.archive.copyOfRange(from, from + info.size.toInt())
    }

    // ---- manifest ---------------------------------------------------------------------------

    @Test fun goodManifestBuildsRoundTripsAndPassesValidation() {
        val src = source()
        val manifest = manifestOf(src)
        assertEquals(id, manifest.migrationId)
        assertEquals(src.archive.size.toLong(), manifest.totalSize)
        assertEquals(Hashing.sha256(src.archive), manifest.archiveSha256)
        assertEquals((src.archive.size + 999) / 1000, manifest.chunks.size)
        assertEquals(listOf(0, 1, 2, 3, 4), manifest.chunks.map { it.index })
        assertEquals(manifest.totalSize, manifest.chunks.sumOf { it.size })
        manifest.chunks.forEach { assertEquals(Hashing.sha256(chunkBytes(src, it.index)), it.sha256) }
        assertEquals(manifest, ChunkManifest.parse(manifest.encode()))
        assertEquals(manifest, src.store.load(id)?.manifest)
        assertTrue(manifest.encode().size < ChunkManifest.MAX_WIRE_BYTES)
    }

    @Test fun tamperedManifestIsRejected() {
        val text = String(manifestOf(source()).encode(), Charsets.US_ASCII)
        val lines = text.split('\n')
        val chunkLine = lines[7].split(' ')
        fun reject(label: String, changed: String) =
            assertThrows(label, BackupException::class.java) { ChunkManifest.parse(changed.toByteArray(Charsets.US_ASCII)) }

        reject("size changed", text.replace(lines[7], "${chunkLine[0]} ${chunkLine[1].toLong() + 1} ${chunkLine[2]}"))
        reject("hash changed", text.replace(chunkLine[2], "g".repeat(64)))
        reject("hash shortened", text.replace(chunkLine[2], chunkLine[2].take(63)))
        reject("hash upper-cased", text.replace(chunkLine[2], "A".repeat(64)))
        reject("index swapped", text.replace(lines[6], "1 ${lines[6].substringAfter(' ')}"))
        reject("total changed", text.replace(lines[2], "total ${lines[2].substringAfter(' ').toLong() + 1}"))
        reject("count too small", text.replace(lines[5], "chunks 2"))
        reject("count too big", text.replace(lines[5], "chunks ${ChunkManifest.MAX_CHUNKS + 1}"))
        reject("extra line", text + "5 1 ${"a".repeat(64)}\n")
        reject("no trailing newline", text.dropLast(1))
        reject("wrong header", text.replace(ChunkManifest.HEADER, "NEXIO-TRANSFER/2"))
        reject("zero-size chunk", text.replace(lines[7], "${chunkLine[0]} 0 ${chunkLine[2]}"))
        reject("negative size", text.replace(lines[7], "${chunkLine[0]} -5 ${chunkLine[2]}"))
        reject("control character", text.replace("id ", "id \u0001"))
        assertThrows(BackupException::class.java) { ChunkManifest.parse(ByteArray(ChunkManifest.MAX_WIRE_BYTES + 1)) }
    }

    @Test fun traversalAndOddNamesAreRejected() {
        val text = String(manifestOf(source()).encode(), Charsets.US_ASCII)
        for (bad in listOf("../../evil", "..", "a/b", id.uppercase(), id + "0", id.dropLast(1), "$id/../x", "")) {
            assertThrows("migration id '$bad'", BackupException::class.java) {
                ChunkManifest.parse(text.replace("id $id", "id $bad").toByteArray())
            }
            assertThrows(BackupException::class.java) { TransferMessage.parse("DONE $bad".toByteArray()) }
            assertThrows(BackupException::class.java) { OutgoingTransfers(File(temp.root, "o2")).archiveFile(bad) }
            assertThrows(BackupException::class.java) { OutgoingTransfers(File(temp.root, "o2")).load(bad) }
        }
        assertFalse(File(temp.root, "evil").exists())

        assertEquals("chunk-3.part", TransferNames.chunkFileName(3))
        assertEquals(3, TransferNames.parseChunkFileName("chunk-3.part"))
        for (bad in listOf("../chunk-3.part", "chunk-03.part", "chunk-3.part/", "chunk-3.part.tmp", "chunk--1.part", "chunk-.part", "chunk-256.part", "chunk-99999.part", "/etc/passwd", "chunk-3.PART")) {
            assertNull("name '$bad'", TransferNames.parseChunkFileName(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { TransferNames.chunkFileName(-1) }
        assertThrows(IllegalArgumentException::class.java) { TransferNames.chunkFileName(ChunkManifest.MAX_CHUNKS) }
        // A chunk index outside the plan never reaches the disk.
        val transfer = incoming().open(manifestOf(source()), firstKey)
        assertFalse(transfer.accept(99, ByteArrayInputStream(ByteArray(10))))
        assertFalse(transfer.accept(-1, ByteArrayInputStream(ByteArray(10))))
    }

    @Test fun controlMessagesRoundTripAndGarbageIsRejected() {
        val sha = "a".repeat(64)
        val messages = listOf(
            TransferMessage.Manifest(manifestOf(source())),
            TransferMessage.Have(id, mapOf(0 to sha, 7 to sha)),
            TransferMessage.Have(id, emptyMap()),
            TransferMessage.Ack(id, 4),
            TransferMessage.Nak(id, 0),
            TransferMessage.Want(id, listOf(1, 3)),
            TransferMessage.Done(id),
            TransferMessage.Abort(id, AbortReason.NO_SPACE),
        )
        messages.forEach { assertEquals(it, TransferMessage.parse(it.encode())) }
        for (garbage in listOf("", "HELLO", "ACK $id", "ACK $id -1", "ACK $id 300", "ACK $id 1 2", "HAVE $id\n0 xyz\n", "HAVE $id\n0 $sha\n0 $sha\n", "HAVE $id\n0 $sha", "ABORT $id NOPE", "WANT $id 1,,2", "DONE $id\nmore")) {
            assertThrows("'$garbage'", BackupException::class.java) { TransferMessage.parse(garbage.toByteArray()) }
        }
    }

    @Test fun largeArchivesGetLargerChunksSoThePlanFits() {
        val gib = 1024L * 1024 * 1024
        assertEquals(ChunkManifest.DEFAULT_CHUNK_BYTES, ChunkManifest.chunkSizeFor(1))
        assertEquals(ChunkManifest.DEFAULT_CHUNK_BYTES, ChunkManifest.chunkSizeFor(gib))
        for (total in listOf(2 * gib, 10 * gib, 100 * gib)) {
            val size = ChunkManifest.chunkSizeFor(total)
            assertTrue((total + size - 1) / size <= ChunkManifest.MAX_CHUNKS)
            assertTrue(size <= ChunkManifest.MAX_CHUNK_BYTES)
        }
    }

    @Test fun theKeyNeverAppearsInTheManifest() {
        val manifest = String(manifestOf(source()).encode(), Charsets.US_ASCII)
        assertFalse(firstKey.toHex() in manifest)
        assertFalse(Base64.getEncoder().encodeToString(firstKey) in manifest)
        assertTrue(KeyCheck.matches(firstKey, id, manifestOf(source()).keyCheck))
        assertFalse(KeyCheck.matches(secondKey, id, manifestOf(source()).keyCheck))
    }

    // ---- planner, verifier, assembler ----------------------------------------------------------

    @Test fun missingMiddleChunkRefusesAssemblyAndPlannerListsExactlyThatIndex() {
        val src = source()
        val manifest = manifestOf(src)
        val transfer = incoming().open(manifest, firstKey)
        for (i in manifest.chunks.indices - 2) assertTrue(transfer.accept(i, ByteArrayInputStream(chunkBytes(src, i))))

        assertEquals(listOf(2), ChunkPlanner.missing(manifest, transfer.verifiedChunks()))
        val refused = transfer.assemble()
        assertEquals(AssemblyResult.Missing(listOf(2)), refused)
        assertFalse("no archive may exist while a chunk is missing", transfer.archive.exists())

        assertTrue(transfer.accept(2, ByteArrayInputStream(chunkBytes(src, 2))))
        assertTrue(ChunkPlanner.missing(manifest, transfer.verifiedChunks()).isEmpty())
        val done = transfer.assemble() as AssemblyResult.Assembled
        assertArrayEquals(src.archive, done.file.readBytes())
    }

    @Test fun badChecksumChunkIsDiscardedAndRequestedAgain() {
        val src = source()
        val manifest = manifestOf(src)
        val transfer = incoming().open(manifest, firstKey)
        val good = chunkBytes(src, 1)

        val flipped = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(transfer.accept(1, ByteArrayInputStream(flipped)))
        assertFalse(transfer.accept(1, ByteArrayInputStream(good.copyOf(good.size - 1))))
        assertFalse(transfer.accept(1, ByteArrayInputStream(good + byteArrayOf(0))))
        assertFalse("nothing kept for a rejected chunk", transfer.chunkFile(1).exists())
        assertFalse(File(transfer.dir, IncomingTransfers.RECEIVING).exists())
        assertEquals(listOf(0, 1, 2, 3, 4), ChunkPlanner.missing(manifest, transfer.verifiedChunks()))

        assertTrue(transfer.accept(1, ByteArrayInputStream(good)))
        assertEquals(setOf(1), transfer.verifiedChunks().keys)
        assertTrue(ChunkVerifier.verify(manifest.chunks[1], good))
        assertFalse(ChunkVerifier.verify(manifest.chunks[1], flipped))
    }

    @Test fun aKeptChunkThatChangedOnDiskIsDroppedOnResume() {
        val src = source()
        val manifest = manifestOf(src)
        val transfer = incoming().open(manifest, firstKey)
        for (i in 0..2) transfer.accept(i, ByteArrayInputStream(chunkBytes(src, i)))
        transfer.chunkFile(1).writeBytes(ByteArray(manifest.chunks[1].size.toInt()))
        assertEquals(setOf(0, 2), transfer.verifiedChunks().keys)
        assertFalse(transfer.chunkFile(1).exists())
        assertEquals(listOf(1, 3, 4), ChunkPlanner.missing(manifest, transfer.verifiedChunks()))
    }

    @Test fun finalArchiveHashIsCheckedEvenWhenEveryChunkMatches() {
        val src = source()
        val real = manifestOf(src)
        val lying = real.copy(archiveSha256 = "b".repeat(64))
        val transfer = incoming().open(lying, firstKey)
        for (i in lying.chunks.indices) assertTrue(transfer.accept(i, ByteArrayInputStream(chunkBytes(src, i))))
        assertEquals(AssemblyResult.ArchiveMismatch, transfer.assemble())
        assertFalse(transfer.archive.exists())
    }

    // ---- keys across a re-pairing --------------------------------------------------------------

    @Test fun keptChunksKeepTheirOriginalKeyWhenTheSecondPairingBringsANewOne() {
        val src = source()
        val manifest = manifestOf(src)
        val root = File(temp.root, "in")
        val first = IncomingTransfers(root, KeyWrapper.None).open(manifest, firstKey)
        for (i in 0..1) first.accept(i, ByteArrayInputStream(chunkBytes(src, i)))
        assertEquals(1, IncomingTransfers(root, KeyWrapper.None).pending().size)
        assertEquals(2, IncomingTransfers(root, KeyWrapper.None).pending().single().chunksKept)

        // New QR code, new key: the manifest is the same, so the kept chunks and the first key are reused.
        val resumed = IncomingTransfers(root, KeyWrapper.None).open(manifest, secondKey)
        assertArrayEquals(firstKey, resumed.key)
        assertEquals(setOf(0, 1), resumed.verifiedChunks().keys)
        for (i in 2 until manifest.chunks.size) resumed.accept(i, ByteArrayInputStream(chunkBytes(src, i)))
        val archive = (resumed.assemble() as AssemblyResult.Assembled).file
        val plain = BackupCrypto.decrypting(archive.inputStream(), BackupSecret.RawKey(resumed.key)).use { it.readBytes() }
        assertArrayEquals(bytes(4_500), plain)
    }

    @Test fun anOfferForUnknownChunksMustBeSealedWithTheCurrentPairingKey() {
        val src = source()
        val manifest = manifestOf(src)
        val store = incoming()
        val error = assertThrows(BackupException::class.java) { store.open(manifest, secondKey) }
        assertEquals(Msg.TRANSFER_RESTART_NEEDED(), error.message)
        assertTrue(store.pending().isEmpty())

        // A different plan under the same id cannot take over kept chunks, and cannot wipe them either.
        val kept = store.open(manifest, firstKey)
        kept.accept(0, ByteArrayInputStream(chunkBytes(src, 0)))
        val other = manifest.copy(archiveSha256 = "c".repeat(64))
        assertThrows(BackupException::class.java) { store.open(other, secondKey) }
        assertTrue(kept.chunkFile(0).isFile)
    }

    @Test fun startingADifferentTransferRemovesTheOldKeptOneAndCancelRemovesEverything() {
        val src = source()
        val store = incoming()
        store.open(manifestOf(src), firstKey).accept(0, ByteArrayInputStream(chunkBytes(src, 0)))
        val otherId = "f".repeat(32)
        val otherSource = OutgoingTransfers(File(temp.root, "out2"))
        otherSource.archiveFile(otherId).writeBytes(bytes(2_000))
        val other = otherSource.seal(otherId, secondKey, chunkSize)
        store.open(other.manifest, secondKey)
        assertEquals(listOf(otherId), store.pending().map { it.migrationId })
        store.deleteAll()
        assertTrue(store.pending().isEmpty())
        assertFalse(File(temp.root, "in").exists())
    }

    // ---- sessions over a fake link -------------------------------------------------------------

    private class Recorder : TransferObserver {
        var offer: TransferOffer? = null
        val progress = mutableListOf<TransferProgress>()
        var failure: Pair<String, Boolean>? = null
        var sourceFinished = false
        var archive: File? = null
        var key: ByteArray? = null

        override fun onOffer(offer: TransferOffer) { this.offer = offer }
        override fun onProgress(progress: TransferProgress) { this.progress += progress }
        override fun onSourceFinished() { sourceFinished = true }
        override fun onArchiveReady(archive: File, key: ByteArray) { this.archive = archive; this.key = key }
        override fun onFailed(message: String, resumable: Boolean) { failure = message to resumable }
    }

    /**
     * An in-memory link that counts sends. Messages go through encode and parse, as on the wire. A drop
     * happens at the n-th chunk send (0-based): that chunk never arrives, and both sides learn the link is gone.
     */
    private class Wire(
        outgoing: OutgoingTransfer,
        incoming: IncomingTransfers,
        pairingKey: ByteArray,
        private val dropAtSend: Int = -1,
        private val corrupt: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        val sourceSees = Recorder()
        val destinationSees = Recorder()
        val sent = mutableListOf<Int>()
        private val queue = ArrayDeque<() -> Unit>()
        private var accepted = false

        val source: SourceSession = SourceSession(outgoing, object : SourceLink {
            override fun send(message: TransferMessage) {
                val copy = TransferMessage.parse(message.encode())
                queue += { destination.onMessage(copy) }
            }

            override fun sendChunk(index: Int, data: InputStream, size: Long) {
                sent += index
                val body = data.readBytes()
                assertEquals(size, body.size.toLong())
                if (sent.size - 1 == dropAtSend) {
                    queue.clear()
                    queue += { source.onLinkLost(); destination.onLinkLost() }
                    return
                }
                if ((corrupt[index] ?: 0) > 0) {
                    corrupt[index] = corrupt.getValue(index) - 1
                    body[0] = (body[0].toInt() xor 1).toByte()
                }
                queue += { destination.onChunk(index, ByteArrayInputStream(body)) }
            }
        }, sourceSees)

        val destination: DestinationSession = DestinationSession(incoming, pairingKey, object : DestinationLink {
            override fun send(message: TransferMessage) {
                val copy = TransferMessage.parse(message.encode())
                queue += { source.onMessage(copy) }
            }
        }, destinationSees)

        fun run() {
            source.start()
            while (true) {
                val step = queue.removeFirstOrNull()
                if (step != null) {
                    step()
                } else if (destinationSees.offer != null && !accepted) {
                    accepted = true
                    destination.accept()
                } else {
                    break
                }
            }
        }
    }

    @Test fun aCompleteTransferSendsEachChunkOnceAndDeliversTheArchive() {
        val src = source()
        val wire = Wire(src.transfer, incoming(), firstKey)
        wire.run()
        assertEquals(listOf(0, 1, 2, 3, 4), wire.sent)
        assertTrue(wire.sourceSees.sourceFinished)
        assertNull(wire.sourceSees.failure)
        assertNull(wire.destinationSees.failure)
        assertArrayEquals(src.archive, wire.destinationSees.archive!!.readBytes())
        val last = wire.destinationSees.progress.last()
        assertEquals(TransferProgress(5, 5, src.archive.size.toLong(), src.archive.size.toLong()), last)
        assertEquals(0L, last.bytesLeft)
        assertEquals(0, wire.destinationSees.progress.first().chunksDone)
    }

    @Test fun aRetryAfterADropSendsOnlyTheRemainingChunks() {
        val src = source()
        val inRoot = File(temp.root, "in")

        val firstTry = Wire(src.transfer, IncomingTransfers(inRoot, KeyWrapper.None), firstKey, dropAtSend = 3)
        firstTry.run()
        assertEquals(listOf(0, 1, 2, 3), firstTry.sent) // chunk 3 was on the wire when the link went down
        assertEquals(true, firstTry.sourceSees.failure?.second)
        assertEquals(true, firstTry.destinationSees.failure?.second)
        assertEquals(Msg.TRANSFER_INTERRUPTED_RESUME(), firstTry.sourceSees.failure?.first)
        assertNull(firstTry.destinationSees.archive)
        assertTrue("the source keeps its sealed archive", src.transfer.archive.isFile)
        assertEquals(3, IncomingTransfers(inRoot, KeyWrapper.None).pending().single().chunksKept)

        // Later: a new pairing (new key), the source reloads the same sealed archive from disk.
        val reloaded = OutgoingTransfers(File(temp.root, "out")).pending().single()
        assertEquals(src.transfer.manifest, reloaded.manifest)
        val secondTry = Wire(reloaded, IncomingTransfers(inRoot, KeyWrapper.None), secondKey)
        secondTry.run()
        assertEquals("completed chunks must not be sent again", listOf(3, 4), secondTry.sent)
        assertEquals(3, secondTry.destinationSees.offer!!.keptChunks)
        assertEquals(src.transfer.manifest.chunks[3].size + src.transfer.manifest.chunks[4].size, secondTry.destinationSees.offer!!.remainingBytes)
        assertEquals(3, secondTry.destinationSees.progress.first().chunksDone)
        assertEquals(3_000L, secondTry.destinationSees.progress.first().bytesDone)
        assertTrue(secondTry.sourceSees.sourceFinished)
        assertArrayEquals(src.archive, secondTry.destinationSees.archive!!.readBytes())
        // The second pairing had a different key, but the archive opens only with the first one, which the destination kept.
        assertArrayEquals(firstKey, secondTry.destinationSees.key)
        val plain = BackupCrypto.decrypting(secondTry.destinationSees.archive!!.inputStream(), BackupSecret.RawKey(secondTry.destinationSees.key!!)).use { it.readBytes() }
        assertArrayEquals(bytes(4_500), plain)
    }

    @Test fun everyDropPointResumesWithoutResendingFinishedChunks() {
        for (dropAt in 0..4) {
            val root = File(temp.root, "run$dropAt")
            val src = source(File(root, "out"))
            val inRoot = File(root, "in")
            val first = Wire(src.transfer, IncomingTransfers(inRoot, KeyWrapper.None), firstKey, dropAtSend = dropAt).also { it.run() }
            assertEquals((0..dropAt).toList(), first.sent)
            val retry = Wire(src.transfer, IncomingTransfers(inRoot, KeyWrapper.None), secondKey).also { it.run() }
            assertEquals("drop at $dropAt", (dropAt..4).toList(), retry.sent)
            assertArrayEquals(src.archive, retry.destinationSees.archive!!.readBytes())
        }
    }

    @Test fun aBadChunkIsDiscardedAndSentAgain() {
        val src = source()
        val wire = Wire(src.transfer, incoming(), firstKey, corrupt = mutableMapOf(2 to 1))
        wire.run()
        assertEquals(listOf(0, 1, 2, 2, 3, 4), wire.sent)
        assertNull(wire.sourceSees.failure)
        assertArrayEquals(src.archive, wire.destinationSees.archive!!.readBytes())
    }

    @Test fun aChunkThatKeepsFailingStopsTheTransferButKeepsTheGoodChunks() {
        val src = source()
        val root = File(temp.root, "in")
        val wire = Wire(src.transfer, IncomingTransfers(root, KeyWrapper.None), firstKey, corrupt = mutableMapOf(2 to 99))
        wire.run()
        assertEquals(listOf(0, 1) + List(SourceSession.MAX_ATTEMPTS) { 2 }, wire.sent)
        assertEquals(Msg.TRANSFER_PART_FAILED() to false, wire.sourceSees.failure)
        assertNull(wire.destinationSees.archive)
        assertEquals(2, IncomingTransfers(root, KeyWrapper.None).pending().single().chunksKept)
    }

    @Test fun whenEverythingIsAlreadyKeptNothingIsSentAndTheArchiveIsDelivered() {
        val src = source()
        val store = incoming()
        val kept = store.open(manifestOf(src), firstKey)
        for (i in manifestOf(src).chunks.indices) kept.accept(i, ByteArrayInputStream(chunkBytes(src, i)))
        val wire = Wire(src.transfer, IncomingTransfers(File(temp.root, "in"), KeyWrapper.None), secondKey)
        wire.run()
        assertTrue(wire.sent.isEmpty())
        assertTrue(wire.sourceSees.sourceFinished)
        assertArrayEquals(src.archive, wire.destinationSees.archive!!.readBytes())
    }

    @Test fun aDestinationWithoutTheKeptChunksAsksTheOwnerToStartOver() {
        val src = source()
        val wire = Wire(src.transfer, incoming(File(temp.root, "fresh")), secondKey)
        wire.run()
        assertTrue(wire.sent.isEmpty())
        assertEquals(Msg.TRANSFER_RESTART_NEEDED() to false, wire.destinationSees.failure)
        assertEquals(Msg.TRANSFER_RESTART_NEEDED() to false, wire.sourceSees.failure)
        assertNull(wire.destinationSees.archive)
    }

    @Test fun aDamagedSourceArchiveIsNeverSent() {
        val src = source()
        val damaged = src.transfer.archive.readBytes().also { it[1500] = (it[1500].toInt() xor 1).toByte() }
        src.transfer.archive.writeBytes(damaged)
        val wire = Wire(src.transfer, incoming(), firstKey)
        wire.run()
        assertEquals(Msg.TRANSFER_SOURCE_DAMAGED() to false, wire.sourceSees.failure)
        assertEquals(listOf(0), wire.sent) // chunk 0 was intact; chunk 1 failed its check before sending
        assertNull(wire.destinationSees.archive)
    }

    @Test fun cancellingDeletesEverythingKept() {
        val src = source()
        val inRoot = File(temp.root, "in")
        Wire(src.transfer, IncomingTransfers(inRoot, KeyWrapper.None), firstKey, dropAtSend = 2).run()
        assertTrue(inRoot.exists() && OutgoingTransfers(File(temp.root, "out")).pending().isNotEmpty())
        IncomingTransfers(inRoot, KeyWrapper.None).deleteAll()
        src.transfer.delete()
        assertFalse(inRoot.exists())
        assertTrue(OutgoingTransfers(File(temp.root, "out")).pending().isEmpty())
    }

    // ---- pairing code ----------------------------------------------------------------------------

    @Test fun anExpiredPairingCodeIsRejectedAndARetryGetsAFreshOne() {
        val first = PairingCode.create(1_000)
        val text = first.encode()
        assertEquals(first, PairingCode.parse(text, first.expiresAt - 1))
        val error = assertThrows(BackupException::class.java) { PairingCode.parse(text, first.expiresAt) }
        assertEquals(Msg.TRANSFER_CODE_EXPIRED(), error.message)
        assertThrows(BackupException::class.java) { PairingCode.parse(text, first.expiresAt + 60_000) }

        val second = PairingCode.create(first.expiresAt + 1)
        assertNotEquals(first.session, second.session)
        assertNotEquals(first.key, second.key)
        assertNotNull(PairingCode.parse(second.encode(), second.expiresAt - 1))
        assertFalse("the code carries no migration id or archive facts", text.contains(id))
    }
}
