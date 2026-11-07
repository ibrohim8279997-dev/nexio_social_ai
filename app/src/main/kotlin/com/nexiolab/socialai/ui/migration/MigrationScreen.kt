package com.nexiolab.socialai.ui.migration

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.data.backup.NearbyTransfer
import com.nexiolab.socialai.data.backup.StagedRestore
import com.nexiolab.socialai.data.backup.TransferEvent
import com.nexiolab.socialai.data.backup.TransferStorage
import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.backup.BackupManifest
import com.nexiolab.socialai.domain.backup.BackupSecret
import com.nexiolab.socialai.domain.backup.PairingCode
import com.nexiolab.socialai.domain.transfer.OutgoingTransfer
import com.nexiolab.socialai.domain.transfer.TransferNames
import com.nexiolab.socialai.domain.model.BackupRecord
import com.nexiolab.socialai.domain.model.MigrationManifestRecord
import com.nexiolab.socialai.domain.model.MigrationRole
import com.nexiolab.socialai.domain.model.MigrationSession
import com.nexiolab.socialai.domain.model.MigrationStatus
import com.nexiolab.socialai.ui.common.Authenticator
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.Field
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.common.rememberAuthenticator
import com.nexiolab.socialai.ui.components.NexioCard
import com.nexiolab.socialai.ui.components.NexioProgressBar
import com.nexiolab.socialai.ui.components.ScreenColumn
import com.nexiolab.socialai.ui.components.SectionHeader
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

data class Progress(val label: String, val done: Long, val total: Long, val speed: Long = 0, val chunksDone: Int = 0, val chunksTotal: Int = 0)

/** Which side of an unfinished transfer this phone is on, and so what Retry does. */
enum class ResumeKind { SEND, RECEIVE }

/** A transfer that stopped part way and whose finished chunks are still on this phone. */
data class PendingTransfer(val kind: ResumeKind, val migrationId: String, val totalSize: Long, val chunksKept: Int, val chunksTotal: Int)

sealed interface Flow {
    data object Idle : Flow
    data class Preparing(val label: String) : Flow
    data class ShowCode(val code: PairingCode, val files: Int, val bytes: Long, val resumed: Boolean = false) : Flow
    data object Searching : Flow
    data class Confirm(val digits: String) : Flow
    data class Offer(val bytes: Long, val keptChunks: Int = 0, val totalChunks: Int = 0) : Flow
    data object SendDone : Flow
    data class Review(val staged: StagedRestore) : Flow
    /** [resume] is set when the kept chunks are good, so Retry can continue where the transfer stopped. */
    data class Failed(val message: String, val resume: ResumeKind? = null) : Flow
}

class MigrationViewModel(private val app: AppContainer) : BaseViewModel() {
    val flow = MutableStateFlow<Flow>(Flow.Idle)
    val progress = MutableStateFlow<Progress?>(null)
    val backups = app.db.migration().observeBackups().state(emptyList())
    private var transfer: NearbyTransfer? = null
    private var role = ResumeKind.SEND
    private val storage = TransferStorage(app.context)

    /** Unfinished transfers whose chunks are kept, so the owner can continue or delete them. */
    val pending = MutableStateFlow<List<PendingTransfer>>(emptyList())

