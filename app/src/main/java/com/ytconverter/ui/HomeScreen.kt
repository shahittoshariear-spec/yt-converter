package com.ytconverter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ytconverter.R
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import com.ytconverter.downloader.DownloadUiState
import com.ytconverter.ui.theme.Mint
import com.ytconverter.ui.theme.Violet
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val url by viewModel.url.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val downloads by viewModel.items.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRequestNotificationPermission() }
    LaunchedEffect(Unit) { viewModel.prefillFromClipboardIfLink() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHost.showSnackbar(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = { BrandMark() },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.settings),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("link") {
                    LinkCard(
                        url = url,
                        enabled = active == null,
                        onUrlChange = viewModel::onUrlChange,
                        onPaste = viewModel::pasteFromClipboard,
                        onClear = viewModel::clearUrl,
                        onSubmit = viewModel::start,
                    )
                }

                item("format") {
                    FormatCard(selected = format, onSelect = viewModel::onFormatChange)
                }

                item("convert") {
                    ConvertButton(
                        enabled = active == null && url.isNotBlank(),
                        busy = active != null,
                        onClick = viewModel::start,
                    )
                }

                active?.let { state ->
                    item("active") {
                        ActiveCard(state = state, onCancel = viewModel::cancel)
                    }
                }

                item("header") {
                    SectionHeader(
                        title = stringResource(R.string.recent),
                        onClearAll = if (downloads.isNotEmpty()) viewModel::clearAll else null,
                    )
                }

                if (downloads.isEmpty()) {
                    item("empty") { EmptyState() }
                } else {
                    items(downloads, key = { it.id }) { record ->
                        DownloadRow(
                            record = record,
                            onOpen = { openRecord(context, record) },
                            onShare = { shareRecord(context, record) },
                            onDelete = { viewModel.delete(record) },
                        )
                    }
                }

                item("footer") { Footer(folder = folder) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .padding(start = 20.dp, end = 6.dp)
            .size(34.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Brush.linearGradient(listOf(Violet, Mint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_note),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LinkCard(
    url: String,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            label = { Text(stringResource(R.string.url_label)) },
            placeholder = {
                Text(
                    text = stringResource(R.string.url_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_link), contentDescription = null)
            },
            trailingIcon = {
                if (url.isEmpty()) {
                    IconButton(onClick = onPaste, enabled = enabled) {
                        Icon(
                            painter = painterResource(R.drawable.ic_paste),
                            contentDescription = stringResource(R.string.paste),
                        )
                    }
                } else {
                    IconButton(onClick = onClear, enabled = enabled) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.clear),
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (enabled) onSubmit() }),
        )
    }
}

@Composable
private fun FormatCard(selected: AudioFormat, onSelect: (AudioFormat) -> Unit) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.format_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AudioFormat.entries.forEach { option ->
                    Segment(
                        label = stringResource(option.labelRes),
                        active = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                text = stringResource(selected.blurbRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(180),
        label = "segmentContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "segmentContent",
    )

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConvertButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Violet, Mint))
                else SolidColor(MaterialTheme.colorScheme.surfaceVariant)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(if (busy) R.string.converting else R.string.convert),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ActiveCard(state: DownloadUiState, onCancel: () -> Unit) {
    val context = LocalContext.current
    val determinate = state.progress >= 0f

    val status = when {
        state.phase == DownloadUiState.Phase.PREPARING -> context.getString(R.string.engine_setup)
        state.phase == DownloadUiState.Phase.CONVERTING -> context.getString(R.string.converting)
        determinate -> buildString {
            append("${(state.progress * 100).roundToInt()}%")
            if (state.etaSeconds > 0) append(" · ${state.etaSeconds}s left")
        }

        else -> context.getString(R.string.downloading)
    }

    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(url = state.thumbnailUrl, width = 84.dp, shape = MaterialTheme.shapes.small)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.uploader?.let { uploader ->
                        Text(
                            text = uploader,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FormatPill(format = state.format)
            }

            if (determinate) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Violet,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Violet,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onClearAll: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onClearAll != null) {
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.clear_all))
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val subtitle = listOf(
        record.format.shortName,
        formatSize(record.sizeBytes),
        formatDuration(record.durationSeconds),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Thumbnail(url = record.thumbnailUrl, width = 88.dp, shape = MaterialTheme.shapes.small)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(url: String?, width: Dp, shape: Shape) {
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FormatPill(format: AudioFormat) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = format.shortName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Violet.copy(alpha = 0.18f), Mint.copy(alpha = 0.18f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = Violet,
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Footer(folder: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.saved_to, folder),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    var draft by remember(folder) { mutableStateOf(folder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.settings_folder)) },
                    supportingText = { Text(stringResource(R.string.settings_folder_help)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.settings_engine),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_engine_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = viewModel::updateEngine,
                    enabled = engineStatus != EngineStatus.UPDATING,
                ) {
                    if (engineStatus == EngineStatus.UPDATING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            if (engineStatus == EngineStatus.UPDATING) R.string.engine_updating
                            else R.string.settings_engine_update
                        )
                    )
                }

                when (engineStatus) {
                    EngineStatus.UPDATED -> Text(
                        text = stringResource(R.string.engine_updated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )

                    EngineStatus.FAILED -> Text(
                        text = stringResource(R.string.engine_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.setFolder(draft)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.settings_close))
            }
        },
    )
}

private fun openRecord(context: Context, record: DownloadRecord) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(record.uri), record.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

private fun shareRecord(context: Context, record: DownloadRecord) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(record.mimeType)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(record.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[index])
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return ""
    val total = seconds.toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}
