package com.kitsuneandroid

import android.app.DownloadManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    var downloadStarting by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<AppDownloadState?>(null) }
    var downloadId by rememberSaveable {
        mutableLongStateOf(preferences.getLong(UPDATE_DOWNLOAD_ID, -1))
    }

    fun clearDownload() {
        preferences.edit().remove(UPDATE_DOWNLOAD_ID).apply()
        downloadId = -1
        downloadState = null
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
        while (downloadId >= 0) {
            val state = withContext(Dispatchers.IO) {
                AppUpdater.downloadState(context, downloadId)
            }
            downloadState = state

            when (state?.status) {
                null,
                DownloadManager.STATUS_FAILED -> {
                    message = context.getString(R.string.update_download_failed)
                    clearDownload()
                    break
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        context.packageManager.canRequestPackageInstalls()

                    if (canInstall && AppUpdater.install(context, downloadId)) {
                        clearDownload()
                    } else {
                        message = context.getString(R.string.allow_unknown_apps_after_download)
                    }
                    break
                }
            }

            delay(500)
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
                    if (downloadState?.active == true) {
                        val progress = downloadState?.progress

                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${(progress * 100).toInt()}%")
                        }
                    }
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
                    enabled = !downloadStarting && (
                        downloadId < 0 || downloadState?.status == DownloadManager.STATUS_SUCCESSFUL
                    ),
                    onClick = {
                        if (
                            downloadStarting ||
                            downloadState?.active == true ||
                            (downloadId >= 0 && downloadState == null)
                        ) {
                            return@Button
                        }

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
                        } else if (downloadState?.status == DownloadManager.STATUS_SUCCESSFUL) {
                            if (AppUpdater.install(context, downloadId)) {
                                clearDownload()
                            }
                        } else {
                            downloadStarting = true
                            try {
                                downloadId = AppUpdater.download(
                                    context = context,
                                    release = availableRelease,
                                    existingId = downloadId
                                )
                                preferences.edit().putLong(UPDATE_DOWNLOAD_ID, downloadId).apply()
                                message = context.getString(R.string.downloading_update_github)
                            } catch (_: Exception) {
                                message = context.getString(R.string.update_download_failed)
                            } finally {
                                downloadStarting = false
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (downloadStarting || downloadId >= 0) {
                                R.string.downloading
                            } else {
                                R.string.download
                            }
                        )
                    )
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