    /** Rows are saved one after another in the order they were queued, so a manifest never beats its session. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val sessionLock = Any()
    private var session: MigrationSession? = null
    private val prefs = app.context.getSharedPreferences(RESTORE_PREFS, android.content.Context.MODE_PRIVATE)

    init {
        viewModelScope.launch { for (job in writes) runCatching { job() } }
        // A restore that failed and was rolled back ended in a restart, which hid the reason.
        prefs.getString(RESTORE_ERROR, null)?.let {
            prefs.edit().remove(RESTORE_ERROR).apply()
            flow.value = Flow.Failed(it)
        }
        refreshPending()
    }

    private fun refreshPending() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching {
                storage.outgoing.pending().map { PendingTransfer(ResumeKind.SEND, it.manifest.migrationId, it.manifest.totalSize, 0, it.manifest.chunks.size) } +
                    storage.incoming.pending().map { PendingTransfer(ResumeKind.RECEIVE, it.migrationId, it.totalSize, it.chunksKept, it.chunksTotal) }
            }.getOrDefault(emptyList())
            pending.value = list
        }
    }

    private fun beginSession(id: String, role: MigrationRole, status: MigrationStatus) {
        val row = MigrationSession(id = id, role = role, status = status, startedAt = System.currentTimeMillis())
        synchronized(sessionLock) { session = row }
        writes.trySend { app.db.migration().saveSession(row) }
    }

    /** Written on status changes only; byte counts ride along instead of being saved on every tick. */
    private fun setStatus(status: MigrationStatus, error: String? = null, change: (MigrationSession) -> MigrationSession = { it }) {
        val row = synchronized(sessionLock) {
            val current = session ?: return
            if (current.finishedAt != null) return
            val finished = status == MigrationStatus.COMPLETED || status == MigrationStatus.FAILED || status == MigrationStatus.CANCELLED
            change(current).copy(status = status, error = error ?: current.error, finishedAt = if (finished) System.currentTimeMillis() else null)
                .also { session = it }
        }
        writes.trySend { app.db.migration().saveSession(row) }
    }

    private fun trackBytes(done: Long, total: Long) {
        val needsStatus = synchronized(sessionLock) {
            val current = session ?: return
            session = current.copy(transferredBytes = done, totalBytes = total)
            current.status != MigrationStatus.TRANSFERRING
        }
        if (needsStatus) setStatus(MigrationStatus.TRANSFERRING)
    }

    private fun saveManifest(manifest: BackupManifest) {
        val id = synchronized(sessionLock) { session?.id } ?: return
        val json = Json.encodeToString(BackupManifest.serializer(), manifest)
        val now = System.currentTimeMillis()
        writes.trySend { app.db.migration().saveManifest(MigrationManifestRecord(sessionId = id, manifestJson = json, createdAt = now)) }
    }

    private fun bytes(label: String): (Long, Long) -> Unit = { done, total -> progress.value = Progress(label, done, total) }

    private fun deviceName() = Build.MODEL.take(30)

