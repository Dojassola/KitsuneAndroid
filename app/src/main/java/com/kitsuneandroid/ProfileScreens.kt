package com.kitsuneandroid

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val PREFS = "kitsune"
private const val PROFILE_NAME = "profile_name"
private const val PROFILE_AVATAR = "profile_avatar"
private const val RELEASE_LANGUAGE = "release_language"
private const val RELEASE_RESOLUTION = "release_resolution"
private const val IGNORED_UPDATE = "ignored_update_version"

@Composable
internal fun ProfileScreen(
    refresh: Int,
    backupBusy: Boolean,
    backupMessage: String?,
    onExport: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var name by remember(refresh) { mutableStateOf(preferences.getString(PROFILE_NAME, "Usuário Kitsune").orEmpty()) }
    var avatar by remember(refresh) { mutableStateOf(preferences.getString(PROFILE_AVATAR, null)) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }
    var releasePreferences by remember(refresh) { mutableStateOf(loadReleasePreferences(context)) }
    var metadataLanguage by remember(refresh) { mutableStateOf(loadMetadataLanguage(context)) }
    var downloadPolicy by remember(refresh) { mutableStateOf(loadDownloadPolicy(context)) }
    var stremioAddons by remember(refresh) {
        mutableStateOf(loadStremioAddonConfigs(context))
    }
    var builtInStreamProviders by remember(refresh) {
        mutableStateOf(loadBuiltInStreamProviders(context))
    }
    var catalogProviders by remember(refresh) {
        mutableStateOf(loadCatalogProviders(context))
    }
    var openSubtitles by remember(refresh) {
        mutableStateOf(loadOpenSubtitlesSettings(context))
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                avatarMessage = "Preparando imagem…"

                try {
                    val encodedAvatar = withContext(Dispatchers.IO) {
                        encodeProfileAvatar(context, uri)
                    }
                    avatar = encodedAvatar
                    preferences.edit().putString(PROFILE_AVATAR, encodedAvatar).apply()
                    avatarMessage = "Foto atualizada."
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    avatarMessage = failure.message
                        ?: "Não foi possível usar essa imagem."
                }
            }
        }
    }
    fun saveReleasePreferences(value: ReleasePreferences) {
        releasePreferences = value
        preferences.edit()
            .putString(RELEASE_LANGUAGE, value.language.name)
            .putInt(RELEASE_RESOLUTION, value.resolution ?: 0)
            .apply()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Perfil", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Idioma dos metadados", fontWeight = FontWeight.Bold)
                    Text(
                        "Define o idioma dos títulos e sinopses de animes e episódios.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    PreferenceOption(
                        selected = metadataLanguage == MetadataLanguage.PORTUGUESE,
                        label = "Português"
                    ) {
                        metadataLanguage = MetadataLanguage.PORTUGUESE
                        saveMetadataLanguage(context, metadataLanguage)
                    }
                    PreferenceOption(
                        selected = metadataLanguage == MetadataLanguage.ORIGINAL,
                        label = "Original"
                    ) {
                        metadataLanguage = MetadataLanguage.ORIGINAL
                        saveMetadataLanguage(context, metadataLanguage)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val image = remember(avatar) { avatar?.let(::decodeProfileAvatar) }
                    if (image != null) {
                        Image(image, "Foto do perfil", Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text(name.trim().firstOrNull()?.uppercase() ?: "K", style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) { Text("Escolher foto") }
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true)
                    Button(onClick = {
                        val savedName = name.trim().ifBlank { "Usuário Kitsune" }
                        name = savedName
                        preferences.edit().putString(PROFILE_NAME, savedName).apply()
                    }) { Text("Salvar perfil") }
                    avatarMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Vídeo preferido", fontWeight = FontWeight.Bold)
                    Text("O app procura primeiro opções compatíveis e então escolhe a mais compartilhada.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Idioma", fontWeight = FontWeight.SemiBold)
                    listOf(
                        ReleaseLanguage.ANY to "Qualquer",
                        ReleaseLanguage.PORTUGUESE to "Português",
                        ReleaseLanguage.ENGLISH to "Inglês",
                        ReleaseLanguage.JAPANESE to "Japonês/original",
                        ReleaseLanguage.DUBBED to "Dublado"
                    ).forEach { (value, label) ->
                        PreferenceOption(releasePreferences.language == value, label) {
                            saveReleasePreferences(releasePreferences.copy(language = value))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Qualidade", fontWeight = FontWeight.SemiBold)
                    listOf(null to "Automática", 720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (value, label) ->
                        PreferenceOption(releasePreferences.resolution == value, label) {
                            saveReleasePreferences(releasePreferences.copy(resolution = value))
                        }
                    }
                }
            }
        }
        item {
            DownloadPolicyCard(
                policy = downloadPolicy,
                onChange = { updated ->
                    downloadPolicy = updated
                    saveDownloadPolicy(context, updated)
                }
            )
        }
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
            StremioAddonsCard(
                configs = stremioAddons,
                builtInProviders = builtInStreamProviders,
                onBuiltInProvidersChange = { updated ->
                    builtInStreamProviders = updated
                    saveBuiltInStreamProviders(context, updated)
                },
                onChange = { updated ->
                    stremioAddons = updated
                    saveStremioAddonConfigs(context, updated)
                }
            )
        }
        item {
            OpenSubtitlesCard(
                settings = openSubtitles,
                onChange = { updated ->
                    openSubtitles = updated
                    saveOpenSubtitlesSettings(context, updated)
                }
            )
        }
        item { PerformanceCard(AppPerformance.metrics) }
        item { BackupCard(backupBusy, backupMessage, onExport, onRestore) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun OpenSubtitlesCard(
    settings: OpenSubtitlesSettings,
    onChange: (OpenSubtitlesSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by rememberSaveable(settings.apiKey) {
        mutableStateOf(settings.apiKey)
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
            Text("OpenSubtitles", fontWeight = FontWeight.Bold)
            Text(
                "Busca opcional de legendas. A conta aumenta os limites, mas não é obrigatória.",
                style = MaterialTheme.typography.bodySmall
            )
            SettingsToggle(
                checked = settings.enabled,
                title = if (settings.enabled) "Ativo" else "Desativado",
                onChange = { enabled -> onChange(settings.copy(enabled = enabled)) }
            )
            Text("Idioma", fontWeight = FontWeight.SemiBold)
            OPEN_SUBTITLES_LANGUAGES.forEach { language ->
                PreferenceOption(settings.language == language.code, language.label) {
                    onChange(settings.copy(language = language.code))
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { value -> apiKey = value.take(200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chave da API") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    onChange(settings.copy(apiKey = apiKey.trim()))
                }
            ) {
                Text("Salvar chave")
            }
            Text("Conta (opcional)", fontWeight = FontWeight.SemiBold)
            if (session == null) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { value -> username = value.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Usuário") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { value -> password = value.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    enabled = !loginBusy,
                    onClick = {
                        loginBusy = true
                        loginMessage = "Entrando…"
                        scope.launch {
                            try {
                                val savedKey = apiKey.trim()
                                onChange(settings.copy(apiKey = savedKey))
                                session = withContext(Dispatchers.IO) {
                                    OpenSubtitles.login(context, savedKey, username, password)
                                }
                                password = ""
                                loginMessage = "Conta conectada."
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                loginMessage = failure.message ?: "Não foi possível entrar."
                            } finally {
                                loginBusy = false
                            }
                        }
                    }
                ) {
                    if (loginBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    } else {
                        Text("Entrar")
                    }
                }
            } else {
                Text("Conectado como ${session?.username.orEmpty()}.")
                TextButton(
                    onClick = {
                        clearOpenSubtitlesSession(context)
                        session = null
                        loginMessage = "Sessão removida deste aparelho."
                    }
                ) {
                    Text("Sair")
                }
            }
            loginMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            Text("Outros provedores", fontWeight = FontWeight.Bold)
            listOf("SubDL", "Addic7ed", "Podnapisi").forEach { provider ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, enabled = false, onCheckedChange = null)
                    Text("$provider • Em breve", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            Text("Fontes do catálogo", fontWeight = FontWeight.Bold)
            Text(
                "Os resultados ativos são combinados na busca.",
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Desempenho recente", fontWeight = FontWeight.Bold)
            if (metrics.isEmpty()) {
                Text("As medições aparecerão após usar o catálogo e o player.")
            } else {
                metrics.take(6).forEach { metric ->
                    Text(
                        "${metric.name}: ${metric.durationMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StremioAddonsCard(
    configs: List<StremioAddonConfig>,
    builtInProviders: Set<BuiltInStreamProvider>,
    onBuiltInProvidersChange: (Set<BuiltInStreamProvider>) -> Unit,
    onChange: (List<StremioAddonConfig>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testingUrl by remember { mutableStateOf<String?>(null) }

    fun setAddonEnabled(config: StremioAddonConfig, enabled: Boolean) {
        onChange(updateStremioAddon(configs, config.manifestUrl) { it.copy(enabled = enabled) })
    }

    fun testAddon(config: StremioAddonConfig) {
        testingUrl = config.manifestUrl
        message = "Testando ${config.name ?: "addon"}…"
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    testStremioAddon(config.manifestUrl)
                }
                onChange(updateStremioAddon(configs, config.manifestUrl) { it.copy(name = manifest.name) })
                message = "${manifest.name}: conexão funcionando."
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                message = failure.message ?: "Falha ao testar o addon."
            } finally {
                testingUrl = null
            }
        }
    }

    fun addAddon() {
        val normalizedUrl = try {
            normalizeStremioAddonUrl(input)
        } catch (failure: Exception) {
            message = failure.message ?: "URL de addon inválida."
            return
        }

        testingUrl = normalizedUrl
        message = "Validando manifesto…"
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    testStremioAddon(normalizedUrl)
                }
                val config = StremioAddonConfig(
                    manifestUrl = normalizedUrl,
                    name = manifest.name,
                    priority = configs.size
                )
                onChange((configs + config).distinctBy(StremioAddonConfig::manifestUrl))
                input = ""
                message = "${manifest.name} adicionado e testado."
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                message = failure.message ?: "O addon não respondeu corretamente."
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
            Text("Provedores de vídeo", fontWeight = FontWeight.Bold)
            Text(
                "Use os provedores integrados ou adicione qualquer manifesto Stremio HTTPS compatível.",
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
                            if (added) "Adicionado à busca" else "Disponível para adicionar",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = {
                        onBuiltInProvidersChange(
                            if (added) builtInProviders - provider else builtInProviders + provider
                        )
                    }) { Text(if (added) "Remover" else "Adicionar") }
                }
            }

            configs.sortedBy(StremioAddonConfig::priority).forEachIndexed { index, config ->
                StremioAddonRow(
                    config = config,
                    index = index,
                    lastIndex = configs.lastIndex,
                    testing = testingUrl != null,
                    onEnabledChange = { enabled -> setAddonEnabled(config, enabled) },
                    onMove = { direction ->
                        onChange(moveStremioAddon(configs, config.manifestUrl, direction))
                    },
                    onTest = { testAddon(config) },
                    onRemove = {
                        onChange(configs.filterNot { it.manifestUrl == config.manifestUrl })
                    }
                )
            }

            StremioAddonForm(
                value = input,
                busy = testingUrl != null,
                onValueChange = { updated ->
                    input = updated
                    message = null
                },
                onAdd = ::addAddon
            )
            message?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StremioAddonRow(
    config: StremioAddonConfig,
    index: Int,
    lastIndex: Int,
    testing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsToggle(
            checked = config.enabled,
            title = config.name ?: "Addon ${index + 1}",
            supportingText = config.manifestUrl,
            onChange = onEnabledChange
        )
        Text(
            text = if (config.enabled) "Prioridade ${index + 1}" else "Desativado",
            style = MaterialTheme.typography.labelSmall
        )
        Row {
            TextButton(enabled = index > 0, onClick = { onMove(-1) }) { Text("Subir") }
            TextButton(enabled = index < lastIndex, onClick = { onMove(1) }) { Text("Descer") }
            TextButton(enabled = !testing, onClick = onTest) { Text("Testar") }
            TextButton(onClick = onRemove) { Text("Remover") }
        }
    }
}

@Composable
private fun StremioAddonForm(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("URL do provedor (manifest.json)") },
        singleLine = true
    )
    Button(onClick = onAdd, enabled = value.isNotBlank() && !busy) {
        Text("Adicionar provedor Stremio")
    }
}

@Composable
private fun DownloadPolicyCard(
    policy: DownloadPolicyPreferences,
    onChange: (DownloadPolicyPreferences) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Downloads", fontWeight = FontWeight.Bold)
            Text(
                "Limites aplicados automaticamente durante o download.",
                style = MaterialTheme.typography.bodySmall
            )
            SettingsToggle(
                checked = policy.wifiOnly,
                title = "Baixar apenas por Wi-Fi",
                onChange = { enabled ->
                    onChange(policy.copy(wifiOnly = enabled))
                }
            )
            SettingsToggle(
                checked = policy.pauseOnLowBattery,
                title = "Pausar com bateria em 15%",
                onChange = { enabled ->
                    onChange(policy.copy(pauseOnLowBattery = enabled))
                }
            )
            SettingsToggle(
                checked = policy.preserveStorage,
                title = "Preservar 1 GiB de espaço livre",
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
            ?: error("Imagem inválida.")
    }
    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val bytes = ByteArrayOutputStream().use { output ->
        check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Não foi possível preparar a imagem." }
        output.toByteArray()
    }
    require(bytes.size <= 512 * 1024) { "A imagem de perfil ficou grande demais." }
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
            Text("Dados e backup", fontWeight = FontWeight.Bold)
            Text("Guarde favoritos, histórico, progresso e preferências fora do aplicativo.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport, enabled = !busy) { Text("Exportar") }
                TextButton(onClick = onRestore, enabled = !busy) { Text("Restaurar") }
                if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun UpdateDialog() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var visible by remember { mutableStateOf(false) }
    var ignoreVersion by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var downloadId by rememberSaveable { mutableStateOf(preferences.getLong("update_download_id", -1)) }

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
                message = "Versão ${available.version} disponível."
                visible = true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@LaunchedEffect
        }
    }

    LaunchedEffect(downloadId) {
        if (downloadId >= 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())) {
            if (AppUpdater.install(context, downloadId)) {
                preferences.edit().remove("update_download_id").apply()
                downloadId = -1
            }
        }
    }

    DisposableEffect(downloadId) {
        val receiver = if (downloadId >= 0) object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    message = "Download concluído. Permita instalar apps desta fonte para continuar."
                } else if (AppUpdater.install(context, downloadId)) {
                    preferences.edit().remove("update_download_id").apply()
                    downloadId = -1
                }
            }
        } else null
        receiver?.let {
            ContextCompat.registerReceiver(context, it, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
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

    if (visible && release != null) AlertDialog(
        onDismissRequest = {
            if (ignoreVersion) preferences.edit().putString(IGNORED_UPDATE, release?.version).apply()
            visible = false
        },
        title = { Text("Atualização disponível") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message)
                Row(
                    Modifier.fillMaxWidth().clickable { ignoreVersion = !ignoreVersion },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(ignoreVersion, { ignoreVersion = it })
                    Text("Não mostrar novamente esta versão")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    message = "Permita instalar apps desta fonte e toque em baixar novamente."
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    downloadId = AppUpdater.download(context, requireNotNull(release))
                    preferences.edit().putLong("update_download_id", downloadId).apply()
                    message = "Baixando atualização pelo GitHub…"
                }
            }) { Text("Baixar") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (ignoreVersion) preferences.edit().putString(IGNORED_UPDATE, release?.version).apply()
                visible = false
            }) { Text("Agora não") }
        }
    )
}
