package app.lightneet.ui.browser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lightneet.R
import app.lightneet.core.vault.Entry
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Screen
import app.lightneet.ui.SortMode
import app.lightneet.ui.common.ConfirmDialog
import app.lightneet.ui.common.ProgressBanner
import app.lightneet.ui.common.TextInputDialog
import app.lightneet.ui.common.formatDate
import app.lightneet.ui.common.formatSize
import app.lightneet.ui.theme.LnColors

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val entry: Entry) : Dialog
    data object ConfirmDelete : Dialog
    data object Move : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, snackbar: SnackbarHostState, beforeExternal: () -> Unit) {
    val entries by vm.entries.collectAsState()
    val crumbs by vm.breadcrumbs.collectAsState()
    val selection by vm.selection.collectAsState()
    val progress by vm.progress.collectAsState()
    val grid by vm.gridView.collectAsState()
    val sort by vm.sortMode.collectAsState()
    val pendingShared by vm.pendingShared.collectAsState()
    val originals by vm.offerDeleteOriginals.collectAsState()

    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris) }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? -> tree?.let(vm::exportSelected) }

    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && crumbs.isNotEmpty()) { vm.up() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected, selection.size, selection.size)) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
                        if (selection.size == 1) {
                            IconButton(onClick = { vm.entry(selection.first())?.let { dialog = Dialog.Rename(it) } }) {
                                Icon(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.rename))
                            }
                        }
                        IconButton(onClick = { dialog = Dialog.Move }) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.move)) }
                        IconButton(onClick = { dialog = Dialog.ConfirmDelete }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = LnColors.Danger) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            // Same title in every vault: a glance at the screen must not reveal which one is open.
                            crumbs.lastOrNull()?.name ?: stringResource(R.string.vault_title),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (crumbs.isNotEmpty()) IconButton(onClick = { vm.up() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort)) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(SortMode.NAME to R.string.sort_name, SortMode.NEWEST to R.string.sort_newest, SortMode.LARGEST to R.string.sort_largest).forEach { (m, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label), color = if (m == sort) LnColors.Accent else LnColors.Text) },
                                        onClick = { vm.setSort(m); sortMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = vm::toggleGrid) {
                            Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, stringResource(R.string.toggle_view))
                        }
                        IconButton(onClick = { vm.loadSettings(); vm.screen.value = Screen.Settings }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                        IconButton(onClick = vm::lock) { Icon(Icons.Outlined.Lock, stringResource(R.string.lock)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        floatingActionButton = {
            if (!selecting && progress == null) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(Icons.Outlined.Add, null) },
                        text = { Text(stringResource(R.string.add)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_files)) },
                            leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                            onClick = { addMenu = false; beforeExternal(); importLauncher.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.take_photo)) },
                            leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                            onClick = { addMenu = false; vm.openCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_folder)) },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                            onClick = { addMenu = false; dialog = Dialog.NewFolder },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (entries.isEmpty()) {
                EmptyState(Modifier.align(Alignment.Center))
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.id }) { e ->
                        Tile(vm, e, e.id in selection, selecting)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(entries, key = { it.id }) { e -> ListRow(vm, e, e.id in selection, selecting) }
                }
            }

            if (selecting) {
                Row(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { beforeExternal(); exportTree.launch(null) },
                        icon = { Icon(Icons.Outlined.SaveAlt, null) },
                        text = { Text(stringResource(R.string.export)) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = LnColors.Text,
                    )
                }
            }

            AnimatedVisibility(progress != null, Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                progress?.let { ProgressBanner(it, vm::cancelOperation) }
            }
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> TextInputDialog(stringResource(R.string.new_folder), "", stringResource(R.string.create), vm::createFolder) { dialog = null }
        is Dialog.Rename -> TextInputDialog(stringResource(R.string.rename), d.entry.name, stringResource(R.string.save), { vm.rename(d.entry.id, it); vm.clearSelection() }) { dialog = null }
        Dialog.ConfirmDelete -> ConfirmDialog(
            stringResource(R.string.delete_confirm_title),
            pluralStringResource(R.plurals.delete_confirm_body, selection.size, selection.size),
            stringResource(R.string.delete), vm::deleteSelected, { dialog = null }, danger = true,
        )
        Dialog.Move -> MoveDialog(vm) { dialog = null }
        null -> Unit
    }

    if (pendingShared.isNotEmpty()) {
        ConfirmDialog(
            stringResource(R.string.shared_title),
            pluralStringResource(R.plurals.shared_body, pendingShared.size, pendingShared.size),
            stringResource(R.string.import_action), vm::importPendingShared, { vm.pendingShared.value = emptyList() },
        )
    }
    if (originals.isNotEmpty() && progress == null) {
        ConfirmDialog(
            stringResource(R.string.originals_title),
            pluralStringResource(R.plurals.originals_body, originals.size, originals.size),
            stringResource(R.string.delete), vm::deleteOriginals, { vm.offerDeleteOriginals.value = emptyList() }, danger = true,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = LnColors.Outline)
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(stringResource(R.string.empty_body), color = LnColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(vm: AppViewModel, e: Entry, selected: Boolean, selecting: Boolean) {
    val thumb by produceState<ImageBitmap?>(null, e.id, e.hasThumbnail) { value = vm.thumbnail(e) }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = { if (selecting) vm.toggleSelect(e.id) else vm.open(e) },
                onLongClick = { vm.toggleSelect(e.id) },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(2.dp, LnColors.Accent, MaterialTheme.shapes.medium) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(iconFor(e), null, Modifier.size(36.dp), tint = if (e.isFolder) LnColors.Accent else LnColors.TextMuted)
            if (selected) Icon(Icons.Outlined.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = LnColors.Accent)
        }
        Text(
            e.name, Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(vm: AppViewModel, e: Entry, selected: Boolean, selecting: Boolean) {
    val thumb by produceState<ImageBitmap?>(null, e.id, e.hasThumbnail) { value = vm.thumbnail(e) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = { if (selecting) vm.toggleSelect(e.id) else vm.open(e) },
                onLongClick = { vm.toggleSelect(e.id) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(iconFor(e), null, tint = if (e.isFolder) LnColors.Accent else LnColors.TextMuted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (e.isFolder) formatDate(e.createdAt) else "${formatSize(e.size)} · ${formatDate(e.createdAt)}",
                style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted,
            )
        }
    }
}

@Composable
private fun MoveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val selection = vm.selection.collectAsState().value
    val folders = remember { vm.folders().filter { it.id !in selection } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to)) },
        text = {
            LazyColumn {
                item { FolderRow(stringResource(R.string.vault_root)) { vm.moveSelected(Entry.ROOT_ID); onDismiss() } }
                items(folders, key = { it.id }) { f -> FolderRow(f.name) { vm.moveSelected(f.id); onDismiss() } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = LnColors.TextMuted) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Folder, null, tint = LnColors.Accent)
        Spacer(Modifier.width(12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun iconFor(e: Entry): ImageVector = when {
    e.isFolder -> Icons.Outlined.Folder
    e.mimeType.startsWith("image/") -> Icons.Outlined.Image
    e.mimeType.startsWith("video/") -> Icons.Outlined.Movie
    e.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
    e.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    e.mimeType.startsWith("text/") -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