    fun createBackup(target: Uri, password: String) = act(app.context.getString(R.string.migration_backup_saved)) {
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_creating_backup))
        try {
            val manifest = withContext(Dispatchers.IO) {
                val output = app.context.contentResolver.openOutputStream(target) ?: error(app.context.getString(R.string.migration_error_write_backup))
                app.backup.export(output, BackupSecret.Password(password.toCharArray()), bytes(app.context.getString(R.string.migration_encrypting)))
            }
            // Read the finished file back: this is its real size and fingerprint, and proves it can be read.
            val (size, sha256) = withContext(Dispatchers.IO) {
                try {
                    val input = app.context.contentResolver.openInputStream(target) ?: error(app.context.getString(R.string.migration_error_verify_backup))
                    CountingInputStream(input).use { counted -> Hashing.sha256(counted).let { counted.count to it } }
                } catch (e: java.io.IOException) {
                    error(app.context.getString(R.string.migration_error_verify_backup))
                }
            }
            val name = target.lastPathSegment?.substringAfterLast('/') ?: "backup"
            app.db.migration().saveBackup(BackupRecord(displayName = name, sizeBytes = size, sha256 = sha256, schemaVersion = manifest.schemaVersion, createdAt = System.currentTimeMillis()))
            app.activity.log(app.context.getString(R.string.migration_activity_backup_created), app.context.getString(R.string.migration_activity_files, manifest.files.size))
        } finally {
            flow.value = Flow.Idle
            progress.value = null
        }
    }

    fun restore(source: Uri, password: String) = viewModelScope.launch {
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_checking_backup))
        try {
            val staged = withContext(Dispatchers.IO) {
                val input = app.context.contentResolver.openInputStream(source) ?: error(app.context.getString(R.string.migration_error_open_backup))
                app.backup.stage(input, BackupSecret.Password(password.toCharArray()), bytes(app.context.getString(R.string.migration_verifying)))
            }
            flow.value = Flow.Review(staged)
        } catch (e: Exception) {
            flow.value = Flow.Failed(e.message ?: app.context.getString(R.string.migration_error_read_backup))
        }
        progress.value = null
    }

    /**
     * Source phone. The archive is sealed once with the QR's one-time key, cut into chunks and kept
     * (without the key) until it is delivered or cancelled. A retry reuses the same sealed bytes.
     */
    fun startSending() = viewModelScope.launch {
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_preparing_data))
        try {
            val code = PairingCode.create(System.currentTimeMillis())
            beginSession(code.session, MigrationRole.SOURCE, MigrationStatus.PREPARING)
            val migrationId = TransferNames.newMigrationId()
            val label = app.context.getString(R.string.migration_preparing)
            // One unfinished outgoing transfer at a time: a new one replaces the old.
            val (sealed, manifest) = withContext(Dispatchers.IO) {
                storage.outgoing.deleteAll()
                val file = storage.outgoing.archiveFile(migrationId)
                val manifest = app.backup.export(file.outputStream(), BackupSecret.RawKey(code.keyBytes), bytes(label))
                val sealed = storage.outgoing.seal(migrationId, code.keyBytes) { done -> progress.value = Progress(label, done, file.length()) }
                sealed to manifest
            }
            progress.value = null
            saveManifest(manifest)
            setStatus(MigrationStatus.WAITING_FOR_PEER) { it.copy(fileCount = manifest.files.size, totalBytes = sealed.manifest.totalSize) }
            present(code, sealed, manifest.files.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { storage.outgoing.deleteAll() }
            progress.value = null
            val message = e.message ?: app.context.getString(R.string.migration_error_prepare)
            setStatus(MigrationStatus.FAILED, message)
            flow.value = Flow.Failed(message)
        }
    }

    /**
     * Retry on the source: a new pairing (new code, new session) for the kept sealed archive. The
     * archive is not rebuilt, so the chunk hashes still match what the destination kept.
     */
    fun resumeSending() = viewModelScope.launch {
        try {
            val kept = withContext(Dispatchers.IO) { storage.outgoing.pending().firstOrNull() }
            if (kept == null) {
                flow.value = Flow.Failed(app.context.getString(R.string.migration_error_nothing_to_resume))
                return@launch
            }
            val code = PairingCode.create(System.currentTimeMillis())
            beginSession(code.session, MigrationRole.SOURCE, MigrationStatus.WAITING_FOR_PEER)
            setStatus(MigrationStatus.WAITING_FOR_PEER) { it.copy(totalBytes = kept.manifest.totalSize) }
            present(code, kept, files = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: app.context.getString(R.string.migration_error_prepare)
            setStatus(MigrationStatus.FAILED, message)
            flow.value = Flow.Failed(message, ResumeKind.SEND)
        }
    }

    /** Shows the code and waits. When the code expires the archive stays; Retry makes a new code. */
    private suspend fun present(code: PairingCode, sealed: OutgoingTransfer, files: Int?) {
        role = ResumeKind.SEND
        flow.value = Flow.ShowCode(code, files ?: 0, sealed.manifest.totalSize, resumed = files == null)
        transfer = NearbyTransfer(app.context, ::onTransfer).also { it.host(code, sealed, deviceName()) }
        delay(PairingCode.LIFETIME_MILLIS)
        if ((flow.value as? Flow.ShowCode)?.code == code) {
            transfer?.stop()
            transfer = null
            val message = app.context.getString(R.string.migration_error_code_expired)
            setStatus(MigrationStatus.FAILED, message)
            flow.value = Flow.Failed(message, ResumeKind.SEND)
            refreshPending()
        }
    }

    /**
     * Destination phone. A retry scans the sender's new code. Kept chunks are matched by the migration
     * id in the manifest, not by the code, and stay sealed with the first pairing's key.
     */
    fun startReceiving(scanned: String) {
        try {
            val code = PairingCode.parse(scanned, System.currentTimeMillis())
            role = ResumeKind.RECEIVE
            beginSession(code.session, MigrationRole.DESTINATION, MigrationStatus.WAITING_FOR_PEER)
            flow.value = Flow.Searching
            transfer = NearbyTransfer(app.context, ::onTransfer).also { it.join(code, deviceName()) }
        } catch (e: Exception) {
            val message = e.message ?: app.context.getString(R.string.migration_error_code_unusable)
            setStatus(MigrationStatus.FAILED, message)
            flow.value = Flow.Failed(message)
        }
    }

    private fun onTransfer(event: TransferEvent) {
        when (event) {
            TransferEvent.Waiting -> Unit
            is TransferEvent.Confirm -> {
                setStatus(MigrationStatus.CONFIRMING)
                flow.value = Flow.Confirm(event.digits)
            }
            is TransferEvent.Offer -> {
                // Room for the chunks still to come, plus the assembled archive before the chunks are freed.
                val needed = event.bytes + event.remainingBytes
                if (needed > app.context.filesDir.usableSpace) {
                    val message = app.context.getString(R.string.migration_error_no_space, Format.bytes(needed))
                    setStatus(MigrationStatus.FAILED, message)
                    flow.value = Flow.Failed(message, if (event.keptChunks > 0) ResumeKind.RECEIVE else null)
                    transfer?.decline(noSpace = true)
                    refreshPending()
                } else {
                    setStatus(MigrationStatus.CONFIRMING) { it.copy(totalBytes = event.bytes) }
                    flow.value = Flow.Offer(event.bytes, event.keptChunks, event.totalChunks)
                }
            }
            is TransferEvent.Progress -> {
                trackBytes(event.done, event.total)
                progress.value = Progress(app.context.getString(R.string.migration_transferring), event.done, event.total, event.bytesPerSecond, event.chunksDone, event.chunksTotal)
            }
            TransferEvent.Sent -> {
                viewModelScope.launch(Dispatchers.IO) { storage.outgoing.deleteAll(); refreshPending() }
                setStatus(MigrationStatus.COMPLETED) { it.copy(transferredBytes = it.totalBytes) }
                flow.value = Flow.SendDone
                progress.value = null
            }
            is TransferEvent.Received -> {
                setStatus(MigrationStatus.VERIFYING) { it.copy(transferredBytes = it.totalBytes) }
                viewModelScope.launch { unpack(event.file, event.key) }
            }
            is TransferEvent.Failed -> {
                progress.value = null
                // Without a resumable state the sealed archive is of no further use on the source.
                if (!event.resumable && role == ResumeKind.SEND) {
                    viewModelScope.launch(Dispatchers.IO) { storage.outgoing.deleteAll(); refreshPending() }
                }
                setStatus(MigrationStatus.FAILED, event.message)
                flow.value = Flow.Failed(event.message, if (event.resumable) role else null)
                refreshPending()
            }
        }
    }

    /** [key] is the key the archive was sealed with: the first pairing's, even after a retry. */
    private suspend fun unpack(file: File, key: ByteArray) {
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_verifying_received))
        try {
            val staged = app.backup.stage(file.inputStream(), BackupSecret.RawKey(key), bytes(app.context.getString(R.string.migration_verifying)))
            saveManifest(staged.manifest)
            setStatus(MigrationStatus.COMPLETED)
            flow.value = Flow.Review(staged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: app.context.getString(R.string.migration_error_verify_received)
            setStatus(MigrationStatus.FAILED, message)
            flow.value = Flow.Failed(message)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { storage.incoming.deleteAll() }
            key.fill(0)
            progress.value = null
            refreshPending()
        }
    }

    fun confirmPairing() {
        transfer?.confirm()
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_connecting))
    }

    fun acceptOffer() {
        transfer?.acceptOffer()
        setStatus(MigrationStatus.TRANSFERRING)
        flow.value = Flow.Preparing(app.context.getString(R.string.migration_receiving))
    }

    /**
     * Swaps the data in and restarts, because the running process still has the old database open.
     * The database is closed even when the swap fails and is rolled back, so the restart runs either
     * way. A failure is shown and kept, then the restart follows after a pause long enough to read it.
     */
    fun commit(restart: () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            withContext(NonCancellable + Dispatchers.IO) { app.backup.commit() }
        } catch (e: Exception) {
            val message = e.message ?: app.context.getString(R.string.migration_error_replace)
            prefs.edit().putString(RESTORE_ERROR, message).commit()
            flow.value = Flow.Failed(message)
            messages.tryEmit(message)
            withContext(NonCancellable) { delay(RESTART_AFTER_FAILURE_MILLIS) }
        } finally {
            busy.value = false
            restart()
        }
    }

    /** Cancel: stops the link and deletes everything kept for a transfer, on both roles. */
    fun cancel() {
        setStatus(MigrationStatus.CANCELLED)
        transfer?.stop()
        transfer = null
        app.backup.discardStaged()
        progress.value = null
        flow.value = Flow.Idle
        pending.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            storage.outgoing.deleteAll()
            storage.incoming.deleteAll()
            refreshPending()
        }
    }

    /** Back: leaves the screen state but keeps the finished chunks, so the transfer can be retried later. */
    fun leave() {
        transfer?.stop()
        transfer = null
        app.backup.discardStaged()
        progress.value = null
        flow.value = Flow.Idle
        refreshPending()
    }

    /** The link may be still up; the kept chunks stay on disk for a retry. */
    override fun onCleared() {
        transfer?.stop()
    }

    private companion object {
        const val RESTORE_PREFS = "restore_state"
        const val RESTORE_ERROR = "last_error"
        const val RESTART_AFTER_FAILURE_MILLIS = 5_000L
    }
}

