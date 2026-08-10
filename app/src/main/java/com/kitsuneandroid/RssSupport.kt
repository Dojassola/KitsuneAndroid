package com.kitsuneandroid

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

internal fun fetchRss(
    url: String,
    providerName: String,
    readTimeoutMillis: Int = 10_000
): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = readTimeoutMillis
    connection.setRequestProperty("Accept", "application/rss+xml, application/xml")
    connection.setRequestProperty("User-Agent", "KitsuneAndroid/1.2")

    return try {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (statusCode !in 200..299) throw IOException("$providerName HTTP $statusCode")
        if (response.length > MAX_RSS_CHARACTERS) {
            throw IOException("A resposta do provedor é grande demais.")
        }
        response
    } finally {
        connection.disconnect()
    }
}

internal fun parseRssItems(xml: String): List<Element> {
    require(!UNSAFE_XML_PATTERN.containsMatchIn(xml)) { "RSS inválido." }
    val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val items = document.getElementsByTagName("item")
    return List(items.length) { index -> items.item(index) }.filterIsInstance<Element>()
}

internal fun Element.text(name: String): String {
    val children = childNodes
    repeat(children.length) { index ->
        val node = children.item(index)
        val nodeName = node.localName ?: node.nodeName.substringAfter(':')
        if (nodeName == name) return node.textContent.trim()
    }
    return ""
}

internal fun sizeToBytes(value: String): Long {
    val match = SIZE_PATTERN.find(value) ?: return 0
    val unit = match.groupValues[2].lowercase()
    val power = SIZE_UNITS.indexOf(unit.replace("i", "")).coerceAtLeast(0)
    val base = if ('i' in unit) 1024.0 else 1000.0
    val amount = match.groupValues[1].toDoubleOrNull() ?: return 0
    return (amount * Math.pow(base, power.toDouble())).toLong()
}

private const val MAX_RSS_CHARACTERS = 2_000_000
private val UNSAFE_XML_PATTERN = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
private val SIZE_PATTERN = Regex(
    "^([\\d.]+)\\s*(KiB|MiB|GiB|TiB|KB|MB|GB|TB|B)$",
    RegexOption.IGNORE_CASE
)
private val SIZE_UNITS = listOf("b", "kb", "mb", "gb", "tb")
