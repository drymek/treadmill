package com.craftersy.treadmill.workout

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/** The app's language, chosen in the settings. */
enum class Language(val tag: String?) {
    /** Follows the phone, including Android's per-app language setting. */
    SYSTEM(null),
    ENGLISH("en"),
    POLISH("pl"),
}

/**
 * Applies the language chosen in the settings. Activities and the service wrap
 * their base context with [wrap]; code without one (the view model) wraps the
 * application context before reading strings.
 */
object AppLocale {
    private const val PREFS = "app"
    private const val KEY = "language"

    fun get(context: Context): Language =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { name -> Language.entries.firstOrNull { it.name == name } } ?: Language.SYSTEM

    fun set(context: Context, language: Language) {
        // Committed at once: the activity is recreated right after and reads it back.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, language.name) }
    }

    /** [base] with the chosen language, or [base] itself when the app follows the system. */
    fun wrap(base: Context): Context {
        val tag = get(base).tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }
}
