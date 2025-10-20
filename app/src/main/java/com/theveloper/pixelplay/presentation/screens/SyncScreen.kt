package com.theveloper.pixelplay.presentation.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.model.DirectoryItem
import com.theveloper.pixelplay.data.worker.SyncWorker
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel
import com.theveloper.pixelplay.utils.GraphAuth
import com.theveloper.pixelplay.utils.GraphRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlin.collections.isNotEmpty
import kotlin.math.roundToInt
import kotlin.text.isNullOrBlank
import com.theveloper.pixelplay.data.worker.SyncWorker.Companion;
import kotlinx.coroutines.flow.first

object SongStorage {
    private var songs: List<SongEntity> = emptyList()

    fun setSongs(list: List<SongEntity>) {
        songs = list
    }

    fun getSongs(): List<SongEntity> {
        return songs
    }

    fun clearSongs() {
        songs = emptyList()
    }
}

// ---------- Collapsing Top Bar ----------
@Composable
private fun SyncTopBar(
    collapseFraction: Float,
    headerHeight: Dp,
    onBackPressed: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val titleScale = lerp(1.2f, 0.8f, collapseFraction)
    val titlePaddingStart = lerp(32.dp, 58.dp, collapseFraction)
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment = BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(surfaceColor.copy(alpha = collapseFraction))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            FilledIconButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 4.dp),
                onClick = onBackPressed,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Icon(painterResource(R.drawable.rounded_arrow_back_24), contentDescription = "Back")
            }

            Box(
                modifier = Modifier
                    .align(animatedTitleAlignment)
                    .height(titleContainerHeight)
                    .fillMaxWidth()
                    .padding(start = titlePaddingStart, end = 24.dp)
            ) {
                Text(
                    text = "Sync",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            scaleX = titleScale
                            scaleY = titleScale
                        }
                )
            }
        }
    }
}

