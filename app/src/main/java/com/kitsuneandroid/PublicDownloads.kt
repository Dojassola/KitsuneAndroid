package com.kitsuneandroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal fun saveBytesToDownloads(
    context: Context,
    displayName: String,
    mimeType: String,
    bytes: ByteArray
): Uri {
    return saveToDownloads(context, displayName, mimeType) { output ->
        output.write(bytes)
    }
}

internal fun saveFileToDownloads(
    context: Context,
    source: File,
    mimeType: String
): Uri {
    require(source.isFile) {
        "O arquivo não está mais disponível."
    }

    return saveToDownloads(context, source.name, mimeType) { output ->
        source.inputStream().use { input ->
            input.copyTo(output)
        }
    }
}

internal fun safeDownloadName(value: String, extension: String): String {
    val baseName = value
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "Kitsune" }
    return if (baseName.endsWith(".$extension", ignoreCase = true)) {
        baseName
    } else {
        "$baseName.$extension"
    }
}

internal fun videoMimeType(file: File): String = when (file.extension.lowercase()) {
    "mkv" -> "video/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "ts", "m2ts" -> "video/mp2t"
    "mov" -> "video/quicktime"
    else -> "video/*"
}

private fun saveToDownloads(
    context: Context,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit
): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return saveToLegacyDownloads(displayName, write)
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Kitsune")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = requireNotNull(
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    ) {
        "Não foi possível criar o arquivo em Downloads."
    }

    try {
        resolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) {
                "Não foi possível abrir o arquivo em Downloads."
            }
            write(output)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
}

@Suppress("DEPRECATION")
private fun saveToLegacyDownloads(
    displayName: String,
    write: (OutputStream) -> Unit
): Uri {
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Kitsune"
    )
    require(directory.isDirectory || directory.mkdirs()) {
        "Não foi possível criar a pasta Downloads/Kitsune."
    }
    val target = uniqueFile(directory, displayName)
    FileOutputStream(target).use(write)
    return Uri.fromFile(target)
}

private fun uniqueFile(directory: File, displayName: String): File {
    val requested = File(displayName)
    val baseName = requested.nameWithoutExtension.ifBlank { "Kitsune" }
    val extension = requested.extension
    var candidate = File(directory, requested.name)
    var suffix = 2

    while (candidate.exists()) {
        val name = if (extension.isBlank()) {
            "$baseName ($suffix)"
        } else {
            "$baseName ($suffix).$extension"
        }
        candidate = File(directory, name)
        suffix++
    }
    return candidate
}
