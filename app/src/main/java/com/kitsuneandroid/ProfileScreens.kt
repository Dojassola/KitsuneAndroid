package com.kitsuneandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val PREFS = "kitsune"
private const val PROFILE_NAME = "profile_name"
private const val PROFILE_AVATAR = "profile_avatar"
private const val RELEASE_LANGUAGE = "release_language"
private const val RELEASE_RESOLUTION = "release_resolution"

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun ProfileScreen(
    refresh: Int,
    backupBusy: Boolean,
    backupMessage: String?,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onDataChanged: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val defaultProfileName = stringResource(R.string.default_profile_name)
    var name by remember(refresh, defaultProfileName) {
        mutableStateOf(preferences.getString(PROFILE_NAME, defaultProfileName).orEmpty())
    }
    var avatar by remember(refresh) { mutableStateOf(preferences.getString(PROFILE_AVATAR, null)) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                avatarMessage = context.getString(R.string.preparing_image)

                try {
                    val encodedAvatar = withContext(Dispatchers.IO) {
                        encodeProfileAvatar(context, uri)
                    }
                    avatar = encodedAvatar
                    preferences.edit().putString(PROFILE_AVATAR, encodedAvatar).apply()
                    avatarMessage = context.getString(R.string.photo_updated)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    avatarMessage = failure.message
                        ?: context.getString(R.string.error_use_image)
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(stringResource(R.string.profile), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val image = remember(avatar) { avatar?.let(::decodeProfileAvatar) }
                    if (image != null) {
                        Image(image, stringResource(R.string.profile_photo), Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text(name.trim().firstOrNull()?.uppercase() ?: "K", style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.choose_photo)) }
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    Button(onClick = {
                        val savedName = name.trim().ifBlank { defaultProfileName }
                        name = savedName
                        preferences.edit().putString(PROFILE_NAME, savedName).apply()
                    }) { Text(stringResource(R.string.save_profile)) }
                    avatarMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            BackupCard(backupBusy, backupMessage, onExport, onRestore)
        }
        item {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.open_settings))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun SettingsScreen(refresh: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var releasePreferences by remember(refresh) { mutableStateOf(loadReleasePreferences(context)) }
    var metadataLanguage by remember(refresh) { mutableStateOf(loadMetadataLanguage(context)) }
    var interfaceLanguage by remember(refresh) { mutableStateOf(loadInterfaceLanguage(context)) }
    var downloadPolicy by remember(refresh) { mutableStateOf(loadDownloadPolicy(context)) }
    var remoteProviders by remember(refresh) { mutableStateOf(loadRemoteProviderConfigs(context)) }
    var builtInStreamProviders by remember(refresh) { mutableStateOf(loadBuiltInStreamProviders(context)) }
    var catalogProviders by remember(refresh) { mutableStateOf(loadCatalogProviders(context)) }
    var subtitleProviders by remember(refresh) { mutableStateOf(loadSubtitleProviderSettings(context)) }
    var episodeUpdatesEnabled by remember(refresh) {
        mutableStateOf(EpisodeUpdateNotifications.enabled(context))
    }
    var languageExpanded by rememberSaveable { mutableStateOf(false) }
    var downloadsExpanded by rememberSaveable { mutableStateOf(false) }
    var notificationsExpanded by rememberSaveable { mutableStateOf(false) }
    var providersExpanded by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        episodeUpdatesEnabled = granted
        EpisodeUpdateNotifications.setEnabled(context, granted)
    }

    fun setEpisodeUpdatesEnabled(enabled: Boolean) {
        if (
            enabled &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        episodeUpdatesEnabled = enabled
        EpisodeUpdateNotifications.setEnabled(context, enabled)
    }

    fun saveReleasePreferences(value: ReleasePreferences) {
        releasePreferences = value
        preferences.edit()
            .putString(RELEASE_LANGUAGE, value.language.name)
            .putInt(RELEASE_RESOLUTION, value.resolution ?: 0)
            .apply()

        subtitleLanguage(value.language)?.let { language ->
            subtitleProviders = subtitleProviders.copy(language = language)
            saveSubtitleProviderSettings(context, subtitleProviders)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.languages_and_video),
                summary = stringResource(R.string.languages_and_video_summary),
                expanded = languageExpanded,
                onClick = { languageExpanded = !languageExpanded }
            )
        }
        if (languageExpanded) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.catalog_language), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.catalog_language_summary),
                            style = MaterialTheme.typography.bodySmall
                        )
                        PreferenceOption(metadataLanguage == MetadataLanguage.PORTUGUESE, stringResource(R.string.portuguese_brazil)) {
                            metadataLanguage = MetadataLanguage.PORTUGUESE
                            saveMetadataLanguage(context, metadataLanguage)
                        }
                        PreferenceOption(metadataLanguage == MetadataLanguage.ORIGINAL, stringResource(R.string.original)) {
                            metadataLanguage = MetadataLanguage.ORIGINAL
                            saveMetadataLanguage(context, metadataLanguage)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.interface_language), fontWeight = FontWeight.Bold)
                        listOf(
                            InterfaceLanguage.SYSTEM to stringResource(R.string.system_language),
                            InterfaceLanguage.PORTUGUESE to stringResource(R.string.portuguese_brazil),
                            InterfaceLanguage.ENGLISH to stringResource(R.string.english)
                        ).forEach { (value, label) ->
                            PreferenceOption(interfaceLanguage == value, label) {
                                interfaceLanguage = value
                                saveInterfaceLanguage(context, value)
                                (context as? MainActivity)?.recreate()
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.video_language), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.video_language_summary),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val selectedLanguage = if (releasePreferences.language == ReleaseLanguage.DUBBED) {
                            ReleaseLanguage.PORTUGUESE
                        } else {
                            releasePreferences.language
                        }
                        listOf(
                            ReleaseLanguage.ANY to stringResource(R.string.any_language),
                            ReleaseLanguage.PORTUGUESE to stringResource(R.string.portuguese_brazil),
                            ReleaseLanguage.ENGLISH to stringResource(R.string.english),
                            ReleaseLanguage.JAPANESE to stringResource(R.string.japanese_original)
                        ).forEach { (value, label) ->
                            PreferenceOption(selectedLanguage == value, label) {
                                saveReleasePreferences(releasePreferences.copy(language = value))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.quality), fontWeight = FontWeight.Bold)
                        listOf(null to stringResource(R.string.automatic), 720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (value, label) ->
                            PreferenceOption(releasePreferences.resolution == value, label) {
                                saveReleasePreferences(releasePreferences.copy(resolution = value))
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.downloads),
                summary = stringResource(R.string.downloads_settings_summary),
                expanded = downloadsExpanded,
                onClick = { downloadsExpanded = !downloadsExpanded }
            )
        }
        if (downloadsExpanded) {
            item {
                DownloadPolicyCard(
                    policy = downloadPolicy,
                    onChange = { updated ->
                        downloadPolicy = updated
                        saveDownloadPolicy(context, updated)
                    }
                )
            }
        }
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.episode_updates),
                summary = stringResource(R.string.episode_updates_summary),
                expanded = notificationsExpanded,
                onClick = { notificationsExpanded = !notificationsExpanded }
            )
        }
        if (notificationsExpanded) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                setEpisodeUpdatesEnabled(!episodeUpdatesEnabled)
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.notify_new_episodes),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.notify_new_episodes_summary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Checkbox(
                            checked = episodeUpdatesEnabled,
                            onCheckedChange = ::setEpisodeUpdatesEnabled
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.providers_and_apis),
                summary = stringResource(R.string.providers_and_apis_summary),
                expanded = providersExpanded,
                onClick = { providersExpanded = !providersExpanded }
            )
        }
        if (providersExpanded) {
            item {
                CatalogProvidersCard(
                    enabled = catalogProviders,
                    onChange = { updated ->
                        catalogProviders = updated
                        saveCatalogProviders(context, updated)
                    }
                )
            }
            item {
                RemoteProvidersCard(
                    configs = remoteProviders,
                    builtInProviders = builtInStreamProviders,
                    onBuiltInProvidersChange = { updated ->
                        builtInStreamProviders = updated
                        saveBuiltInStreamProviders(context, updated)
                    },
                    onChange = { updated ->
                        remoteProviders = updated
                        saveRemoteProviderConfigs(context, updated)
                    }
                )
            }
            item {
                OnlineSubtitlesCard(
                    settings = subtitleProviders,
                    onChange = { updated ->
                        subtitleProviders = updated
                        saveSubtitleProviderSettings(context, updated)
                    }
                )
            }
        }
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.diagnostics),
                summary = stringResource(R.string.diagnostics_summary),
                expanded = diagnosticsExpanded,
                onClick = { diagnosticsExpanded = !diagnosticsExpanded }
            )
        }
        if (diagnosticsExpanded) {
            item { PerformanceCard(AppPerformance.metrics) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun subtitleLanguage(language: ReleaseLanguage): String? {
    return when (language) {
        ReleaseLanguage.PORTUGUESE,
        ReleaseLanguage.DUBBED -> "pt-br"
        ReleaseLanguage.ENGLISH -> "en"
        ReleaseLanguage.JAPANESE -> "ja"
        ReleaseLanguage.ANY -> null
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
private fun OnlineSubtitlesCard(
    settings: SubtitleProviderSettings,
    onChange: (SubtitleProviderSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openSubtitlesApiKey by rememberSaveable(settings.openSubtitlesApiKey) {
        mutableStateOf(settings.openSubtitlesApiKey)
    }
    var subDlApiKey by rememberSaveable(settings.subDlApiKey) {
        mutableStateOf(settings.subDlApiKey)
    }
    var session by remember { mutableStateOf(loadOpenSubtitlesSession(context)) }
    var username by remember(session?.username) {
        mutableStateOf(session?.username.orEmpty())
    }
    var password by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginMessage by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subtitle_providers), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.subtitle_providers_summary),
                style = MaterialTheme.typography.bodySmall
            )
            Text("OpenSubtitles", fontWeight = FontWeight.SemiBold)
            SettingsToggle(
                checked = settings.openSubtitlesEnabled,
                title = stringResource(if (settings.openSubtitlesEnabled) R.string.enabled else R.string.disabled),
                onChange = { enabled ->
                    onChange(settings.copy(openSubtitlesEnabled = enabled))
                }
            )
            OutlinedTextField(
                value = openSubtitlesApiKey,
                onValueChange = { value -> openSubtitlesApiKey = value.take(200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    onChange(settings.copy(openSubtitlesApiKey = openSubtitlesApiKey.trim()))
                }
            ) {
                Text(stringResource(R.string.save_key))
            }
            Text(stringResource(R.string.account_optional), fontWeight = FontWeight.SemiBold)
            if (session == null) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { value -> username = value.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { value -> password = value.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    enabled = !loginBusy,
                    onClick = {
                        loginBusy = true
                        loginMessage = context.getString(R.string.signing_in)
                        scope.launch {
                            try {
                                val savedKey = openSubtitlesApiKey.trim()
                                onChange(settings.copy(openSubtitlesApiKey = savedKey))
                                session = withContext(Dispatchers.IO) {
                                    OpenSubtitles.login(context, savedKey, username, password)
                                }
                                password = ""
                                loginMessage = context.getString(R.string.account_connected)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                loginMessage = failure.message ?: context.getString(R.string.error_sign_in)
                            } finally {
                                loginBusy = false
                            }
                        }
                    }
                ) {
                    if (loginBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    } else {
                        Text(stringResource(R.string.sign_in))
                    }
                }
            } else {
                Text(stringResource(R.string.connected_as, session?.username.orEmpty()))
                TextButton(
                    onClick = {
                        clearOpenSubtitlesSession(context)
                        session = null
                        loginMessage = context.getString(R.string.session_removed)
                    }
                ) {
                    Text(stringResource(R.string.sign_out))
                }
            }
            loginMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            Text("SubDL", fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.subdl_summary),
                style = MaterialTheme.typography.bodySmall
            )
            SettingsToggle(
                checked = settings.subDlEnabled,
                title = stringResource(if (settings.subDlEnabled) R.string.enabled else R.string.disabled),
                onChange = { enabled ->
                    onChange(settings.copy(subDlEnabled = enabled))
                }
            )
            OutlinedTextField(
                value = subDlApiKey,
                onValueChange = { value -> subDlApiKey = value.take(200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.subdl_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    onChange(settings.copy(subDlApiKey = subDlApiKey.trim()))
                }
            ) {
                Text(stringResource(R.string.save_subdl_key))
            }
            Text(stringResource(R.string.automatic_translation), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.automatic_translation_summary),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.google_translation_disclaimer),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com"))
                    )
                }
            ) {
                Text(stringResource(R.string.open_google_translate))
            }
        }
    }
}

