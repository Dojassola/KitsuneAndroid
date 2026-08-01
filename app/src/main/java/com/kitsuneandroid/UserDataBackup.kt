package com.kitsuneandroid

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties

private const val PREFS_NAME = "kitsune"
private const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

object UserDataBackup {
    fun export(context: Context, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Não foi possível abrir o arquivo de backup.")
        output.use { BackupCodec.write(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all, it) }
    }

    fun restore(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Não foi possível abrir o arquivo de backup.")
        val values = input.use { BackupCodec.read(it) }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Tipo de dado não suportado no backup.")
            }
        }
        check(editor.commit()) { "Não foi possível restaurar os dados." }
    }
}

internal object BackupCodec {
    private const val FORMAT = "kitsune-user-data"
    private const val VERSION = "1"
    private const val ENTRY = "entry."
    private val charset = StandardCharsets.UTF_8.name()

    fun write(values: Map<String, *>, output: OutputStream) {
        val properties = Properties().apply {
            setProperty("format", FORMAT)
            setProperty("version", VERSION)
            values.forEach { (key, value) -> setProperty(ENTRY + encode(key), encodeValue(value)) }
        }
        properties.store(output, "Kitsune user data backup")
    }

    fun read(input: InputStream): Map<String, Any> {
        val bytes = readBounded(input)
        val properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
        require(properties.getProperty("format") == FORMAT && properties.getProperty("version") == VERSION) {
            "Este arquivo não é um backup compatível do Kitsune."
        }
        val result = linkedMapOf<String, Any>()
        properties.stringPropertyNames().filter { it.startsWith(ENTRY) }.forEach { storedKey ->
            val key = decode(storedKey.removePrefix(ENTRY))
            require(key !in result) { "Backup contém chaves duplicadas." }
            result[key] = decodeValue(properties.getProperty(storedKey))
        }
        return result
    }

    private fun encodeValue(value: Any?): String = when (value) {
        is String -> "s:${encode(value)}"
        is Set<*> -> "ss:${value.map {
            require(it is String) { "Conjunto inválido nas preferências." }
            encode(it)
        }.sorted().joinToString(",")}"
        is Int -> "i:$value"
        is Long -> "l:$value"
        is Float -> "f:$value"
        is Boolean -> "b:$value"
        else -> error("Tipo de preferência não suportado: ${value?.javaClass?.simpleName ?: "null"}")
    }

    private fun decodeValue(value: String): Any {
        val separator = value.indexOf(':')
        require(separator > 0) { "Entrada inválida no backup." }
        val type = value.substring(0, separator)
        val content = value.substring(separator + 1)
        return when (type) {
            "s" -> decode(content)
            "ss" -> if (content.isEmpty()) emptySet<String>() else content.split(',').map(::decode).toSet()
            "i" -> content.toIntOrNull() ?: error("Número inteiro inválido no backup.")
            "l" -> content.toLongOrNull() ?: error("Número longo inválido no backup.")
            "f" -> content.toFloatOrNull() ?: error("Número decimal inválido no backup.")
            "b" -> when (content) { "true" -> true; "false" -> false; else -> error("Booleano inválido no backup.") }
            else -> error("Tipo desconhecido no backup.")
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BACKUP_BYTES) { "O backup ultrapassa 5 MiB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun encode(value: String) = URLEncoder.encode(value, charset)
    private fun decode(value: String) = URLDecoder.decode(value, charset)
}
