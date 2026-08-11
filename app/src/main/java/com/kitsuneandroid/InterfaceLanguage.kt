package com.kitsuneandroid

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

private const val PREFERENCES = "kitsune"
private const val INTERFACE_LANGUAGE = "interface_language"

internal enum class InterfaceLanguage(val languageTag: String?) {
    SYSTEM(null),
    PORTUGUESE("pt-BR"),
    ENGLISH("en")
}

internal fun parseInterfaceLanguage(value: String?): InterfaceLanguage {
    return InterfaceLanguage.entries.firstOrNull { language -> language.name == value }
        ?: InterfaceLanguage.PORTUGUESE
}

internal fun loadInterfaceLanguage(context: Context): InterfaceLanguage {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(INTERFACE_LANGUAGE, null)
    return parseInterfaceLanguage(value)
}

internal fun saveInterfaceLanguage(context: Context, language: InterfaceLanguage) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(INTERFACE_LANGUAGE, language.name)
        .apply()
}

internal fun Context.withInterfaceLanguage(): Context {
    val languageTag = loadInterfaceLanguage(this).languageTag ?: return this
    val locale = Locale.forLanguageTag(languageTag)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
