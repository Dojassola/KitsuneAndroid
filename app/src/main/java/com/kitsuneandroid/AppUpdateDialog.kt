package com.kitsuneandroid

import android.app.DownloadManager
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val UPDATE_PREFERENCES = "kitsune"
private const val IGNORED_UPDATE = "ignored_update_version"
private const val UPDATE_DOWNLOAD_ID = "update_download_id"

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun UpdateDialog() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var visible by remember { mutableStateOf(false) }
    var ignoreVersion by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var downloadId by rememberSaveable {
        mutableLongStateOf(preferences.getLong(UPDATE_DOWNLOAD_ID, -1))
    }

    fun clearDownload() {
        preferences.edit().remove(UPDATE_DOWNLOAD_ID).apply()
        downloadId = -1
    }

    fun dismissDialog() {
        if (ignoreVersion) {
            preferences.edit().putString(IGNORED_UPDATE, release?.version).apply()
        }
        visible = false
    }

    LaunchedEffect(Unit) {
        delay(1_500)

        try {
            val available = withContext(Dispatchers.IO) {
                AppUpdater.latest(context)
            }

            if (
                available != null &&
                preferences.getString(IGNORED_UPDATE, null) != available.version
            ) {
                release = available
                message = context.getString(R.string.version_available, available.version)
                visible = true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@LaunchedEffect
        }
    }

    LaunchedEffect(downloadId) {
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

        if (downloadId >= 0 && canInstall && AppUpdater.install(context, downloadId)) {
            clearDownload()
        }
    }

    DisposableEffect(downloadId) {
        val receiver = if (downloadId >= 0) {
            updateDownloadReceiver(
                context = context,
                downloadId = downloadId,
                onPermissionRequired = {
                    message = context.getString(R.string.allow_unknown_apps_after_download)
                },
                onInstalled = {
                    clearDownload()
                }
            )
        } else {
            null
        }

        receiver?.let { downloadReceiver ->
            ContextCompat.registerReceiver(
                context,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        onDispose {
            if (receiver != null) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    Unit
                }
            }
        }
    }

    val availableRelease = release
    if (visible && availableRelease != null) {
        AlertDialog(
            onDismissRequest = ::dismissDialog,
            title = { Text(stringResource(R.string.update_available)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(message)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ignoreVersion = !ignoreVersion },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(ignoreVersion, { ignoreVersion = it })
                        Text(stringResource(R.string.do_not_show_version_again))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            !context.packageManager.canRequestPackageInstalls()
                        ) {
                            message = context.getString(R.string.allow_unknown_apps_retry)
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            downloadId = AppUpdater.download(context, availableRelease)
                            preferences.edit().putLong(UPDATE_DOWNLOAD_ID, downloadId).apply()
                            message = context.getString(R.string.downloading_update_github)
                        }
                    }
                ) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = ::dismissDialog
                ) {
                    Text(stringResource(R.string.not_now))
                }
            }
        )
    }
}

private fun updateDownloadReceiver(
    context: Context,
    downloadId: Long,
    onPermissionRequired: () -> Unit,
    onInstalled: () -> Unit
): BroadcastReceiver {
    return object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) {
                return
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                onPermissionRequired()
                return
            }

            if (AppUpdater.install(context, downloadId)) {
                onInstalled()
            }
        }
    }
}
