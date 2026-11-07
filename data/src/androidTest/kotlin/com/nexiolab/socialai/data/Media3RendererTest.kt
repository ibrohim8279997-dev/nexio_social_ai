package com.nexiolab.socialai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexiolab.socialai.data.video.Media3VideoRenderer
import com.nexiolab.socialai.domain.model.VideoTransition
import com.nexiolab.socialai.domain.video.RenderSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File

/** Runs the real encoder on a device or emulator. Nothing here is mocked. */
@RunWith(AndroidJUnit4::class)
class Media3RendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(name: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        val file = File(context.cacheDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file.path
    }

    /** Three seconds of 16-bit PCM in a WAV container, so no audio asset has to ship with the test. */
    private fun tone(name: String): String {
        val file = File(context.cacheDir, "$name.wav")
        val rate = 44_100
        val samples = rate * 3
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF"); int(36 + samples * 2); out.writeBytes("WAVEfmt "); int(16); short(1); short(1)
            int(rate); int(rate * 2); short(2); short(16); out.writeBytes("data"); int(samples * 2)
            repeat(samples) { i -> short((Math.sin(i * 2 * Math.PI * 440 / rate) * 8000).toInt()) }
        }
        return file.path
    }

    private fun metadata(path: String): MediaMetadataRetriever = MediaMetadataRetriever().apply { setDataSource(path) }

    private fun tracks(path: String): List<String> {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        extractor.release()
        return mimes
    }

    private fun spec(transition: VideoTransition, caption: String = "", music: String? = null) = RenderSpec(
        photos = listOf(photo("a", Color.RED), photo("b", Color.GREEN), photo("c", Color.BLUE)),
        width = 720, height = 1280, frameRate = 30, durationSeconds = 6,
        transition = transition, caption = caption, voiceoverPath = null, musicPath = music,
    )

    @Test fun rendersRequestedSizeAndDuration() = runBlocking {
        val path = Media3VideoRenderer(context).render(spec(VideoTransition.NONE))
        val data = metadata(path)
        assertEquals("720", data.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        assertEquals("1280", data.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        val ms = data.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("duration was $ms", ms in 5_500..6_500)
        data.release()
    }

    @Test fun photosAppearInOrder() = runBlocking {
        val path = Media3VideoRenderer(context).render(spec(VideoTransition.NONE))
        val data = metadata(path)
        fun at(seconds: Double) = data.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(360, 640)
        assertTrue(Color.red(at(1.0)) > 200)
        assertTrue(Color.green(at(3.0)) > 200)
        assertTrue(Color.blue(at(5.0)) > 200)
        data.release()
    }

    @Test fun fadeDarkensPhotoBoundaries() = runBlocking {
        val path = Media3VideoRenderer(context).render(spec(VideoTransition.FADE))
        val data = metadata(path)
        val middle = data.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(360, 640)
        val boundary = data.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(360, 640)
        val brightness = { c: Int -> Color.red(c) + Color.green(c) + Color.blue(c) }
        assertTrue("middle=${brightness(middle)} boundary=${brightness(boundary)}", brightness(boundary) < brightness(middle) / 3)
        data.release()
    }

    @Test fun zoomAndCaptionRenderWithoutError() = runBlocking {
        val path = Media3VideoRenderer(context).render(spec(VideoTransition.ZOOM, caption = "Yangi kofe"))
        assertTrue(File(path).length() > 0)
    }

    @Test fun musicProducesAudioTrackThatLastsTheWholeVideo() = runBlocking {
        val path = Media3VideoRenderer(context).render(spec(VideoTransition.NONE, music = tone("tone")))
        val mimes = tracks(path)
        assertTrue(mimes.toString(), mimes.any { it.startsWith("audio/") })
        val data = metadata(path)
        val ms = data.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("duration was $ms", ms in 5_500..6_500)
        data.release()
    }
}
