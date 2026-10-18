@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ssdplayer.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.videoFramePercent

private val Bg = Color(0xFF0A0A0C)
private val Muted = Color(0xFFA8A8B0)
private val Glass = Color.White.copy(alpha = 0.14f)
private val SidePad = 64.dp

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Color.White, background = Bg, surface = Color(0xFF16161A)),
            ) {
                HomeScreen(vm)
            }
        }
    }
}

private data class Shelf(val title: String, val videos: List<Video>)

@Composable
private fun HomeScreen(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.25).build() }
            .crossfade(true)
            .build()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setFolder(uri)
        }
    }
    val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.useMediaStore()
        else Toast.makeText(ctx, "Video permission is needed to scan this device", Toast.LENGTH_LONG).show()
    }
    fun scanDevice() {
        if (ContextCompat.checkSelfPermission(ctx, videoPermission) == PackageManager.PERMISSION_GRANTED) vm.useMediaStore()
        else runCatching { permissionLauncher.launch(videoPermission) }
    }
    fun chooseFolder() {
        try {
            picker.launch(null)
        } catch (e: Exception) {
            // Some TVs ship without a folder picker: fall back to scanning the device.
            Toast.makeText(ctx, "No folder picker on this TV, scanning the device instead", Toast.LENGTH_LONG).show()
            scanDevice()
        }
    }

    // Bumped on every resume so "Up Next" reflects what was just played.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val rows = remember(vm.videos, tick) {
        val resume = vm.videos
            .filter { Progress.position(ctx, it.uri.toString()) > 0 }
            .sortedByDescending { Progress.lastPlayed(ctx, it.uri.toString()) }
        buildList {
            if (resume.isNotEmpty()) add(Shelf("Up Next", resume))
            vm.videos.groupBy { it.folder }.forEach { (f, v) -> add(Shelf(f, v)) }
        }
    }

    var focused by remember { mutableStateOf<Video?>(null) }
    val hero = focused ?: rows.firstOrNull()?.videos?.firstOrNull()
    val listState = rememberLazyListState()
    var focusedRow by remember { mutableIntStateOf(0) }
    // Keep the focused row pinned under the hero so the previous row never peeks in above it.
    LaunchedEffect(focusedRow, rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(focusedRow.coerceIn(0, rows.lastIndex))
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Backdrop: the focused video's frame, heavily scrimmed so text stays readable.
        Crossfade(targetState = hero, label = "backdrop") { v ->
            if (v != null) Thumb(v, loader, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg.copy(alpha = 0.94f), 0.55f to Bg.copy(alpha = 0.6f), 1f to Bg.copy(alpha = 0.3f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Bg.copy(alpha = 0.6f), 0.3f to Color.Transparent, 0.58f to Bg, 1f to Bg)
            )
        )

        Column(Modifier.fillMaxSize().padding(start = SidePad, top = 28.dp)) {
            Header(
                onRescan = vm::refresh,
                onChange = vm::resetSource,
                showActions = vm.hasFolder,
            )

            if (!vm.hasFolder) {
                EmptyState(onPick = { chooseFolder() }, onScan = { scanDevice() })
            } else {
                HeroInfo(hero, loading = vm.loading)
                val first = remember { FocusRequester() }
                LaunchedEffect(rows.isNotEmpty()) {
                    if (rows.isNotEmpty()) runCatching { first.requestFocus() }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(rows.size) { r ->
                        val row = rows[r]
                        Column {
                            Text(
                                row.title,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 4.dp, end = SidePad),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                contentPadding = PaddingValues(start = 6.dp, end = SidePad, top = 14.dp, bottom = 6.dp),
                            ) {
                                items(row.videos.size) { i ->
                                    val v = row.videos[i]
                                    VideoCard(
                                        video = v,
                                        loader = loader,
                                        progress = Progress.fraction(ctx, v.uri.toString()).takeIf { it > 0f },
                                        modifier = if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier,
                                        onFocus = { focused = v; focusedRow = r },
                                        onClick = { play(ctx, row.videos, i) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun play(ctx: Context, list: List<Video>, index: Int) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(list.map { it.uri.toString() }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(list.map { it.title }))
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
    )
}

@Composable
private fun Header(onRescan: () -> Unit, onChange: () -> Unit, showActions: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(end = SidePad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Media Player", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.weight(1f))
        if (showActions) {
            GhostButton("Rescan", onRescan)
            Spacer(Modifier.width(10.dp))
            GhostButton("Change source", onChange)
        }
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = Glass,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.background(Glass, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HeroInfo(video: Video?, loading: Boolean) {
    Column(
        Modifier.height(210.dp).fillMaxWidth(0.62f),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (video != null) {
            Text(
                video.folder.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                video.title,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val size = formatSize(video.size)
                if (size.isNotEmpty()) Text(size, fontSize = 14.sp, color = Muted)
                video.badges.forEach { Pill(it) }
            }
        } else {
            Text(if (loading) "Scanning…" else "No videos found", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, onScan: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(end = SidePad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pick your video folder", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text("Plug in your SSD, then choose the folder that holds your videos.", fontSize = 15.sp, color = Muted)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.focusRequester(focus),
            colors = ButtonDefaults.colors(
                containerColor = Color.White,
                contentColor = Color.Black,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        ) { Text("Choose folder", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(14.dp))
        GhostButton("Scan all videos on this device", onScan)
    }
}

@Composable
private fun Thumb(video: Video, loader: ImageLoader, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(video.uri).videoFramePercent(0.1).build(),
        imageLoader = loader,
        contentDescription = null,
        contentScale = scale,
        modifier = modifier,
    )
}

@Composable
private fun VideoCard(
    video: Video,
    loader: ImageLoader,
    progress: Float?,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(250.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .onFocusChanged {
                    isFocused = it.hasFocus
                    if (it.hasFocus) onFocus()
                },
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.1f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
            glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.25f), elevation = 16.dp)),
            colors = CardDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
        ) {
            Box(Modifier.fillMaxSize()) {
                Thumb(video, loader, Modifier.fillMaxSize())
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
                    }
                }
            }
        }
        Text(
            video.title,
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) Color.White else Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp),
        )
    }
}
