package com.kitsuneandroid

import org.json.JSONArray
import org.json.JSONObject

internal fun String?.trimmedOrNull(): String? {
    val text = this?.trim() ?: return null
    return text.ifEmpty { null }
}

internal fun JSONObject.stringOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).trimmedOrNull()
}

internal fun JSONArray?.strings(): List<String> {
    val array = this ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trimmedOrNull()?.let(::add)
        }
    }
}
