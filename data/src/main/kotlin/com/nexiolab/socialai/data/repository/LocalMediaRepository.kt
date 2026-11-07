package com.nexiolab.socialai.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.nexiolab.socialai.data.db.MediaDao
import com.nexiolab.socialai.domain.Hashing
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.domain.model.MediaSource
import com.nexiolab.socialai.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Media lives in private internal storage under filesDir/media; Room only keeps the reference. */
class LocalMediaRepository(private val context: Context, private val dao: MediaDao) : MediaRepository {
    val root: File get() = File(context.filesDir, "media").apply { mkdirs() }

    override fun observeAll() = dao.observeAll()
    override suspend fun byId(id: Long) = dao.byId(id)
    override fun absolutePath(media: MediaFile) = File(root, media.relativePath).path

    override suspend fun import(uri: String, source: MediaSource): MediaFile = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        val mime = context.contentResolver.getType(parsed) ?: "application/octet-stream"
        val name = displayName(parsed)
        val temp = File(root, "import-${UUID.randomUUID()}.tmp")
        try {
            val input = context.contentResolver.openInputStream(parsed) ?: error(Msg.FILE_OPEN_FAILED())
            input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }
            adopt(temp, kindOf(mime), mime, name, source)
        } finally {
            temp.delete()
        }
    }

    override suspend fun store(bytes: ByteArray, kind: MediaKind, mimeType: String, name: String, source: MediaSource): MediaFile =
        withContext(Dispatchers.IO) {
            val temp = File(root, "store-${UUID.randomUUID()}.tmp")
            try {
                temp.writeBytes(bytes)
                adopt(temp, kind, mimeType, name, source)
            } finally {
                temp.delete()
            }
        }

    override suspend fun registerFile(path: String, kind: MediaKind, mimeType: String, name: String, source: MediaSource): MediaFile =
        withContext(Dispatchers.IO) {
            val temp = File(path)
            try {
                adopt(temp, kind, mimeType, name, source)
            } finally {
                temp.delete()
            }
        }

    private suspend fun adopt(temp: File, kind: MediaKind, mime: String, name: String, source: MediaSource): MediaFile {
        val sha = temp.inputStream().use(Hashing::sha256)
        dao.bySha(sha)?.let { return it }
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: mime.substringAfter('/').take(5).filter(Char::isLetterOrDigit)
        val relative = "${kind.name.lowercase()}/${UUID.randomUUID()}.$ext"
        val target = File(root, relative).apply { parentFile?.mkdirs() }
        temp.copyTo(target)
        val (width, height) = dimensions(target, kind)
        val row = MediaFile(
            kind = kind,
            relativePath = relative,
            displayName = name,
            mimeType = mime,
            sizeBytes = target.length(),
            sha256 = sha,
            width = width,
            height = height,
            durationMs = duration(target, kind),
            source = source,
            createdAt = System.currentTimeMillis(),
        )
        return row.copy(id = dao.insert(row))
    }

    override suspend fun delete(media: MediaFile) {
        dao.delete(media)
        withContext(Dispatchers.IO) { File(root, media.relativePath).delete() }
    }

    private fun kindOf(mime: String) = when {
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.DOCUMENT
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private fun dimensions(file: File, kind: MediaKind): Pair<Int?, Int?> {
        if (kind != MediaKind.IMAGE) return null to null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
    }

    private fun duration(file: File, kind: MediaKind): Long? {
        if (kind != MediaKind.VIDEO && kind != MediaKind.AUDIO) return null
        return runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.path)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()
    }
}
