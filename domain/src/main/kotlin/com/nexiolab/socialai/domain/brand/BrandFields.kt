package com.nexiolab.socialai.domain.brand

import com.nexiolab.socialai.domain.i18n.localized
import com.nexiolab.socialai.domain.model.BrandProfile

enum class BrandSection(private val titleEn: String, private val titleRu: String) {
    BUSINESS("Business", "Бизнес"),
    AUDIENCE("Audience", "Аудитория"),
    BRAND("Brand identity", "Фирменный стиль"),
    MARKETING("Marketing", "Маркетинг"),
    CONTENT("Content", "Контент"),
    ;

    val title: String get() = localized(titleEn, titleRu)
}

/**
 * One business fact. The interview, the Brand Profile form and the agents' context all read this list.
 * [id] is stable and never translated; [section], [label] and [question] follow the app language.
 */
class BrandField(
    val id: String,
    private val sectionKey: BrandSection,
    val labelEn: String,
    private val labelRu: String,
    private val questionEn: String,
    private val questionRu: String,
    val read: (BrandProfile) -> String,
    val write: (BrandProfile, String) -> BrandProfile,
    val required: Boolean = false,
    val singleLine: Boolean = false,
) {
    val section: String get() = sectionKey.title
    val label: String get() = localized(labelEn, labelRu)
    val question: String get() = localized(questionEn, questionRu)
}

object BrandFields {
    private val BUSINESS = BrandSection.BUSINESS
    private val AUDIENCE = BrandSection.AUDIENCE
    private val BRAND = BrandSection.BRAND
    private val MARKETING = BrandSection.MARKETING
    private val CONTENT = BrandSection.CONTENT

