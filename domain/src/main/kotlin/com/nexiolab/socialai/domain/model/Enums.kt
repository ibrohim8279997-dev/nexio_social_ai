package com.nexiolab.socialai.domain.model

import com.nexiolab.socialai.domain.i18n.localized

enum class AgentRole(
    val titleEn: String,
    private val titleRu: String,
    private val summaryEn: String,
    private val summaryRu: String,
) {
    MAIN(
        "Main Agent", "Главный агент",
        "Coordinates the specialists, checks results and asks for your approval.",
        "Координирует специалистов, проверяет результаты и просит вашего одобрения.",
    ),
    MARKETING(
        "Marketing Agent", "Агент маркетинга",
        "Business analysis, strategy, audience and competitors.",
        "Анализ бизнеса, стратегия, аудитория и конкуренты.",
    ),
    CONTENT(
        "Content Agent", "Агент контента",
        "Posts, captions, hashtags, scripts and content calendars.",
        "Посты, подписи, хештеги, сценарии и контент-календари.",
    ),
    VISUAL(
        "Visual Agent", "Визуальный агент",
        "Image concepts, visual direction and image prompts.",
        "Концепции изображений, визуальное направление и запросы для создания изображений.",
    ),
    VIDEO(
        "Video Agent", "Видеоагент",
        "Video scripts, storyboards and scene planning.",
        "Сценарии видео, раскадровки и планирование сцен.",
    ),
    ANALYTICS(
        "Analytics Agent", "Агент аналитики",
        "Performance, growth and expense analysis.",
        "Анализ результатов, роста и расходов.",
    ),
    QUALITY_CONTROL(
        "Quality Control Agent", "Агент контроля качества",
        "Independent review. Can reject work and return it for correction.",
        "Независимая проверка. Может отклонить работу и вернуть её на доработку.",
    ),
    ;

    val title: String get() = localized(titleEn, titleRu)
    val summary: String get() = localized(summaryEn, summaryRu)

    companion object {
        val specialists = entries.filter { it != MAIN }
    }
}

enum class ProviderType(private val labelEn: String, val defaultBaseUrl: String, private val labelRu: String = labelEn) {
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com"),
    OPENAI("OpenAI", "https://api.openai.com"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com"),
    GROQ("Groq", "https://api.groq.com/openai"),
    OPENAI_COMPATIBLE("OpenAI-compatible", "", "Совместимый с OpenAI"),
    ;

    val label: String get() = localized(labelEn, labelRu)
}

enum class TaskStatus { QUEUED, RUNNING, WAITING_APPROVAL, NEEDS_CORRECTION, COMPLETED, FAILED, CANCELLED }

enum class ContentStatus(private val labelEn: String, private val labelRu: String) {
    DRAFT("Draft", "Черновик"),
    REVIEW("Review", "На проверке"),
    APPROVED("Approved", "Одобрено"),
    SCHEDULED("Scheduled", "Запланировано"),
    PUBLISHING("Publishing", "Публикуется"),
    PUBLISHED("Published", "Опубликовано"),
    FAILED("Failed", "Ошибка"),
    ;

    val label: String get() = localized(labelEn, labelRu)
}

/** [labelEn] stays public so prompts sent to agents keep their English wording. */
enum class ContentType(val labelEn: String, private val labelRu: String) {
    IMAGE_POST("Image post", "Пост с изображением"),
    CAROUSEL("Carousel", "Карусель"),
    REEL("Reel", "Reels"),
    STORY("Story", "Сторис"),
    ;

    val label: String get() = localized(labelEn, labelRu)
}

enum class MediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

enum class MediaSource { UPLOADED, AI_GENERATED, RENDERED }

enum class VideoMode { FULL_AI, PHOTO_BASED }

enum class VideoStatus { DRAFT, CONCEPT_READY, APPROVED, GENERATING, RENDERING, QC_REVIEW, READY, FAILED }

enum class VideoTransition { NONE, FADE, ZOOM }

/**
 * Publication states. [PUBLISH_UNCERTAIN] exists so an ambiguous network failure on the final
 * publish call can never lead to a blind retry and a duplicate post.
 */
enum class PublishState {
    PENDING, MEDIA_UPLOADED, CONTAINER_CREATED, READY_TO_PUBLISH, PUBLISH_UNCERTAIN, PUBLISHED, FAILED,
}

/**
 * How far an expense figure can be trusted.
 * MEASURED: provider-reported usage multiplied by the price stored at request time.
 * ESTIMATED: usage or price was inferred. Never shown as verified.
 * UNPRICED: usage is known but no price was configured, so no amount can be stated.
 */
enum class CostConfidence { MEASURED, ESTIMATED, UNPRICED }

enum class RequestType { TEXT, VISION, IMAGE, VIDEO, SPEECH, CONNECTION_TEST }

enum class NoticeSeverity { INFO, WARNING, ERROR }

enum class MigrationRole { SOURCE, DESTINATION }

enum class MigrationStatus { PREPARING, WAITING_FOR_PEER, CONFIRMING, TRANSFERRING, VERIFYING, COMPLETED, FAILED, CANCELLED }
