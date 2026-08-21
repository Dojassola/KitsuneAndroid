package com.kitsuneandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryHubScreen(
    episodes: List<TorrentDownload>,
    mediaLists: List<MediaList>,
    offlineAnimeIds: Set<Int>,
    onSelect: (Anime) -> Unit,
    onPlay: (TorrentDownload) -> Unit,
    onOpenVideo: () -> Unit,
    onRemove: (TorrentDownload) -> Unit,
    onDataChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val titles = listOf(
        stringResource(R.string.offline),
        stringResource(R.string.lists)
    )

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) }
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> LibraryScreen(episodes, onPlay, onOpenVideo, onRemove)
                else -> MediaListsPage(
                    lists = mediaLists,
                    offlineAnimeIds = offlineAnimeIds,
                    onSelect = onSelect,
                    onDataChanged = onDataChanged
                )
            }
        }
    }
}

@Composable
private fun MediaListsPage(
    lists: List<MediaList>,
    offlineAnimeIds: Set<Int>,
    onSelect: (Anime) -> Unit,
    onDataChanged: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var selectedListId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingList by remember { mutableStateOf<MediaList?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MediaList?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(lists, selectedListId) {
        if (lists.none { list -> list.id == selectedListId }) {
            selectedListId = lists.firstOrNull()?.id
        }
    }
    LaunchedEffect(selectedListId) {
        gridState.scrollToItem(0)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    val count = withContext(Dispatchers.IO) {
                        MediaListRepository.import(context, uri)
                    }
                    onDataChanged()
                    resources.getString(R.string.lists_imported_count, count)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    AppErrors.record("lists.import", failure)
                    failure.message ?: resources.getString(R.string.error_import_lists)
                }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        MediaListRepository.export(context, uri)
                    }
                    resources.getString(R.string.lists_exported)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    AppErrors.record("lists.export", failure)
                    failure.message ?: resources.getString(R.string.error_export_lists)
                }
            }
        }
    }

    if (creating || editingList != null) {
        ListNameDialog(
            initialName = editingList?.name.orEmpty(),
            onDismiss = {
                creating = false
                editingList = null
            },
            onConfirm = { name ->
                val editing = editingList
                if (editing == null) {
                    selectedListId = MediaListRepository.create(context, name).id
                } else {
                    MediaListRepository.rename(context, editing.id, name)
                }
                creating = false
                editingList = null
                onDataChanged()
            }
        )
    }
    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_list)) },
            text = { Text(stringResource(R.string.confirm_delete_list, list.name)) },
            confirmButton = {
                Button(onClick = {
                    MediaListRepository.delete(context, list.id)
                    deleting = null
                    onDataChanged()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val selectedList = lists.firstOrNull { list -> list.id == selectedListId }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { creating = true }) {
                Text(stringResource(R.string.new_list))
            }
            TextButton(onClick = { importer.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                Text(stringResource(R.string.import_action))
            }
            TextButton(
                enabled = lists.isNotEmpty(),
                onClick = { exporter.launch("Kitsune-lists.json") }
            ) {
                Text(stringResource(R.string.export))
            }
        }
        message?.let { value ->
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (lists.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lists.forEach { list ->
                    FilterChip(
                        selected = list.id == selectedListId,
                        onClick = { selectedListId = list.id },
                        label = { Text("${list.name} (${list.items.size})") }
                    )
                }
            }
        }
        selectedList?.let { list ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { editingList = list }) {
                    Text(stringResource(R.string.rename))
                }
                TextButton(onClick = { deleting = list }) {
                    Text(stringResource(R.string.delete))
                }
            }
            Catalog(
                items = list.items,
                state = gridState,
                loading = false,
                error = null,
                emptyMessage = stringResource(R.string.empty_list),
                offlineAnimeIds = offlineAnimeIds,
                onRetry = onDataChanged,
                onSelect = onSelect
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.create_first_list))
        }
    }
}

@Composable
private fun ListNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initialName.isBlank()) R.string.new_list else R.string.rename_list))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { value -> name = value.take(60) },
                label = { Text(stringResource(R.string.list_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun MediaListMembershipDialog(
    anime: Anime,
    lists: List<MediaList>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var newListName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_list)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lists.forEach { list ->
                    val included = list.items.any { item -> item.id == anime.id }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = included,
                            onCheckedChange = { checked ->
                                MediaListRepository.setItem(context, list.id, anime, checked)
                                onChanged()
                            }
                        )
                        Text(list.name)
                    }
                }
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { value -> newListName = value.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.new_list)) },
                    singleLine = true
                )
                Button(
                    enabled = newListName.isNotBlank(),
                    onClick = {
                        val created = MediaListRepository.create(context, newListName)
                        MediaListRepository.setItem(context, created.id, anime, true)
                        newListName = ""
                        onChanged()
                    }
                ) {
                    Text(stringResource(R.string.create_and_add))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        }
    )
}
