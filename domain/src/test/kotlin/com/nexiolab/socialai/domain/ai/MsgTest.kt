package com.nexiolab.socialai.domain.ai

import com.nexiolab.socialai.domain.i18n.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsgTest {
    // Matches %s, %d, %.2f, %.0f and similar; "%%" is a literal percent and is removed first.
    private val specifier = Regex("%[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")

    private fun specifiersOf(text: String) = specifier.findAll(text.replace("%%", "")).map { it.value }.toList()

    @Test fun everyMessageHasBothLanguages() {
        for (msg in Msg.entries) {
            assertTrue("${msg.name} has a blank English text", msg.en.isNotBlank())
            assertTrue("${msg.name} has a blank Russian text", msg.ru.isNotBlank())
        }
    }

    @Test fun everyMessageUsesTheSameSpecifiersInBothLanguages() {
        for (msg in Msg.entries) {
            assertEquals("${msg.name} specifiers differ between en and ru", specifiersOf(msg.en), specifiersOf(msg.ru))
        }
    }

    @Test fun noMessageHasAStrayPercentSign() {
        for (msg in Msg.entries) {
            for (text in listOf(msg.en, msg.ru)) {
                val rest = specifier.replace(text.replace("%%", ""), "")
                assertTrue("${msg.name} has an unescaped percent sign: $text", '%' !in rest)
            }
        }
    }

    @Test fun russianLocaleSelectsRussianText() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"))
            assertEquals(Msg.SOMETHING_WENT_WRONG.ru, Msg.SOMETHING_WENT_WRONG())
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals(Msg.SOMETHING_WENT_WRONG.en, Msg.SOMETHING_WENT_WRONG())
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