// ---------- Sync Screen ----------
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SyncScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    onNavigationIconSyncClick: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val musicDao: MusicDao

    LaunchedEffect(Unit) {
        try {
            GraphAuth.init(context)
            //val token = GraphAuth.acquireToken(context as Activity)
            //Log.d("TOKEN", "Token: $token")
            // TODO: Save token in ViewModel or DataStore for reuse
        } catch (e: Exception) {
            Log.e("MSAL", "Auth failed", e)
        }
    }

    val uiState by settingsViewModel.uiState.collectAsState()
    val playerSheetState by playerViewModel.sheetState.collectAsState()

    BackHandler(enabled = playerSheetState == PlayerSheetState.EXPANDED) {
        playerViewModel.collapsePlayerSheet()
    }

    val directoryItems: ImmutableList<DirectoryItem> = remember(uiState.directoryItems) {
        uiState.directoryItems.toImmutableList()
    }

    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(true) {
        transitionState.targetState = true
    }

    val transition = rememberTransition(transitionState, label = "SettingsAppearTransition")

    val contentAlpha by transition.animateFloat(
        label = "ContentAlpha",
        transitionSpec = { tween(durationMillis = 500) }
    ) { if (it) 1f else 0f }

    val contentOffset by transition.animateDp(
        label = "ContentOffset",
        transitionSpec = { tween(durationMillis = 400, easing = FastOutSlowInEasing) }
    ) { if (it) 0.dp else 40.dp }

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    var showDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://ethnear-my.sharepoint.com/:f:/g/personal/dhruv_ethnear_onmicrosoft_com/EoqAezeSz2VOo-mqz-IPcpgBueKg7ybYPLpBR52U8n8dPg?e=iBhUwG") }
    var isSyncing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val WORK_NAME = "com.theveloper.pixelplay.presentation.screens.SyncScreen"
    val TAG = "SyncScreen"

    LaunchedEffect(topBarHeight.value) {
        collapseFraction =
            1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx))
                .coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                if (!isScrollingDown &&
                    (lazyListState.firstVisibleItemIndex > 0 ||
                            lazyListState.firstVisibleItemScrollOffset > 0)
                ) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight =
                    (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch {
                        topBarHeight.snapTo(newHeight)
                    }
                }

                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand =
                topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand =
                lazyListState.firstVisibleItemIndex == 0 &&
                        lazyListState.firstVisibleItemScrollOffset == 0

            val targetValue =
                if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx

            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(
                        targetValue,
                        spring(stiffness = Spring.StiffnessMedium)
                    )
                }
            }
        }
    }

    Scaffold(
        //snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(bottom = 80.dp)
            )
        }
    ) {
        Box(
            modifier = Modifier
                .nestedScroll(nestedScrollConnection)
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset.toPx()
                }
        ) {
            val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(top = currentTopBarHeightDp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    SettingsSection(
                        title = "OneDrive Sync",
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        Column(modifier = Modifier.clip(shape = RoundedCornerShape(24.dp))) {
                            SettingsItem(
                                title = "Sync from Graph API",
                                subtitle = "Fetch music files from a OneDrive share link.",
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Sync,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                },
                                trailingIcon = {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else null
                                },
                                onClick = { showDialog = true },
                                //enabled = !isSyncing
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            SettingsItem(
                                title = "Clear Cache",
                                subtitle = "Remove cached music files from local storage.",
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                },
                                onClick = {
                                    // viewModel.clearCache()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Cache cleared")
                                    }
                                },
                                //enabled = !isSyncing
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
                item { Spacer(modifier = Modifier.height(MiniPlayerHeight + 36.dp)) }
            }
            SyncTopBar(
                collapseFraction = collapseFraction,
                headerHeight = currentTopBarHeightDp,
                onBackPressed = onNavigationIconSyncClick
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Enter Shared OneDrive Link") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("OneDrive Share URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch {
                        isSyncing = true
                        try {
                            if (context is Activity) {
                                Log.d(TAG, "Starting OneDrive sync...")
                                val songs = GraphRepository.fetchStructuredSongs(context, url)
                                Log.d(TAG, "Fetched ${songs.size} songs from OneDrive")

                                if (songs.isNotEmpty()) {

                                    SongStorage.setSongs(songs)

                                    // Debug: Log some sample songs
//                                    songs.take(3).forEach { song ->
//                                        Log.d(TAG, "Sample song: ${song.title}, Path: ${song.parentDirectoryPath}")
//                                    }
//
//                                    val existingLyricsMap = settingsViewModel.musicDao
//                                        .getAllSongsList()
//                                        .associate { it.id to it.lyrics }
//                                    Log.d(TAG, "Found ${existingLyricsMap.size} existing songs with lyrics")
//
//                                    val (correctedSongs, albums, artists) = preProcessAndDeduplicate(songs)
//                                    Log.d(TAG, "After processing: ${correctedSongs.size} songs, ${albums.size} albums, ${artists.size} artists")
//
//                                    val songsWithPreservedLyrics = correctedSongs.map { songEntity ->
//                                        val existingLyrics = existingLyricsMap[songEntity.id]
//                                        if (!existingLyrics.isNullOrBlank()) {
//                                            songEntity.copy(lyrics = existingLyrics)
//                                        } else songEntity
//                                    }
//
//                                    // CRITICAL: Add OneDrive folders to allowed directories
                                    val oneDriveFolders = songs.map { it.parentDirectoryPath }.distinct()
                                    Log.d(TAG, "OneDrive folders found: $oneDriveFolders")

                                    val currentAllowed = settingsViewModel.userPreferencesRepository.allowedDirectoriesFlow.first().toMutableSet()
                                    Log.d(TAG, "Current allowed directories: $currentAllowed")

                                    currentAllowed.addAll(oneDriveFolders)
                                    Log.d(TAG, "Updated allowed directories: $currentAllowed")

                                    settingsViewModel.userPreferencesRepository.updateAllowedDirectories(currentAllowed)
//
//                                    // Clear and insert data
//                                    Log.d(TAG, "Clearing existing music data...")
//                                    settingsViewModel.musicDao.clearAllMusicData()
//
//                                    Log.d(TAG, "Inserting new music data...")
//                                    settingsViewModel.musicDao.insertMusicData(
//                                        songsWithPreservedLyrics,
//                                        albums,
//                                        artists
//                                    )
//
//                                    // Debug: Verify data was inserted
//                                    val insertedSongs = settingsViewModel.musicDao.getAllSongsList()
//                                    Log.d(TAG, "Verification: ${insertedSongs.size} songs in database after insert")
//
//                                    // Debug: Check what the repository returns
//                                    val currentAllowedAfterInsert = settingsViewModel.userPreferencesRepository.allowedDirectoriesFlow.first()
//                                    val setupDone = settingsViewModel.userPreferencesRepository.initialSetupDoneFlow.first()
//                                    Log.d(TAG, "Post-insert - Allowed dirs: $currentAllowedAfterInsert, Setup done: $setupDone")
//
//                                    Log.i(TAG, "Music data insertion completed successfully.")
                                    //settingsViewModel.refreshLibrary()
                                    snackbarHostState.showSnackbar("Synced ${songs.size} songs")
                                } else {
                                    Log.w(TAG, "No songs found from Graph API")
                                    snackbarHostState.showSnackbar("No songs found")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Sync failed", e)
                            snackbarHostState.showSnackbar("Error: ${e.localizedMessage}")
                        } finally {
                            isSyncing = false
                        }
                    }
                }) {
                    Text("Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun preProcessAndDeduplicate(songs: List<SongEntity>): Triple<List<SongEntity>, List<AlbumEntity>, List<ArtistEntity>> {
    // Artist de-duplication
    val artistMap = mutableMapOf<String, Long>()
    songs.forEach { song ->
        if (!artistMap.containsKey(song.artistName)) {
            artistMap[song.artistName] = song.artistId
        }
    }

    // Album de-duplication
    val albumMap = mutableMapOf<Pair<String, String>, Long>()
    songs.forEach { song ->
        val key = Pair(song.albumName, song.artistName)
        if (!albumMap.containsKey(key)) {
            albumMap[key] = song.albumId
        }
    }

    val correctedSongs = songs.map { song ->
        val canonicalArtistId = artistMap[song.artistName]!!
        val canonicalAlbumId = albumMap[Pair(song.albumName, song.artistName)]!!
        song.copy(artistId = canonicalArtistId, albumId = canonicalAlbumId)
    }

    // Create unique albums
    val albums = correctedSongs.groupBy { it.albumId }.map { (albumId, songsInAlbum) ->
        val firstSong = songsInAlbum.first()
        AlbumEntity(
            id = albumId,
            title = firstSong.albumName,
            artistName = firstSong.artistName,
            artistId = firstSong.artistId,
            albumArtUriString = firstSong.albumArtUriString,
            songCount = songsInAlbum.size,
            year = firstSong.year
        )
    }

    // Create unique artists
    val artists = correctedSongs.groupBy { it.artistId }.map { (artistId, songsByArtist) ->
        val firstSong = songsByArtist.first()
        ArtistEntity(
            id = artistId,
            name = firstSong.artistName,
            trackCount = songsByArtist.size
        )
    }

    return Triple(correctedSongs, albums, artists)
}

