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
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PREFS_NAME = "kitsune"
private const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
private val TRANSIENT_PREFERENCE_KEYS = setOf("performance_metrics", "update_download_id")

object UserDataBackup {
    fun export(context: Context, uri: Uri, password: CharArray? = null) {
        VideoHistory.flushForBackup()
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error(context.getString(R.string.error_export_backup))
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        output.use { destination ->
            val encoded = ByteArrayOutputStream().also { buffer ->
                BackupCodec.write(userDataPreferences(preferences), buffer)
            }.toByteArray()
            if (password == null || password.isEmpty()) {
                destination.write(encoded)
            } else {
                EncryptedBackupCodec.write(encoded, password, destination)
            }
        }
    }

    fun restore(context: Context, uri: Uri, password: CharArray? = null) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error(context.getString(R.string.error_restore_backup))
        val payload = input.use { stream -> stream.readBytes(MAX_BACKUP_BYTES + 1024) }
        val decoded = if (EncryptedBackupCodec.isEncrypted(payload)) {
            val backupPassword = password
            require(backupPassword != null && backupPassword.isNotEmpty()) {
                context.getString(R.string.backup_password_required)
            }
            EncryptedBackupCodec.read(payload, backupPassword)
        } else {
            payload
        }
        val values = BackupCodec.read(ByteArrayInputStream(decoded))
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

private fun InputStream.readBytes(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) {
            return output.toByteArray()
        }
        require(output.size() + count <= maximum) { "O backup é grande demais." }
        output.write(buffer, 0, count)
    }
}

internal object EncryptedBackupCodec {
    private val header = "KITSUNE-ENC-1\n".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 150_000

    fun isEncrypted(payload: ByteArray): Boolean {
        return payload.size >= header.size && payload.copyOfRange(0, header.size).contentEquals(header)
    }

    fun write(payload: ByteArray, password: CharArray, output: OutputStream) {
        require(password.isNotEmpty()) { "Informe uma senha para o backup." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt))
        output.write(header)
        output.write(salt)
        output.write(cipher.iv)
        output.write(cipher.doFinal(payload))
    }

    fun read(payload: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(payload)) { "Backup criptografado inválido." }
        require(password.isNotEmpty()) { "Informe a senha do backup." }
        val saltStart = header.size
        val ivStart = saltStart + SALT_BYTES
        val dataStart = ivStart + IV_BYTES
        require(payload.size > dataStart) { "Backup criptografado incompleto." }
        return try {
            val salt = payload.copyOfRange(saltStart, ivStart)
            val iv = payload.copyOfRange(ivStart, dataStart)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.doFinal(payload.copyOfRange(dataStart, payload.size))
        } catch (_: Exception) {
            throw IllegalArgumentException("Senha incorreta ou backup corrompido.")
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES"
        )
    }
}

internal fun userDataPreferences(values: Map<String, *>): Map<String, *> {
    return values.filterKeys { key ->
        key !in TRANSIENT_PREFERENCE_KEYS && !key.startsWith("catalog_cache:")
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