@Composable
private fun CatalogProvidersCard(
    enabled: Set<CatalogProvider>,
    onChange: (Set<CatalogProvider>) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.catalog_sources), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.catalog_sources_summary),
                style = MaterialTheme.typography.bodySmall
            )
            CatalogProvider.entries.forEach { provider ->
                SettingsToggle(
                    checked = provider in enabled,
                    title = provider.label,
                    onChange = { checked ->
                        onChange(if (checked) enabled + provider else enabled - provider)
                    }
                )
            }
        }
    }
}

@Composable
private fun PerformanceCard(metrics: List<PerformanceMetric>) {
    val context = LocalContext.current
    val diagnosticsTitle = stringResource(R.string.diagnostics)
    val shareDiagnostics = stringResource(R.string.share_diagnostics)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recent_performance), fontWeight = FontWeight.Bold)
            if (metrics.isEmpty()) {
                Text(stringResource(R.string.performance_empty))
            } else {
                metrics.take(6).forEach { metric ->
                    Text(
                        "${metric.name}: ${metric.durationMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, diagnosticsTitle)
                            .putExtra(Intent.EXTRA_TEXT, AppPerformance.diagnosticReport(context))
                        context.startActivity(
                            Intent.createChooser(share, shareDiagnostics)
                        )
                    }
                ) {
                    Text(stringResource(R.string.share_diagnostics))
                }
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
private fun RemoteProvidersCard(
    configs: List<RemoteProviderConfig>,
    builtInProviders: Set<BuiltInStreamProvider>,
    onBuiltInProvidersChange: (Set<BuiltInStreamProvider>) -> Unit,
    onChange: (List<RemoteProviderConfig>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testingUrl by remember { mutableStateOf<String?>(null) }

    fun setProviderEnabled(config: RemoteProviderConfig, enabled: Boolean) {
        onChange(updateRemoteProvider(configs, config.manifestUrl) { it.copy(enabled = enabled) })
    }

    fun setCatalogEnabled(config: RemoteProviderConfig, enabled: Boolean) {
        onChange(updateRemoteProvider(configs, config.manifestUrl) { it.copy(catalogEnabled = enabled) })
    }

    fun setStreamEnabled(config: RemoteProviderConfig, enabled: Boolean) {
        onChange(updateRemoteProvider(configs, config.manifestUrl) { it.copy(streamEnabled = enabled) })
    }

    fun testProvider(config: RemoteProviderConfig) {
        testingUrl = config.manifestUrl
        message = context.getString(R.string.testing_provider, config.name ?: "addon")
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    inspectRemoteProvider(config.manifestUrl)
                }
                onChange(
                    updateRemoteProvider(configs, config.manifestUrl) {
                        it.copy(
                            protocol = manifest.protocol,
                            providerId = manifest.id,
                            name = manifest.name,
                            version = manifest.version,
                            capabilities = manifest.capabilities
                        )
                    }
                )
                message = context.getString(R.string.provider_connection_working, manifest.name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                message = failure.message ?: context.getString(R.string.error_test_addon)
            } finally {
                testingUrl = null
            }
        }
    }

    fun addProvider() {
        val normalizedUrl = try {
            normalizeRemoteProviderUrl(input)
        } catch (failure: Exception) {
            message = failure.message ?: context.getString(R.string.invalid_addon_url)
            return
        }

        testingUrl = normalizedUrl
        message = context.getString(R.string.validating_manifest)
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    inspectRemoteProvider(normalizedUrl)
                }
                val config = RemoteProviderConfig(
                    manifestUrl = normalizedUrl,
                    protocol = manifest.protocol,
                    providerId = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    priority = configs.size,
                    capabilities = manifest.capabilities
                )
                onChange((configs + config).distinctBy(RemoteProviderConfig::manifestUrl))
                input = ""
                message = context.getString(R.string.provider_added, manifest.name, manifest.protocol.title)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                message = failure.message ?: context.getString(R.string.addon_invalid_response)
            } finally {
                testingUrl = null
            }
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.sources_and_providers), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.sources_and_providers_summary),
                style = MaterialTheme.typography.bodySmall
            )
            BuiltInStreamProvider.entries.forEach { provider ->
                val added = provider in builtInProviders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(if (added) R.string.added_to_search else R.string.available_to_add),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = {
                        onBuiltInProvidersChange(
                            if (added) builtInProviders - provider else builtInProviders + provider
                        )
                    }) { Text(stringResource(if (added) R.string.remove else R.string.add)) }
                }
            }

            configs.sortedBy(RemoteProviderConfig::priority).forEachIndexed { index, config ->
                RemoteProviderRow(
                    config = config,
                    index = index,
                    lastIndex = configs.lastIndex,
                    testing = testingUrl != null,
                    onEnabledChange = { enabled -> setProviderEnabled(config, enabled) },
                    onCatalogEnabledChange = { enabled -> setCatalogEnabled(config, enabled) },
                    onStreamEnabledChange = { enabled -> setStreamEnabled(config, enabled) },
                    onMove = { direction ->
                        onChange(moveRemoteProvider(configs, config.manifestUrl, direction))
                    },
                    onTest = { testProvider(config) },
                    onRemove = {
                        onChange(configs.filterNot { it.manifestUrl == config.manifestUrl })
                    }
                )
            }

            RemoteProviderForm(
                value = input,
                busy = testingUrl != null,
                onValueChange = { updated ->
                    input = updated
                    message = null
                },
                onAdd = ::addProvider
            )
            message?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RemoteProviderRow(
    config: RemoteProviderConfig,
    index: Int,
    lastIndex: Int,
    testing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onCatalogEnabledChange: (Boolean) -> Unit,
    onStreamEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsToggle(
            checked = config.enabled,
            title = config.name ?: stringResource(R.string.addon_number, index + 1),
            supportingText = config.manifestUrl,
            onChange = onEnabledChange
        )
        Text(
            text = remoteProviderStatus(config, index),
            style = MaterialTheme.typography.labelSmall
        )
        if (config.capabilities.isEmpty() || "catalog" in config.capabilities) {
            SettingsToggle(
                checked = config.catalogEnabled,
                title = stringResource(R.string.use_for_catalog),
                onChange = onCatalogEnabledChange
            )
        }
        if (
            config.capabilities.isEmpty() ||
            "stream" in config.capabilities ||
            "streams" in config.capabilities
        ) {
            SettingsToggle(
                checked = config.streamEnabled,
                title = stringResource(R.string.use_for_video),
                onChange = onStreamEnabledChange
            )
        }
        Row {
            TextButton(enabled = index > 0, onClick = { onMove(-1) }) { Text(stringResource(R.string.move_up)) }
            TextButton(enabled = index < lastIndex, onClick = { onMove(1) }) { Text(stringResource(R.string.move_down)) }
            TextButton(enabled = !testing, onClick = onTest) { Text(stringResource(R.string.test)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun remoteProviderStatus(config: RemoteProviderConfig, index: Int): String {
    if (!config.enabled) {
        return stringResource(R.string.disabled)
    }
    val capabilities = buildList {
        if ("catalog" in config.capabilities) {
            add(stringResource(R.string.catalog))
        }
        if ("stream" in config.capabilities || "streams" in config.capabilities) {
            add(stringResource(R.string.video))
        }
        if ("subtitles" in config.capabilities) {
            add(stringResource(R.string.subtitles))
        }
    }
    val capabilityText = capabilities.joinToString().ifBlank { stringResource(R.string.capabilities_untested) }
    return stringResource(R.string.provider_status, config.protocol.title, index + 1, capabilityText)
}

@Composable
private fun RemoteProviderForm(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.provider_manifest_url)) },
        singleLine = true
    )
    Button(onClick = onAdd, enabled = value.isNotBlank() && !busy) {
        Text(stringResource(R.string.install_provider))
    }
}

