package com.nexiolab.socialai.ui.common

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * The app's own language, separate from the phone's. Android stores the choice, restarts the screen
 * and also shows it under the phone's per-app language setting.
 */
object AppLanguage {
    const val SYSTEM = ""
    val options = listOf("en" to "English", "ru" to "Русский")

    private fun manager(context: Context) = context.getSystemService(LocaleManager::class.java)

    fun current(context: Context): String = manager(context).applicationLocales.toLanguageTags()

    fun set(context: Context, tag: String) {
        manager(context).applicationLocales = if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
