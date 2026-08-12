package com.kitsuneandroid

internal fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0

    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }

    if (unit == 0) {
        return "${value.toLong()} ${units[unit]}"
    }
    return "%.1f %s".format(value, units[unit])
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