/** Counts the bytes read, so the size is taken from the same pass that computes the hash. */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

private fun qrBitmap(text: String): Bitmap {
    val size = 720
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

/** Nearby Connections needs these on Android 13+. All are declared neverForLocation. */
private val nearbyPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.NEARBY_WIFI_DEVICES,
)

@Composable
fun MigrationScreen() {
    val vm = appViewModel { MigrationViewModel(it) }
    Messages(vm)
    val context = LocalContext.current
    val resources = LocalResources.current
    val auth = rememberAuthenticator()
    val transferAuthTitle = stringResource(R.string.migration_transfer_auth_title)
    val createBackupTitle = stringResource(R.string.migration_create_backup)
    val flow by vm.flow.collectAsState()
    val progress by vm.progress.collectAsState()
    val backups by vm.backups.collectAsState()
    val unfinished by vm.pending.collectAsState()
    var password by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // What "Try again" repeats. A restore sets none: its password is gone, so Back to the start is the retry.
    var lastAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) pendingAction?.invoke()
        pendingAction = null
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.createBackup(it, password); password = "" }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { lastAction = null; vm.restore(it, password); password = "" }
    }
    fun withNearby(action: () -> Unit) {
        pendingAction = action
        permissions.launch(nearbyPermissions)
    }
    fun scan() {
        GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(vm::startReceiving) }
            .addOnFailureListener { vm.messages.tryEmit(resources.getString(R.string.migration_scanner_failed, it.message)) }
    }
    ScreenColumn {
        item {
            NexioCard {
                Text(
                    stringResource(R.string.migration_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nexio.colors.textMuted,
                )
            }
        }
        progress?.let { p ->
            item {
                NexioCard {
                    Text(p.label, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
                    Gap(4)
                    NexioProgressBar(if (p.total > 0) p.done.toFloat() / p.total else 0f)
                    Gap(4)
                    Text(
                        stringResource(R.string.migration_progress_of, Format.bytes(p.done), Format.bytes(p.total), Format.bytes((p.total - p.done).coerceAtLeast(0))) +
                            if (p.speed > 0) stringResource(R.string.migration_progress_speed, Format.bytes(p.speed)) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Nexio.colors.textMuted,
                    )
                    if (p.chunksTotal > 0) {
                        Text(
                            stringResource(R.string.migration_progress_chunks, p.chunksDone, p.chunksTotal),
                            style = MaterialTheme.typography.labelSmall,
                            color = Nexio.colors.textMuted,
                        )
                    }
                }
            }
        }
        when (val state = flow) {
            Flow.Idle -> {
                unfinished.forEach { kept -> item(key = kept.migrationId) {
                    NexioCard {
                        SectionHeader(stringResource(R.string.migration_unfinished_title))
                        Text(
                            if (kept.kind == ResumeKind.SEND) stringResource(R.string.migration_unfinished_send, Format.bytes(kept.totalSize))
                            else stringResource(R.string.migration_unfinished_receive, kept.chunksKept, kept.chunksTotal, Format.bytes(kept.totalSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Nexio.colors.textMuted,
                        )
                        Gap()
                        Button({
                            if (kept.kind == ResumeKind.SEND) auth.confirm(transferAuthTitle) { withNearby { vm.resumeSending() } }
                            else withNearby { scan() }
                        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_continue)) }
                        OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel_delete)) }
                    }
                } }
                item {
                    NexioCard {
                        SectionHeader(stringResource(R.string.migration_move_title))
                        Gap(4)
                        Button({
                            val send: () -> Unit = { auth.confirm(transferAuthTitle) { withNearby { vm.startSending() } } }
                            lastAction = send
                            send()
                        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_transfer_button)) }
                        Gap(4)
                        OutlinedButton({
                            // The sender's code works once, so a retry scans a code again.
                            val receive: () -> Unit = { withNearby { scan() } }
                            lastAction = receive
                            receive()
                        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_receive_button)) }
                        Gap(4)
                        Text(stringResource(R.string.migration_move_hint), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
                    }
                }
                item {
                    NexioCard {
                        SectionHeader(stringResource(R.string.migration_backup_title))
                        Gap(4)
                        Field(stringResource(R.string.migration_password_label), password, { password = it }, visual = PasswordVisualTransformation(), supporting = stringResource(R.string.migration_password_hint))
                        Gap(4)
                        Button({ auth.confirm(createBackupTitle) { createFile.launch("nexio-${java.time.LocalDate.now()}.nexiobak") } }, Modifier.fillMaxWidth(), enabled = password.length >= 10) { Text(createBackupTitle) }
                        Gap(4)
                        OutlinedButton({ openFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = password.isNotEmpty()) { Text(stringResource(R.string.migration_restore_button)) }
                    }
                }
                if (backups.isNotEmpty()) {
                    item {
                        NexioCard {
                            SectionHeader(stringResource(R.string.migration_backups_here))
                            backups.take(5).forEach {
                                Text("${Format.dateTime(it.createdAt)} · ${Format.bytes(it.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                            }
                        }
                    }
                }
            }
            is Flow.Preparing -> item { NexioCard { Text(state.label, color = Nexio.colors.text) } }
            is Flow.ShowCode -> item {
                NexioCard {
                    SectionHeader(stringResource(R.string.migration_scan_title))
                    Text(
                        if (state.resumed) stringResource(R.string.migration_code_summary_resumed, Format.bytes(state.bytes))
                        else stringResource(R.string.migration_code_summary, state.files, Format.bytes(state.bytes)),
                        style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    Gap()
                    val bitmap = remember(state.code) { qrBitmap(state.code.encode()) }
                    Image(bitmap.asImageBitmap(), stringResource(R.string.migration_code_description), Modifier.fillMaxWidth().size(280.dp).align(Alignment.CenterHorizontally))
                    Gap()
                    OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel)) }
                }
            }
            Flow.Searching -> item {
                NexioCard {
                    Text(stringResource(R.string.migration_searching), color = Nexio.colors.text)
                    OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel)) }
                }
            }
            is Flow.Confirm -> item {
                NexioCard {
                    SectionHeader(stringResource(R.string.migration_confirm_title))
                    Gap()
                    Text(state.digits, style = MaterialTheme.typography.headlineMedium, color = Nexio.colors.text)
                    Gap()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(vm::cancel, Modifier.weight(1f)) { Text(stringResource(R.string.migration_no_cancel)) }
                        Button(vm::confirmPairing, Modifier.weight(1f)) { Text(stringResource(R.string.migration_yes_match)) }
                    }
                }
            }
            is Flow.Offer -> item {
                NexioCard {
                    SectionHeader(stringResource(R.string.migration_receive_question, Format.bytes(state.bytes)))
                    Text(stringResource(R.string.migration_offer_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    if (state.keptChunks > 0) {
                        Gap(4)
                        Text(stringResource(R.string.migration_offer_resume, state.keptChunks, state.totalChunks), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text)
                    }
                    Gap()
                    Button(vm::acceptOffer, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_start_transfer)) }
                    OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel)) }
                }
            }
            Flow.SendDone -> item {
                NexioCard {
                    SectionHeader(stringResource(R.string.migration_sent))
                    Text(stringResource(R.string.migration_sent_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    TextButton(vm::cancel) { Text(stringResource(R.string.migration_done)) }
                }
            }
            is Flow.Review -> item { ReviewCard(state.staged, vm, context, auth) }
            is Flow.Failed -> item {
                NexioCard {
                    Text(stringResource(R.string.migration_failed_title), style = MaterialTheme.typography.titleSmall, color = Brand.Danger)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.text)
                    Gap(4)
                    Text(stringResource(R.string.migration_failed_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    if (state.resume != null) {
                        Gap(4)
                        Text(stringResource(R.string.migration_resume_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Back keeps the finished chunks; only Cancel and delete removes them.
                        TextButton(vm::leave) { Text(stringResource(R.string.migration_back)) }
                        when (state.resume) {
                            ResumeKind.SEND -> Button({ auth.confirm(transferAuthTitle) { withNearby { vm.resumeSending() } } }) { Text(stringResource(R.string.migration_retry)) }
                            ResumeKind.RECEIVE -> Button({ withNearby { scan() } }) { Text(stringResource(R.string.migration_retry)) }
                            null -> lastAction?.let { again ->
                                Button({
                                    vm.leave()
                                    again()
                                }) { Text(stringResource(R.string.migration_try_again)) }
                            }
                        }
                    }
                    if (state.resume != null) {
                        OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(staged: StagedRestore, vm: MigrationViewModel, context: android.content.Context, auth: Authenticator) {
    var confirming by remember { mutableStateOf(false) }
    val replaceTitle = stringResource(R.string.migration_replace_data)
    NexioCard {
        SectionHeader(stringResource(R.string.migration_verified))
        Text(
            stringResource(R.string.migration_verified_files, staged.manifest.files.size) + "\n" +
                stringResource(R.string.migration_verified_counts, staged.posts, staged.mediaFiles, staged.providers) + "\n" +
                stringResource(R.string.migration_verified_size, Format.bytes(staged.manifest.totalBytes), Format.dateTime(staged.manifest.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = Nexio.colors.text,
        )
        if (staged.missingMedia > 0) {
            Gap(4)
            Text(stringResource(R.string.migration_missing_media, staged.missingMedia), style = MaterialTheme.typography.bodySmall, color = Brand.Warning)
        }
        Gap(4)
        Text(stringResource(R.string.migration_review_note), style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
        Gap()
        Button({ confirming = true }, Modifier.fillMaxWidth()) { Text(replaceTitle) }
        OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.migration_cancel_keep)) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.migration_replace_dialog_title)) },
            text = { Text(stringResource(R.string.migration_replace_dialog_text)) },
            confirmButton = {
                TextButton({
                    confirming = false
                    auth.confirm(replaceTitle) {
                        vm.commit {
                            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            context.startActivity(launch)
                            Runtime.getRuntime().exit(0)
                        }
                    }
                }) { Text(stringResource(R.string.migration_replace)) }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.migration_cancel)) } },
        )
    }
}
