package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.backup.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val temp = TemporaryFolder()

    private val password = BackupSecret.Password("correct horse".toCharArray())
    private val iterations = 1_000 // Fast for tests; the app uses BackupCrypto.ITERATIONS.

    private fun bytes(size: Int) = ByteArray(size).also(Random(7)::nextBytes)

    private fun seal(data: ByteArray, secret: BackupSecret = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypting(out, secret, iterations).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, secret: BackupSecret = password) =
        BackupCrypto.decrypting(ByteArrayInputStream(sealed), secret).use { it.readBytes() }

    @Test fun roundTripsAcrossChunkBoundaries() {
        for (size in listOf(0, 1, (1 shl 20) - 1, 1 shl 20, (1 shl 20) + 1, (3 shl 20) + 5)) {
            assertArrayEquals("size $size", bytes(size), open(seal(bytes(size))))
        }
    }

    @Test fun passwordIsNotStoredAndPlaintextDoesNotLeak() {
        val marker = "SECRET-BUSINESS-DATA".toByteArray()
        val sealed = String(seal(marker), Charsets.ISO_8859_1)
        assertFalse("SECRET-BUSINESS-DATA" in sealed)
        assertFalse("correct horse" in sealed)
    }

    @Test fun wrongPasswordIsRefused() {
        assertThrows(BackupException::class.java) { open(seal(bytes(100)), BackupSecret.Password("nope".toCharArray())) }
    }

    @Test fun anyFlippedByteIsDetected() {
        val sealed = seal(bytes(5_000))
        for (position in listOf(40, 60, 2_000, sealed.size - 1)) {
            val damaged = sealed.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertThrows("byte $position", BackupException::class.java) { open(damaged) }
        }
    }

    @Test fun truncationAtAChunkBoundaryIsDetected() {
        val sealed = seal(bytes((2 shl 20) + 100))
        val firstChunk = 8 + 1 + 1 + 4 + 16 + 7 + 4 + (1 shl 20) + 16
        assertThrows(BackupException::class.java) { open(sealed.copyOf(firstChunk)) }
        assertThrows(BackupException::class.java) { open(sealed.copyOf(sealed.size - 1)) }
    }

    @Test fun reorderedChunksAreDetected() {
        val sealed = seal(bytes(3 shl 20))
        val header = 37
        val chunk = 4 + (1 shl 20) + 16
        val swapped = sealed.copyOf()
        sealed.copyInto(swapped, header, header + chunk, header + 2 * chunk)
        sealed.copyInto(swapped, header + chunk, header, header + chunk)
        assertThrows(BackupException::class.java) { open(swapped) }
    }

    @Test fun foreignFileIsNotMistakenForABackup() {
        val e = assertThrows(BackupException::class.java) { open(ByteArray(200)) }
        assertTrue(e.message!!.contains("not a Nexio"))
    }

    private fun tree(): Pair<File, List<String>> {
        val root = temp.newFolder("src")
        File(root, "db").mkdirs()
        File(root, "media/image").mkdirs()
        File(root, "db/nexio.db").writeBytes(bytes(10_000))
        File(root, "media/image/a.png").writeBytes(bytes(3_000))
        return root to listOf("db/nexio.db", "media/image/a.png")
    }

    private fun archive(root: File, paths: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveWriter.write(out, root, ArchiveWriter.manifest(root, paths, 1, "0.1", 5))
        return out.toByteArray()
    }

    @Test fun archiveRoundTripVerifiesEveryFile() {
        val (root, paths) = tree()
        val staging = temp.newFolder("staging")
        val manifest = ArchiveReader.extract(ByteArrayInputStream(archive(root, paths)), staging, Long.MAX_VALUE)
        assertEquals(2, manifest.files.size)
        assertEquals(13_000, manifest.totalBytes)
        assertArrayEquals(File(root, "db/nexio.db").readBytes(), File(staging, "db/nexio.db").readBytes())
    }

    /** Builds an archive by hand, the way an attacker would. */
    private fun forged(manifest: BackupManifest, entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifestOf(vararg files: ManifestFile) = BackupManifest(schemaVersion = 1, appVersion = "x", createdAt = 0, files = files.toList())

    private fun assertRejected(archive: ByteArray, staging: File) {
        assertThrows(BackupException::class.java) { ArchiveReader.extract(ByteArrayInputStream(archive), staging, Long.MAX_VALUE) }
    }

    @Test fun pathTraversalIsRejected() {
        for (bad in listOf("../evil", "media/../../evil", "/etc/passwd", "media/a\\b", "media/./a", "db", "other/a", "media//a", "media/a\u0000.png", "")) {
            assertFalse(bad, PathValidator.isSafe(bad))
        }
        assertTrue(PathValidator.isSafe("media/image/0a-b_c.png"))
        val data = bytes(10)
        val staging = temp.newFolder("t1")
        assertRejected(forged(manifestOf(ManifestFile("../../evil.txt", 10, Hashing.sha256(data))), listOf("../../evil.txt" to data)), staging)
        assertFalse(File(staging.parentFile, "evil.txt").exists())
    }

    @Test fun unexpectedFileIsRejected() {
        val data = bytes(10)
        val listed = ManifestFile("media/a.png", 10, Hashing.sha256(data))
        assertRejected(forged(manifestOf(listed), listOf("media/a.png" to data, "media/extra.png" to data)), temp.newFolder("t2"))
    }

    @Test fun tamperedFileFailsChecksum() {
        val data = bytes(10)
        val listed = ManifestFile("media/a.png", 10, Hashing.sha256(data))
        assertRejected(forged(manifestOf(listed), listOf("media/a.png" to bytes(10).also { it[0] = 99 })), temp.newFolder("t3"))
    }

    @Test fun missingFileIsRejected() {
        val data = bytes(10)
        val a = ManifestFile("media/a.png", 10, Hashing.sha256(data))
        val b = ManifestFile("media/b.png", 10, Hashing.sha256(data))
        assertRejected(forged(manifestOf(a, b), listOf("media/a.png" to data)), temp.newFolder("t4"))
    }

    @Test fun compressionBombStopsAtDeclaredSize() {
        val huge = ByteArray(50_000_000)
        val listed = ManifestFile("media/a.png", 100, Hashing.sha256(huge))
        val staging = temp.newFolder("t5")
        assertRejected(forged(manifestOf(listed), listOf("media/a.png" to huge)), staging)
        assertTrue(File(staging, "media/a.png").length() <= 100_000)
    }

    @Test fun missingManifestIsRejected() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("media/a.png")); it.write(1); it.closeEntry() }
        assertRejected(out.toByteArray(), temp.newFolder("t6"))
    }

    @Test fun notEnoughStorageIsRefusedBeforeWriting() {
        val (root, paths) = tree()
        val staging = temp.newFolder("t7")
        val e = assertThrows(BackupException::class.java) { ArchiveReader.extract(ByteArrayInputStream(archive(root, paths)), staging, 100) }
        assertTrue(e.message!!.contains("storage"))
        assertEquals(0, staging.listFiles()!!.size)
    }

    @Test fun pairingCodeIsTemporaryAndCarriesOnlyTransferFields() {
        val code = PairingCode.create(1_000)
        val text = code.encode()
        assertEquals(code, PairingCode.parse(text, 1_000))
        assertEquals(32, code.keyBytes.size)
        val fields = PairingCode::class.java.declaredFields.map { it.name }.filter { it != "Companion" && !it.startsWith("$") && it != "PREFIX" && !it.endsWith("BYTES") && it != "LIFETIME_MILLIS" }
        assertEquals(setOf("version", "session", "key", "expiresAt"), fields.toSet())
        assertThrows(BackupException::class.java) { PairingCode.parse(text, code.expiresAt) }
        assertThrows(BackupException::class.java) { PairingCode.parse("hello", 0) }
        assertThrows(BackupException::class.java) { PairingCode.parse(text.replace(code.session, "../x"), 0) }
    }

    @Test fun oneTimeKeyProtectsTransfers() {
        val code = PairingCode.create(0)
        val other = PairingCode.create(0)
        val sealed = seal(bytes(100), BackupSecret.RawKey(code.keyBytes))
        assertArrayEquals(bytes(100), open(sealed, BackupSecret.RawKey(code.keyBytes)))
        assertThrows(BackupException::class.java) { open(sealed, BackupSecret.RawKey(other.keyBytes)) }
    }
}