@Composable
private fun DownloadPolicyCard(
    policy: DownloadPolicyPreferences,
    onChange: (DownloadPolicyPreferences) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.downloads), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.download_limits_summary),
                style = MaterialTheme.typography.bodySmall
            )
            SettingsToggle(
                checked = policy.wifiOnly,
                title = stringResource(R.string.wifi_only),
                onChange = { enabled ->
                    onChange(policy.copy(wifiOnly = enabled))
                }
            )
            SettingsToggle(
                checked = policy.pauseOnLowBattery,
                title = stringResource(R.string.pause_low_battery),
                onChange = { enabled ->
                    onChange(policy.copy(pauseOnLowBattery = enabled))
                }
            )
            SettingsToggle(
                checked = policy.preserveStorage,
                title = stringResource(R.string.preserve_storage),
                onChange = { enabled ->
                    onChange(policy.copy(preserveStorage = enabled))
                }
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    checked: Boolean,
    title: String,
    supportingText: String? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                onChange(!checked)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange
        )
        Column {
            Text(title)
            supportingText?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PreferenceOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

internal fun loadReleasePreferences(context: Context): ReleasePreferences {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val savedLanguage = preferences.getString(RELEASE_LANGUAGE, null)
    val language = ReleaseLanguage.entries.firstOrNull { entry ->
        entry.name == savedLanguage
    } ?: ReleaseLanguage.ANY
    return ReleasePreferences(language, preferences.getInt(RELEASE_RESOLUTION, 1080).takeIf { it > 0 })
}

private fun encodeProfileAvatar(context: Context, uri: Uri): String {
    val bitmap = if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error(context.getString(R.string.invalid_image))
    }
    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val bytes = ByteArrayOutputStream().use { output ->
        check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
            context.getString(R.string.error_prepare_image)
        }
        output.toByteArray()
    }
    require(bytes.size <= 512 * 1024) { context.getString(R.string.profile_image_too_large) }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun decodeProfileAvatar(value: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Composable
private fun BackupCard(busy: Boolean, message: String?, onExport: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.data_and_backup), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.data_and_backup_summary), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport, enabled = !busy) { Text(stringResource(R.string.export)) }
                TextButton(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.restore)) }
                if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
