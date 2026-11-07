package com.nexiolab.socialai.data.video

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.nexiolab.socialai.domain.ai.AiError
import com.nexiolab.socialai.domain.ai.AiException
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.VideoTransition
import com.nexiolab.socialai.domain.video.RenderSpec
import com.nexiolab.socialai.domain.video.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Photo slideshows built with Media3 Transformer (Apache 2.0). FFmpeg was avoided: ffmpeg-kit is
 * retired, and FFmpeg builds carry LGPL or GPL distribution duties.
 *
 * The motion effects sit on the Composition rather than on each photo, so they see one continuous
 * timeline and can work out each photo's position from it.
 */
@OptIn(UnstableApi::class)
class Media3VideoRenderer(private val context: Context) : VideoRenderer {

    override suspend fun render(spec: RenderSpec, onProgress: (Int) -> Unit): String = withContext(Dispatchers.Main) {
        require(spec.photos.isNotEmpty()) { Msg.VIDEO_NEEDS_PHOTO() }
        val output = File(context.cacheDir, "render-${UUID.randomUUID()}.mp4")
        val composition = composition(spec)
        val handler = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine { continuation ->
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setPortraitEncodingEnabled(true)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (continuation.isActive) continuation.resume(output.path)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            output.delete()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    AiException(AiError.SERVER, Msg.VIDEO_EXPORT_FAILED(exportException.errorCodeName)),
                                )
                            }
                        }
                    },
                )
                .build()
            continuation.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    output.delete()
                }
            }
            transformer.start(composition, output.path)
            handler.post(poll)
        }
    }

    private fun composition(spec: RenderSpec): Composition {
        val perPhotoMs = spec.durationSeconds * 1000L / spec.photos.size
        val scale = Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val photos = spec.photos.map { path ->
            val item = MediaItem.Builder().setUri(Uri.fromFile(File(path))).setImageDurationMs(perPhotoMs).build()
            EditedMediaItem.Builder(item)
                .setFrameRate(spec.frameRate)
                .setEffects(Effects(emptyList(), listOf(scale)))
                .build()
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO)).addItems(photos).build()
        spec.voiceoverPath?.let { sequences += audioSequence(it, gain = 1f, looping = false) }
        spec.musicPath?.let { sequences += audioSequence(it, gain = if (spec.voiceoverPath != null) MUSIC_UNDER_VOICE else 1f, looping = true) }

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), globalEffects(spec, perPhotoMs * 1000)))
            .build()
    }

    private fun audioSequence(path: String, gain: Float, looping: Boolean): EditedMediaItemSequence {
        val processors = ArrayList<AudioProcessor>()
        if (gain != 1f) {
            processors += ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(gain))
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(gain))
            }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(path))))
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
        return EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addItem(item)
            .setIsLooping(looping)
            .build()
    }

    private fun globalEffects(spec: RenderSpec, segmentUs: Long): List<androidx.media3.common.Effect> {
        val effects = ArrayList<androidx.media3.common.Effect>()
        when (spec.transition) {
            VideoTransition.NONE -> Unit
            VideoTransition.FADE -> effects += FadeThroughBlack(segmentUs)
            VideoTransition.ZOOM -> {
                effects += SlowZoom(segmentUs)
                effects += FadeThroughBlack(segmentUs)
            }
        }
        if (spec.caption.isNotBlank()) effects += captionOverlay(spec.caption, spec.height)
        return effects
    }

    private fun captionOverlay(caption: String, height: Int): OverlayEffect {
        val text = " ${caption.trim()} "
        val styled = SpannableString(text).apply {
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, flags)
            setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, text.length, flags)
            setSpan(AbsoluteSizeSpan(height / CAPTION_HEIGHT_DIVISOR), 0, text.length, flags)
            setSpan(StyleSpan(Typeface.BOLD), 0, text.length, flags)
        }
        val placement = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, CAPTION_POSITION).build()
        return OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(styled, placement)))
    }

    /** Brightness dips to black at each photo boundary. */
    private class FadeThroughBlack(private val segmentUs: Long) : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val position = presentationTimeUs % segmentUs
            val edge = minOf(position, segmentUs - position).toFloat()
            val level = (edge / minOf(FADE_US, segmentUs / 2f)).coerceIn(0f, 1f)
            return floatArrayOf(
                level, 0f, 0f, 0f,
                0f, level, 0f, 0f,
                0f, 0f, level, 0f,
                0f, 0f, 0f, 1f,
            )
        }
    }

    private class SlowZoom(private val segmentUs: Long) : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val progress = (presentationTimeUs % segmentUs).toFloat() / segmentUs
            val zoom = 1f + ZOOM_RANGE * progress
            return Matrix().apply { setScale(zoom, zoom) }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
        const val FADE_US = 400_000f
        const val ZOOM_RANGE = 0.12f
        const val MUSIC_UNDER_VOICE = 0.25f
        const val CAPTION_HEIGHT_DIVISOR = 26
        const val CAPTION_POSITION = -0.72f
    }
}
