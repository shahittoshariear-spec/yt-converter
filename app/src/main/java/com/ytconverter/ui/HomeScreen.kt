package com.ytconverter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ytconverter.R
import com.ytconverter.data.AudioFormat
import com.ytconverter.data.DownloadRecord
import com.ytconverter.downloader.FailureKind
import com.ytconverter.downloader.JobPhase
import com.ytconverter.downloader.JobStatus
import com.ytconverter.downloader.QueueItem
import com.ytconverter.ui.theme.LocalBrandPalette
import kotlinx.coroutines.delay
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
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val playlistHint by viewModel.playlistHint.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    // Sections that have already animated in, so scrolling back does not replay it.
    val seenSections = remember { mutableSetOf<String>() }

    val working = queue.any { it.isActive }

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
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )
            )
    ) {
        AmbientBackground(alive = working, modifier = Modifier.matchParentSize())

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            ShariearTag(
                                textStyle = MaterialTheme.typography.labelSmall,
                                glowRadius = 8.dp,
                            )
                        }
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
                    StaggeredAppear("link", seenSections, 0) {
                        LinkCard(
                            url = url,
                            enabled = !resolving,
                            onUrlChange = viewModel::onUrlChange,
                            onPaste = viewModel::pasteFromClipboard,
                            onClear = viewModel::clearUrl,
                            onSubmit = viewModel::start,
                        )
                    }
                }

                item("format") {
                    StaggeredAppear("format", seenSections, 1) {
                        FormatCard(selected = format, onSelect = viewModel::onFormatChange)
                    }
                }

                item("convert") {
                    StaggeredAppear("convert", seenSections, 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConvertButton(
                                enabled = url.isNotBlank() && !resolving,
                                busy = resolving,
                                onClick = viewModel::start,
                            )
                            if (playlistHint) {
                                TextButton(
                                    onClick = viewModel::startWithPlaylist,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_playlist_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.playlist_add))
                                }
                            }
                        }
                    }
                }

                if (queue.isNotEmpty()) {
                    item("queue-header") {
                        SectionHeader(
                            title = stringResource(R.string.queue_title),
                            action = when {
                                queue.any { it.isActive } -> stringResource(R.string.queue_cancel_all) to viewModel::cancelAll
                                queue.any { !it.isActive } -> stringResource(R.string.queue_clear_finished) to viewModel::clearFinished
                                else -> null
                            },
                        )
                    }
                    items(queue, key = { it.id }) { item ->
                        QueueRow(
                            item = item,
                            onCancel = { viewModel.cancelItem(item.id) },
                            onRetry = { viewModel.retry(item.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                item("downloads-header") {
                    SectionHeader(
                        title = stringResource(R.string.recent),
                        action = if (downloads.isNotEmpty()) {
                            stringResource(R.string.clear_all) to viewModel::clearAll
                        } else {
                            null
                        },
                    )
                }

                if (downloads.isEmpty() && queue.isEmpty()) {
                    item("empty") { EmptyState() }
                } else {
                    items(downloads, key = { it.id }) { record ->
                        DownloadRow(
                            record = record,
                            onOpen = { openRecord(context, record) },
                            onShare = { shareRecord(context, record) },
                            onDelete = { viewModel.delete(record) },
                            modifier = Modifier.animateItem(),
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

/**
 * One soft violet wash behind everything, instead of layered violet and green ones.
 *
 * Two different hues over a dark background mix towards grey and read as a smear; a
 * single hue tinted gently from the top just adds depth. It only drifts while the
 * queue is busy, so an idle screen costs nothing to keep on screen.
 */
@Composable
private fun AmbientBackground(alive: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val wash = if (isDark) 0.13f else 0.07f

    val drift = if (alive) {
        val transition = rememberInfiniteTransition(label = "ambient")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "drift",
        ).value
    } else {
        0.5f
    }

    Box(
        modifier = modifier.drawBehind {
            val radius = size.maxDimension * 0.95f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = wash), Color.Transparent),
                    center = Offset(size.width * (0.5f + 0.05f * drift), -size.height * 0.04f),
                    radius = radius,
                )
            )
        }
    )
}

/**
 * Fades and lifts a section into place, so the screen assembles instead of appearing.
 *
 * [seen] is hoisted above the list because items are disposed when scrolled away, and
 * without it the entrance would replay on every scroll back.
 */
@Composable
private fun StaggeredAppear(
    key: String,
    seen: MutableSet<String>,
    index: Int,
    content: @Composable () -> Unit,
) {
    val alreadySeen = key in seen
    var visible by remember { mutableStateOf(alreadySeen) }
    LaunchedEffect(key) {
        if (!alreadySeen) {
            delay(70L * index)
            seen.add(key)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320)) + slideInVertically(tween(380)) { height -> height / 5 },
    ) {
        content()
    }
}

/**
 * The one card style used across the screen.
 *
 * `surfaceContainer` plus a hairline outline is what gives every panel an edge in
 * dark mode; at the default one-dp elevation the Material tint is far too weak to
 * separate a card from the background.
 */
@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        content = content,
    )
}