    val all = listOf(
        BrandField(
            "name", BUSINESS, "Business name", "Название бизнеса",
            "What is your business called?", "Как называется ваш бизнес?",
            { it.businessName }, { p, v -> p.copy(businessName = v) }, required = true, singleLine = true,
        ),
        BrandField(
            "category", BUSINESS, "Business category", "Категория бизнеса",
            "What category is your business in?", "К какой категории относится ваш бизнес?",
            { it.category }, { p, v -> p.copy(category = v) }, required = true, singleLine = true,
        ),
        BrandField(
            "description", BUSINESS, "Business description", "Описание бизнеса",
            "Describe your business in a few sentences.", "Опишите ваш бизнес в нескольких предложениях.",
            { it.description }, { p, v -> p.copy(description = v) }, required = true,
        ),
        BrandField(
            "products", BUSINESS, "Products", "Товары",
            "Which products do you sell?", "Какие товары вы продаёте?",
            { it.products }, { p, v -> p.copy(products = v) },
        ),
        BrandField(
            "services", BUSINESS, "Services", "Услуги",
            "Which services do you offer?", "Какие услуги вы предлагаете?",
            { it.services }, { p, v -> p.copy(services = v) },
        ),
        BrandField(
            "audience", AUDIENCE, "Target audience", "Целевая аудитория",
            "Who are your ideal customers?", "Кто ваши идеальные клиенты?",
            { it.targetAudience }, { p, v -> p.copy(targetAudience = v) }, required = true,
        ),
        BrandField(
            "demographics", AUDIENCE, "Customer demographics", "Портрет клиентов",
            "Any useful demographics, such as age range or city? Leave empty if not relevant.",
            "Есть полезные данные о клиентах, например возраст или город? Если это не важно, оставьте поле пустым.",
            { it.demographics }, { p, v -> p.copy(demographics = v) },
        ),
        BrandField(
            "identity", BRAND, "Brand identity", "Фирменный стиль",
            "What should people feel and remember about your brand?", "Что люди должны чувствовать и запоминать о вашем бренде?",
            { it.brandIdentity }, { p, v -> p.copy(brandIdentity = v) },
        ),
        BrandField(
            "colors", BRAND, "Brand colors", "Цвета бренда",
            "What are your brand colors? Hex codes are best.", "Какие у вашего бренда цвета? Лучше всего указать HEX-коды.",
            { it.brandColors }, { p, v -> p.copy(brandColors = v) }, singleLine = true,
        ),
        BrandField(
            "fonts", BRAND, "Fonts", "Шрифты",
            "Which fonts does your brand use?", "Какие шрифты использует ваш бренд?",
            { it.fonts }, { p, v -> p.copy(fonts = v) }, singleLine = true,
        ),
        BrandField(
            "voice", BRAND, "Communication style", "Стиль общения",
            "How should your brand sound? For example warm, expert, playful.",
            "Как должен звучать ваш бренд? Например, тепло, экспертно, с юмором.",
            { it.brandVoice }, { p, v -> p.copy(brandVoice = v) }, required = true,
        ),
        BrandField(
            "languages", BRAND, "Preferred languages", "Предпочтительные языки",
            "Which languages should content use?", "На каких языках должен быть контент?",
            { it.languages }, { p, v -> p.copy(languages = v) }, required = true, singleLine = true,
        ),
        BrandField(
            "goals", MARKETING, "Marketing objectives", "Маркетинговые цели",
            "What should marketing achieve in the next few months?", "Чего должен достичь маркетинг в ближайшие месяцы?",
            { it.marketingGoals }, { p, v -> p.copy(marketingGoals = v) }, required = true,
        ),
        BrandField(
            "competitors", MARKETING, "Competitors", "Конкуренты",
            "Who are your main competitors?", "Кто ваши основные конкуренты?",
            { it.competitors }, { p, v -> p.copy(competitors = v) },
        ),
        BrandField(
            "budget", MARKETING, "Monthly marketing budget", "Месячный маркетинговый бюджет",
            "What is your monthly marketing budget?", "Каков ваш месячный бюджет на маркетинг?",
            { it.monthlyMarketingBudget }, { p, v -> p.copy(monthlyMarketingBudget = v) }, singleLine = true,
        ),
        BrandField(
            "platforms", CONTENT, "Social media platforms", "Социальные сети",
            "Where do you publish today?", "Где вы публикуете контент сейчас?",
            { it.platforms }, { p, v -> p.copy(platforms = v) }, singleLine = true,
        ),
        BrandField(
            "frequency", CONTENT, "Content frequency", "Частота публикаций",
            "How often do you want to post?", "Как часто вы хотите публиковать посты?",
            { it.contentFrequency }, { p, v -> p.copy(contentFrequency = v) }, required = true, singleLine = true,
        ),
        BrandField(
            "formats", CONTENT, "Content formats", "Форматы контента",
            "Which formats do you want? For example reels, carousels, image posts.",
            "Какие форматы вам нужны? Например, reels, карусели, посты с изображениями.",
            { it.contentFormats }, { p, v -> p.copy(contentFormats = v) }, singleLine = true,
        ),
        BrandField(
            "voiceover", CONTENT, "Voiceover preferences", "Пожелания к озвучке",
            "What should voiceovers sound like, if you use them?", "Как должна звучать озвучка, если вы её используете?",
            { it.voiceoverPreferences }, { p, v -> p.copy(voiceoverPreferences = v) },
        ),
        BrandField(
            "visual", CONTENT, "Visual preferences", "Визуальные предпочтения",
            "What visual style do you prefer?", "Какой визуальный стиль вам ближе?",
            { it.visualPreferences }, { p, v -> p.copy(visualPreferences = v) },
        ),
    )

    val sections: List<String> get() = all.map { it.section }.distinct()

    /** Business facts for an agent's system prompt; only answered fields, so nothing is implied. */
    fun context(profile: BrandProfile): String = all
        .mapNotNull { f -> f.read(profile).trim().takeIf(String::isNotEmpty)?.let { "${f.labelEn}: $it" } }
        .joinToString("\n")

    fun missingRequired(profile: BrandProfile): List<BrandField> =
        all.filter { it.required && it.read(profile).isBlank() }
}
