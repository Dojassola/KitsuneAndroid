package com.kitsuneandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AccountSyncSettingsCard() {
    val context = LocalContext.current
    val revision = AccountSync.revision.intValue
    val syncStartedMessage = stringResource(R.string.account_sync_started)
    var message by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.account_sync_local_first))
            AccountProvider.entries.forEach { provider ->
                val connection = remember(revision, provider) {
                    AccountSync.connection(context, provider)
                }
                AccountProviderRow(
                    provider = provider,
                    connection = connection,
                    onConnect = {
                        message = AccountSync.connect(context, provider)
                    },
                    onSync = {
                        message = syncStartedMessage
                        AccountSync.syncNow(context, provider)
                    },
                    onDisconnect = {
                        AccountSync.disconnect(context, provider)
                        message = null
                    }
                )
            }
            message?.let { Text(it) }
        }
    }
}

@Composable
private fun AccountProviderRow(
    provider: AccountProvider,
    connection: AccountConnection,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(provider.title(), fontWeight = FontWeight.Bold)
        Text(connection.description())
        connection.error?.let { error ->
            Text(error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (connection.connected) {
                Button(onClick = onSync) {
                    Text(stringResource(R.string.sync_now))
                }
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.disconnect))
                }
            } else {
                Button(onClick = onConnect, enabled = connection.configured) {
                    Text(stringResource(R.string.connect_account))
                }
            }
        }
    }
}

@Composable
private fun AccountProvider.title(): String {
    return when (this) {
        AccountProvider.ANILIST -> "AniList"
        AccountProvider.MY_ANIME_LIST -> "MyAnimeList"
    }
}

@Composable
private fun AccountConnection.description(): String {
    if (!configured) {
        return stringResource(R.string.account_sync_not_configured)
    }
    if (!connected) {
        return stringResource(R.string.account_not_connected)
    }
    if (lastSyncAt <= 0) {
        return username ?: stringResource(R.string.account_sync_connected)
    }
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSyncAt))
    return stringResource(R.string.account_last_sync, username.orEmpty(), date)
}