private const val PULSE_CYCLES = 3
private const val PULSE_MIN = 0.55f

/**
 * The family signature, in gradient text on a pill with a pulsing bloom.
 *
 * `Modifier.blur` only works on API 31+, so the inner radial bloom is drawn on every
 * version and the outward halo is a bonus on newer devices.
 *
 * The pulse is finite: it lights up when the app opens and then settles, so a screen
 * that is just sitting there schedules no animation frames at all.
 */
@Composable
private fun ShariearTag(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    glowRadius: Dp = 16.dp,
) {
    val brand = LocalBrandPalette.current
    val shape = RoundedCornerShape(percent = 50)
    val gradient = Brush.linearGradient(listOf(brand.accentStart, brand.accentEnd))

    val glow = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        repeat(PULSE_CYCLES) {
            glow.animateTo(PULSE_MIN, tween(durationMillis = 1400, easing = LinearEasing))
            glow.animateTo(1f, tween(durationMillis = 1400, easing = LinearEasing))
        }
    }
    val pulseValue = glow.value

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = pulseValue }
                .blur(glowRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(gradient, shape)
        )
        Box(
            modifier = Modifier
                .clip(shape)
                .background(gradient)
                .padding(1.5.dp)
                .background(brand.signatureFill, shape)
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                brand.gradientStart.copy(alpha = 0.34f * pulseValue),
                                brand.gradientEnd.copy(alpha = 0.14f * pulseValue),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.maxDimension * 0.62f,
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.signature),
                style = textStyle.copy(
                    brush = gradient,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                ),
            )
        }
    }
}

