package com.nexiolab.socialai.domain.instagram

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What has been done for one image of a carousel. Stored on the publication so a restart resumes. */
@Serializable
data class CarouselChild(
    val position: Int,
    val mediaId: Long,
    val hostedUrl: String? = null,
    /** The Instagram child container for this image. */
    val containerId: String? = null,
    val finished: Boolean = false,
)

object CarouselProgress {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CarouselChild.serializer())

    fun decode(text: String?): List<CarouselChild> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(children: List<CarouselChild>): String = json.encodeToString(serializer, children)
}
