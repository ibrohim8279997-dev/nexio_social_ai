package com.nexiolab.socialai.data.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nexiolab.socialai.domain.ai.ImageInput
import com.nexiolab.socialai.domain.i18n.Msg
import java.io.ByteArrayOutputStream

/** Phone photos are far larger than provider limits, and agents do not need the full resolution. */
object AnalysisImages {
    private const val MAX_SIDE = 1568
    private const val QUALITY = 85

    fun load(path: String): ImageInput {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException(Msg.PHOTO_UNREADABLE())
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return ImageInput("image/jpeg", out.toByteArray())
    }
}
