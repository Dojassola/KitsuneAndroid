package com.kitsuneandroid

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import java.io.File

internal data class DownloadPolicyPreferences(
    val wifiOnly: Boolean = false,
    val pauseOnLowBattery: Boolean = true,
    val preserveStorage: Boolean = true
)

private const val PREFERENCES = "kitsune"
private const val WIFI_ONLY = "download_wifi_only"
private const val PAUSE_ON_LOW_BATTERY = "download_pause_low_battery"
private const val PRESERVE_STORAGE = "download_preserve_storage"
private const val LOW_BATTERY_PERCENT = 15
private const val MINIMUM_FREE_STORAGE_BYTES = 1024L * 1024 * 1024

internal fun loadDownloadPolicy(context: Context): DownloadPolicyPreferences {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    return DownloadPolicyPreferences(
        wifiOnly = preferences.getBoolean(WIFI_ONLY, false),
        pauseOnLowBattery = preferences.getBoolean(PAUSE_ON_LOW_BATTERY, true),
        preserveStorage = preferences.getBoolean(PRESERVE_STORAGE, true)
    )
}

internal fun saveDownloadPolicy(
    context: Context,
    policy: DownloadPolicyPreferences
) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(WIFI_ONLY, policy.wifiOnly)
        .putBoolean(PAUSE_ON_LOW_BATTERY, policy.pauseOnLowBattery)
        .putBoolean(PRESERVE_STORAGE, policy.preserveStorage)
        .apply()
}

internal fun downloadBlockReason(context: Context): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
    val capabilities = network?.let(connectivity::getNetworkCapabilities)
    val unmeteredNetwork = capabilities?.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_NOT_METERED
    ) == true
    val battery = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    )
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPercent = if (level >= 0 && scale > 0) {
        level * 100 / scale
    } else {
        null
    }
    val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
        batteryStatus == BatteryManager.BATTERY_STATUS_FULL
    val downloadDirectory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
        "Kitsune"
    )

    return downloadPolicyBlockReason(
        preferences = loadDownloadPolicy(context),
        unmeteredNetwork = unmeteredNetwork,
        batteryPercent = batteryPercent,
        charging = charging,
        freeStorageBytes = downloadDirectory.usableSpace
    )
}

internal fun downloadPolicyBlockReason(
    preferences: DownloadPolicyPreferences,
    unmeteredNetwork: Boolean,
    batteryPercent: Int?,
    charging: Boolean,
    freeStorageBytes: Long
): String? {
    if (preferences.wifiOnly && !unmeteredNetwork) {
        return "Download pausado até conectar a uma rede Wi-Fi."
    }

    if (
        preferences.pauseOnLowBattery &&
        !charging &&
        batteryPercent != null &&
        batteryPercent <= LOW_BATTERY_PERCENT
    ) {
        return "Download pausado por bateria fraca."
    }

    if (
        preferences.preserveStorage &&
        freeStorageBytes < MINIMUM_FREE_STORAGE_BYTES
    ) {
        return "Download pausado para preservar 1 GiB de espaço livre."
    }

    return null
}