@Composable
private fun BrandMark() {
    val brand = LocalBrandPalette.current
    Box(
        modifier = Modifier
            .padding(start = 20.dp, end = 6.dp)
            .size(32.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Brush.linearGradient(listOf(brand.gradientStart, brand.gradientEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_note),
            contentDescription = null,
            tint = brand.onGradient,
            modifier = Modifier.size(19.dp),
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
    AppCard {
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
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.format_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FormatSelector(selected = selected, onSelect = onSelect)
            Text(
                text = stringResource(selected.blurbRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Segmented control whose highlight slides between the options. */
@Composable
private fun FormatSelector(selected: AudioFormat, onSelect: (AudioFormat) -> Unit) {
    val entries = AudioFormat.entries
    val density = LocalDensity.current
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val segmentPx = if (contentWidthPx > 0) contentWidthPx.toFloat() / entries.size else 0f
    val index = entries.indexOf(selected).coerceAtLeast(0)

    val offsetPx by animateFloatAsState(
        targetValue = segmentPx * index,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "segmentOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
            .onSizeChanged { contentWidthPx = it.width },
    ) {
        Box(Modifier.matchParentSize()) {
            if (segmentPx > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(offsetPx.roundToInt(), 0) }
                        .width(with(density) { segmentPx.toDp() })
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            entries.forEach { entry ->
                val active = entry == selected
                val contentColor by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "segmentLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { onSelect(entry) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(entry.labelRes),
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConvertButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val brand = LocalBrandPalette.current
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "buttonScale",
    )
    val contentColor =
        if (enabled) brand.onGradient else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (enabled) 14.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = brand.gradientStart,
                spotColor = brand.gradientEnd,
            )
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.horizontalGradient(listOf(brand.gradientStart, brand.gradientEnd))
                } else {
                    // A flat theme colour, not a faded gradient: dimming the gradient
                    // used to drop the label to roughly 1:1 contrast.
                    SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
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
                    color = contentColor,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(
                    when {
                        busy -> R.string.preparing
                        else -> R.string.convert
                    }
                ),
                color = contentColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Thumbnail(item.thumbnailUrl, width = 88.dp, shape = MaterialTheme.shapes.small)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title.ifBlank { item.url },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusLine(item)
                }
                FormatPill(format = item.format)
            }

            if (item.isRunning) {
                LifeProgress(item.progress)
            }

            item.errorKind?.let { kind ->
                Text(
                    text = friendlyError(kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val action: Pair<String, () -> Unit>? = when (item.status) {
                JobStatus.RUNNING -> stringResource(R.string.cancel) to onCancel
                JobStatus.QUEUED -> stringResource(R.string.remove) to onCancel
                JobStatus.FAILED, JobStatus.CANCELED -> stringResource(R.string.retry) to onRetry
                JobStatus.FINISHED -> null
            }
            action?.let { (label, handler) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = handler) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(item: QueueItem, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = when (item.status) {
        JobStatus.FAILED -> colors.error
        JobStatus.FINISHED -> colors.secondary
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.isRunning) PulsingDot(color = colors.primary)
        Text(
            text = queueStatusLine(item),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PulsingDot(color: Color, size: Dp = 6.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val scale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(color)
    )
}

/** Progress that glides to its target instead of jumping between updates. */
@Composable
private fun LifeProgress(progress: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val bar = modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(shape)

    if (progress < 0f) {
        LinearProgressIndicator(
            modifier = bar,
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
        )
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 450, easing = LinearEasing),
            label = "progressFill",
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = bar,
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: Pair<String, () -> Unit>?) {
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
        action?.let { (label, handler) ->
            TextButton(onClick = handler) { Text(label) }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val subtitle = listOf(
        record.format.shortName,
        formatSize(record.sizeBytes),
        formatDuration(record.durationSeconds),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    AppCard(
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
    // Neutral on purpose: the format is metadata, not a status, so it should not
    // introduce a second hue next to the violet accent.
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = format.shortName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
        }
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
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
    val trimNonMusic by viewModel.trimNonMusic.collectAsStateWithLifecycle()
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_sponsorblock),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.settings_sponsorblock_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = trimNonMusic, onCheckedChange = viewModel::setTrimNonMusic)
                }

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

                HorizontalDivider()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.about_credit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_blurb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
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

@Composable
private fun queueStatusLine(item: QueueItem): String {
    val context = LocalContext.current
    return when (item.status) {
        JobStatus.QUEUED -> context.getString(R.string.status_queued)
        JobStatus.FINISHED -> context.getString(R.string.status_done)
        JobStatus.CANCELED -> context.getString(R.string.status_canceled)
        JobStatus.FAILED -> context.getString(R.string.status_failed)
        JobStatus.RUNNING -> item.note ?: when (item.phase) {
            JobPhase.PREPARING -> context.getString(R.string.engine_setup)
            JobPhase.REPAIRING -> context.getString(R.string.phase_repairing)
            JobPhase.CONVERTING -> context.getString(R.string.converting)
            JobPhase.DOWNLOADING -> if (item.progress >= 0f) {
                buildString {
                    append("${(item.progress * 100).roundToInt()}%")
                    if (item.etaSeconds > 0) append(" · ${item.etaSeconds}s")
                }
            } else {
                context.getString(R.string.downloading)
            }
        }
    }
}

@Composable
private fun friendlyError(kind: FailureKind): String = stringResource(
    when (kind) {
        FailureKind.AGE_RESTRICTED -> R.string.err_age_restricted
        FailureKind.STALE_EXTRACTOR -> R.string.err_stale_engine
        FailureKind.NETWORK -> R.string.err_network
        FailureKind.UNAVAILABLE -> R.string.err_unavailable
        FailureKind.UNKNOWN -> R.string.notif_failed
    }
)

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
