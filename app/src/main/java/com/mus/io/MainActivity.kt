@file:Suppress("DEPRECATION") // LocalClipboardManager preserves the existing synchronous copy action.

package com.nejuni.juni

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nejuni.juni.data.LocalMediaRepository
import com.nejuni.juni.data.StartupTrace
import com.nejuni.juni.data.NerdStats
import com.nejuni.juni.data.TrackLog
import com.nejuni.juni.data.model.BrowseType
import com.nejuni.juni.data.model.LikeStatus
import com.nejuni.juni.data.model.PlaybackSourceType
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.UiState
import com.nejuni.juni.data.model.UserPlaylist
import com.nejuni.juni.data.model.durationMillis
import com.nejuni.juni.data.scrobbling.LastFM
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.data.settings.BitPerfectMode
import com.nejuni.juni.data.settings.LibrarySort
import com.nejuni.juni.data.settings.ThemeMode
import com.nejuni.juni.ui.components.DacVolumeHud
import com.nejuni.juni.ui.components.DacVolumeKeys
import com.nejuni.juni.ui.components.LocalPlayingBarsMotion
import com.nejuni.juni.ui.components.handleDacVolumeKey
import com.nejuni.juni.ui.components.VolumeHud
import com.nejuni.juni.ui.navigation.AppDestination
import com.nejuni.juni.ui.navigation.AppRoute
import com.nejuni.juni.ui.screens.EqualizerPresetPage
import com.nejuni.juni.ui.screens.SettingsChoicePage
import com.nejuni.juni.ui.screens.LastFmSettingsOnly
import com.nejuni.juni.ui.screens.EqualizerScreen
import com.nejuni.juni.ui.screens.HistoryScreen
import com.nejuni.juni.ui.screens.SettingsScreen
import com.nejuni.juni.playback.LinkRequest
import com.nejuni.juni.playback.MusicLink
import com.nejuni.juni.playback.PlayerDeepLink
import com.nejuni.juni.playback.QueueCoordinator
import com.nejuni.juni.playback.QueueCoordinator.asQueueEntry
import com.nejuni.juni.playback.QueueShuffle
import com.nejuni.juni.playback.QueueSource
import com.nejuni.juni.data.model.QueueTier
import com.nejuni.juni.playback.playSongs
import com.nejuni.juni.playback.BoundedQueuePlanner
import com.nejuni.juni.playback.playBoundedContext
import com.nejuni.juni.playback.contextEntryKey
import com.nejuni.juni.playback.toMediaItem
import com.nejuni.juni.playback.toSong
import com.nejuni.juni.playback.toggleShuffle
import com.nejuni.juni.playback.clearQueue
import com.nejuni.juni.ui.components.BrowseActionsMenu
import com.nejuni.juni.ui.components.BrowseTarget
import com.nejuni.juni.ui.components.ConfirmationAlert
import com.nejuni.juni.ui.components.PlaylistPickerSheet
import com.nejuni.juni.ui.components.LongPressOrigin
import com.nejuni.juni.ui.components.MenuAnchor
import com.nejuni.juni.ui.components.menuAnchor
import com.nejuni.juni.ui.components.IosMenuPopover
import com.nejuni.juni.ui.components.SongActionsMenu
import com.nejuni.juni.ui.components.HeldContextMenu
import com.nejuni.juni.ui.components.HeldItem
import com.nejuni.juni.playback.rememberMediaController
import com.nejuni.juni.playback.rememberPlayerState
import com.nejuni.juni.ui.MainViewModel
import com.nejuni.juni.ui.components.BottomFadeScrim
import com.nejuni.juni.ui.components.TopFadeBlur
import com.nejuni.juni.ui.components.FloatingBarsTapGuard
import com.nejuni.juni.ui.components.BottomTab
import com.nejuni.juni.ui.components.FLOATING_BAR_MAX_WIDTH
import com.nejuni.juni.ui.components.FloatingBottomBar
import com.nejuni.juni.ui.components.GlassNavBar
import com.nejuni.juni.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.nejuni.juni.ui.components.FrostedTopBar
import com.nejuni.juni.ui.components.LastfmLoginAlert
import com.nejuni.juni.ui.components.LocalAppBackdrop
import com.nejuni.juni.ui.components.LocalLiquidGlassEnabled
import com.nejuni.juni.ui.components.backdrop.backdrops.LayerBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.layerBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.nejuni.juni.ui.components.isGlassSupported

import com.nejuni.juni.ui.components.MiniPlayer
import com.nejuni.juni.ui.components.QueueActionNotice
import com.nejuni.juni.ui.components.QueueActionNoticeHost
import com.nejuni.juni.ui.components.SearchField
import com.nejuni.juni.sharedui.resources.Res as SharedRes
import com.nejuni.juni.sharedui.resources.search_library_hint
import com.nejuni.juni.ui.components.optimizedHazeEffect
import com.nejuni.juni.ui.components.topBarContentPadding
import com.nejuni.juni.ui.components.topBarHeight
import com.nejuni.juni.ui.components.AppLanguageDialog
import com.nejuni.juni.ui.components.TranslationLanguageDialog
import com.nejuni.juni.ui.components.LyricsSourcesDialog
import com.nejuni.juni.ui.icons.JuniIcons
import com.nejuni.juni.ui.icons.SfIcons
import androidx.media3.common.Player
import com.nejuni.juni.ui.player.NowPlayingScreen
import com.nejuni.juni.ui.player.MixViewRequest
import com.nejuni.juni.ui.player.PlayerSheetMotion
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import com.nejuni.juni.ui.components.MiniPlayerPull
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import com.nejuni.juni.ui.player.LocalPlayerDock
import com.nejuni.juni.ui.player.PlayerDock
import com.nejuni.juni.ui.screens.DetailScreen
import com.nejuni.juni.ui.screens.sortedForDetail
import com.nejuni.juni.ui.screens.LocalFolder
import com.nejuni.juni.ui.screens.LocalFolderState
import com.nejuni.juni.ui.screens.LocalFolderTab
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import com.nejuni.juni.ui.screens.localizedLabel
import com.nejuni.juni.data.LocalGroups
import com.nejuni.juni.data.OfflinePlaylists
import com.nejuni.juni.data.toGroup
import com.nejuni.juni.data.settings.LibraryViewType
import com.nejuni.juni.data.settings.LocalMusicSort
import com.nejuni.juni.ui.screens.SearchScreen
import com.nejuni.juni.data.settings.SongSort
import com.nejuni.juni.ui.festive.DiwaliGate
import com.nejuni.juni.ui.festive.DiwaliGreetingScreen
import com.nejuni.juni.ui.replay.ReplayScreen
import com.nejuni.juni.ui.replay.cards
import com.nejuni.juni.ui.replay.ReplayShareSheet
import com.nejuni.juni.ui.replay.ReplayStories
import com.nejuni.juni.ui.replay.ReplayStoryPage
import com.nejuni.juni.ui.replay.rememberReplayState
import com.nejuni.juni.ui.theme.juniTheme
import com.nejuni.juni.ui.theme.rememberArtworkPalette
import com.nejuni.juni.ui.theme.SystemBarIcons
import com.nejuni.juni.ui.utils.guardSheetFromContentTouches
import com.nejuni.juni.ui.utils.rememberIosOverscrollFactory
import com.nejuni.juni.ui.performance.resolvePerformanceRefreshRate
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal fun shouldSkipAfterDislike(
    previousStatus: LikeStatus,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != LikeStatus.DISLIKE && targetVideoId == currentVideoId


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.log("activity", "onCreate")
        // The first drawn frame, for the launch trace. Registered before the
        // composition so it fires on the frame that first shows the page.
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    val used = Runtime.getRuntime().let {
                        (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
                    }
                    StartupTrace.log("activity", "first frame — ${used}MB used")
                    return true
                }
            },
        )
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time juniApp first reads it.
        PlayerDeepLink.consume(intent)
        // Likewise for a link tapped or shared from another app — see [MusicLink].
        MusicLink.consume(intent)
        val isDirectAppLaunch = intent.action == Intent.ACTION_MAIN && intent.data == null
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            juniTheme(darkTheme = darkTheme, font = appFont) {
                // The glass surfaces sample this layer, and a layer records only
                // what is drawn into it — which, for bit, is a page that
                // paints no background of its own. Everywhere a page is not
                // showing artwork the recording is transparent, so the glass had
                // nothing to blur there and you saw straight through it to the
                // sharp page underneath: album art came through the bar blurred
                // and text came through it untouched. The window's background is
                // the floor the pages have always been drawn against, so it is
                // laid down here too and the recording is opaque like the screen.
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    juniApp(
                        darkTheme = darkTheme,
                        windowWidth = maxWidth,
                        windowHeight = maxHeight,
                        appBackdrop = appBackdrop,
                        isDirectAppLaunch = isDirectAppLaunch,
                    )
                }
                }
            }
        }
    }

    /**
     * The volume keys move the DAC's own volume while direct USB output owns
     * it: Android's music stream no longer reaches that DAC, so the system
     * volume would change nothing anyone hears. Handled here rather than left
     * to the media session so the app's own HUD answers instead of Android's
     * remote-volume panel; in the background the session takes them. A sheet
     * in a window of its own asks the same through [DacVolumeKeys].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleDacVolumeKey(event) || super.dispatchKeyEvent(event)

    /**
     * Requests a window refresh rate without forcing a display mode or
     * resolution. Android may still lower it for temperature, battery state or
     * hardware limits, which is why Settings describes this as a preference.
     */
    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        MusicLink.consume(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun juniApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    /**
     * The window's height, measured the same way and for the same reason as
     * [windowWidth] — and needed alongside it for exactly one thing: telling
     * a portrait window apart from a landscape one. Width alone can't; a
     * big tablet's portrait width comfortably clears a phone's landscape
     * width, so the two-column player (see [landscapePlayerAvailable])
     * would fire in portrait too if it only ever asked about width.
     */
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    isDirectAppLaunch: Boolean,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    // Recording the backdrop layer costs a draw pass, so it only runs when a
    // liquid-glass surface (the nav bar or artwork-page back button) can sample it.
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    // "Reduce dynamic blur" keeps the glass bar's *shape* — the folding
    // now-playing-and-tabs component is a layout, not an effect, and dropping
    // back to the two stacked bars would be answering a question about material
    // with a different screen. What it drops is the sampling: the surfaces fill
    // solid (see [Modifier.liquidGlass]) and the whole-page layer recording
    // below goes with them, which is the part that costs a draw pass.
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    // What folds [GlassNavBar] between its expanded and inline shapes. Held here
    // rather than inside the bar because the page's scroll is what drives it,
    // and the page is a sibling of the bar rather than a child.
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    /**
     * Whether the player's sheet is up. The player is always a full-screen
     * take-over raised over the page, on every window size — the library
     * stays full-screen behind a mini player rather than losing a lane to a
     * permanent pane.
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    // The DAC's own volume while direct USB output owns it; the full player
    // has its own bar for that, so the HUD stays out of its way.
    androidx.compose.runtime.SideEffect { VolumeHud.suppressed = showNowPlaying }
    // The Top Picks and mood artwork loop forever; nobody sees it with the
    // app in the background or Now Playing pulled up over the page.
    val appForeground = com.nejuni.juni.ui.rememberIsForeground()
    val artworkMotion = appForeground && !showNowPlaying
    // Where the two ends of opening and closing the player meet: the mini
    // player's cover reports itself here, the sheet its offset, and the player
    // flies its artwork between them — see [PlayerDock].
    val playerDock = remember { PlayerDock() }
    // Who moves the player sheet when the app does rather than M3: a tap's
    // open, the pull up from the mini player, and every close — see
    // [PlayerSheetMotion].
    val playerSheetScope = rememberCoroutineScope()
    val playerSheetMotion = remember {
        PlayerSheetMotion(
            scope = playerSheetScope,
            dock = playerDock,
            shown = { showNowPlaying },
            setShown = { showNowPlaying = it },
        )
    }
    playerSheetMotion.windowInfo = LocalWindowInfo.current
    // Whether a portrait player fully up has the page covered. Derived, so the
    // page's draw below hears when that flips rather than on every frame of the
    // sheet's travel: read straight off the fraction, each of those frames
    // redrew the page's whole window behind the player, glass and blurs and all.
    val coveredByPlayer by remember {
        derivedStateOf { showNowPlaying && playerDock.attached && playerDock.openFraction() >= 1f }
    }
    // "Reduce animation" keeps the player as it always was: M3's own slide up
    // and down, its own scrim and window animations, no pull from the mini
    // player and no artwork flying between the two. Everything below that
    // takes part in the docking asks for [activeDock] / this, never for the
    // dock or the motion directly.
    val reducePlayerMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val activeDock = playerDock.takeIf { !reducePlayerMotion }
    playerSheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    // How the app takes the player down, whatever for: a back press, or a page
    // opened from inside it. Set straight to false, the sheet left in one frame
    // on its window's own exit animation — the old fast drop, with a copy of the
    // artwork slipping down out of the mini player's cover after it.
    val dismissPlayer: () -> Unit = {
        if (reducePlayerMotion) showNowPlaying = false else playerSheetMotion.close()
    }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    // Replay: the page (a route on the back stack — see [AppNavigator]), the
    // stories over it, and the share sheet over those. Closing one has to
    // reveal what it was opened from.
    // Library cards use the same Replay page as every other entry point, but
    // category cards ask it to start at their matching ranked section.
    var replayLandingPage by rememberSaveable { mutableStateOf(ReplayStoryPage.INTRO) }
    var replayStory by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by rememberSaveable { mutableStateOf(false) }
    /** Which story card the share sheet is for, or null for the whole Replay. */
    var replaySharePage by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }

    var confirmBitPerfect by remember { mutableStateOf<BitPerfectMode?>(null) }
    var confirmFixedVolume by remember { mutableStateOf(false) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var showLyricsOffset by remember { mutableStateOf(false) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    /** Whether the track menu was opened from the History screen. */
    var menuFromHistory by remember { mutableStateOf(false) }
    /**
     * The row or card that was held to open the track menu, or null when it
     * was opened any other way. Non-null lifts that item into
     * [HeldContextMenu]; null hangs the menu off [songMenuAnchor] instead —
     * which is what the ⋮ on the very same row opens.
     */
    var songMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    /** The ⋮ or ⋯ the track menu was opened from, for [IosMenuPopover] to hang off. */
    var songMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    /**
     * A still of the player for its ⋯ menu to blur — see the player's sheet
     * below. Up here so the tap that opens the menu can ask for it: asked any
     * later, the picture is read back on a frame of the menu's spring.
     */
    val playerBackdrop = rememberSnapshotBackdrop()
    /**
     * The player live instead, while it is a full-screen Spotify Canvas: that
     * keeps playing under the ⋯ menu, and a still of it would freeze under the
     * panel. Recorded only while that is the case — see the player's sheet.
     */
    val livePlayerBackdrop = rememberLayerBackdrop()
    var playerLiveCanvas by remember { mutableStateOf(false) }
    /**
     * The same for a ⋮ or ⋯ menu over a page: a still of everything under it —
     * the page, its bars, the mini player — blurred under the panel. The
     * page's live haze, under a panel growing on a spring, was re-cut and
     * rescaled with it on every frame: the blur swam against the panel's
     * growth, and the whole page was rendered again for it each time.
     */
    val pageBackdrop = rememberSnapshotBackdrop()
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songMenuOrigin = LongPressOrigin.consume()
        songMenuAnchor = MenuAnchor.consume()
        songActions = song
    }
    // Which track the playlist picker is adding, or null when it's closed.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    // The same two, over the device's own playlists — for a track that is a
    // file on it, and for "New playlist" on Local Music's Playlists tab.
    var offlinePlaylistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingOfflinePlaylist by remember { mutableStateOf(false) }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    /** The card held to open [browseActions] — see [songMenuOrigin]. */
    var browseMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    /** The ⋯ [browseActions] was opened from — see [songMenuAnchor]. */
    var browseMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    /** Rename asked for from the menu, which hands it on to a sheet holding only the form. */
    var browseRenameInSheet by remember { mutableStateOf(false) }
    /** The playlist being rearranged, or null when the reorder page is shut. */
    /** An album or playlist: lifted when it was a hold, else hung off the ⋯ that was tapped. */
    val openBrowseMenu: (BrowseTarget) -> Unit = { target ->
        browseMenuOrigin = LongPressOrigin.consume()
        browseMenuAnchor = MenuAnchor.consume()
        browseRenameInSheet = false
        browseActions = target
    }
    // Set each time the search tab is tapped, which SearchScreen uses as a
    // signal to focus the input field.
    var searchFocusRequested by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }
    var playRequestGeneration by remember { mutableIntStateOf(0) }
    // ---- Diwali (greeting once after the welcome, golden home wordmark) ----
    // Indian users only, by the device timezone alone, and only on Diwali
    // itself (see DiwaliGate). Asked again each time the app comes to the
    // front, so one left open overnight into the day still greets — and one
    // left open past it lets the gold go.
    val diwaliFestive = remember(appForeground) {
        DiwaliGate.isIndianTimezone() && DiwaliGate.isDiwaliDay()
    }
    var showDiwali by rememberSaveable { mutableStateOf(false) }
    var diwaliAutoShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(diwaliFestive) {
        if (diwaliFestive && !diwaliAutoShown) {
            diwaliAutoShown = true
            if (!AppSettings.diwaliGreetingShown.value) showDiwali = true
        }
    }

    // ---- Navigation ----
    // One back stack for every page: the routes pushed over the tabs, and the
    // album / artist / playlist pages interleaved with them — see
    // [AppNavigator]. Whatever was opened last is what is on screen, and back
    // (the gesture or the bar's button alike) undoes exactly that.
    val navigator = viewModel.navigator
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val topEntry = navigator.visibleEntry(detailStack.size)
    // An artist's open shelf is drawn by the artist page itself, so it is not a
    // route the page area switches to — only the shelf the page is showing.
    val visibleEntry = topEntry
    /** The route on screen, or null when a release page or a tab is. */
    val route = visibleEntry?.route
    /**
     * The release page at the top of its stack, whether or not a route
     * covers it. Most things want [detail] — the one actually on screen.
     */
    val topDetail = detailStack.lastOrNull()
    /** The release page on screen, or null when a route or a tab is. */
    val detail = topDetail?.takeIf { visibleEntry == null }

    SystemBarIcons(
        dark = !darkTheme && !showNowPlaying && route != AppRoute.Replay && replayStory == null && !showDiwali,
    )

    val query by viewModel.query.collectAsStateWithLifecycle()
    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()
    val historyState by viewModel.history.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()
    // Local Music and the other device folders draw as release pages, but they
    // have no artwork to wash the top inset in, so they keep the plain bar;
    // their top bar trades the overflow for a sort-and-view menu, and keeps the
    // account photo.
    //
    // An album or artist opened out of a folder is none of that: it has a cover
    // and a track list, so it takes the bar every other release page takes.
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    // An album or playlist page: the one whose top bar carries its search,
    // sort and overflow controls.
    val isReleaseDetail = detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST
    // Not keyed on the browse id and not remembered here: each page's choice
    // lives in AppSettings keyed by that page — Spotify-style, one playlist's
    // order never imposes itself on another, and every page keeps its own
    // across visits.
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = topDetail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    // A device folder's equivalents — its sort and whether it is a grid —
    // kept apart for Downloads and shared by every other folder, as they
    // always were. Up here because both the page and the top bar's menu read
    // them.
    val localMusicSort by AppSettings.localMusicSort.collectAsStateWithLifecycle()
    val localMusicViewType by AppSettings.localMusicViewType.collectAsStateWithLifecycle()
    val folderSortOf: (String) -> LocalMusicSort = { localMusicSort }
    val folderViewTypeOf: (String) -> LibraryViewType = { localMusicViewType }
    var folderMenuOpen by remember { mutableStateOf(false) }
    // Whether the open release page's filter box is up. Held out here with the
    // sort control because the button that opens it sits in the top bar beside
    // it, and reset with the page so a filter never follows you to the next one.
    var detailSearching by rememberSaveable(topDetail?.browseId) { mutableStateOf(false) }
    var localSearchOpen by remember { mutableStateOf(false) }
    // A device file's heart is the device's Liked playlist. One rule for
    // every surface that draws a heart — the player and the track menu alike.
    val offlineLiked by OfflinePlaylists.liked.collectAsStateWithLifecycle()
    val offlinePlaylists by OfflinePlaylists.playlists.collectAsStateWithLifecycle()
    val offlinePlaylistGroups = remember(offlinePlaylists) { offlinePlaylists.map { it.toGroup() } }
    val likeStatusOf: (Song) -> LikeStatus = { song ->
        if (song.localUri?.let { it in offlineLiked } == true) LikeStatus.LIKE else LikeStatus.INDIFFERENT
    }
    val toggleLikeOf: (Song) -> Unit = { song -> OfflinePlaylists.toggleLike(song) }


    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    // Playlists imported without (or instead of) a YouTube Music account live
    // in the app's own store.
    val localPlaylists by com.nejuni.juni.data.localplaylist.LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    // An offline playlist's page is the store's, and follows it: a track
    // removed from its menu, or a like from the player, leaves no stale row.
    // Apart from the effect above so a like never re-lists the Downloads folder.
    LaunchedEffect(topDetail?.browseId, offlinePlaylists) {
        val openPage = topDetail ?: return@LaunchedEffect
        if (openPage.songs !is UiState.Loading && OfflinePlaylists.isOfflinePlaylist(openPage.browseId)) {
            viewModel.reloadLocalDetail(openPage.browseId)
        }
    }
    // Nothing re-reads the device library on its own after the first run. The
    // folder and filter settings decide what a scan reads, so they take effect
    // at the next "Scan library" rather than starting one here.
    val libraryScanning by viewModel.libraryScanning.collectAsStateWithLifecycle()
    val libraryScannedAt by viewModel.libraryScannedAt.collectAsStateWithLifecycle()
    val lastScanLabel = remember(libraryScannedAt) {
        libraryScannedAt.takeIf { it > 0L }?.let { at ->
            android.text.format.DateUtils.getRelativeTimeSpanString(
                at,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        }
    }
    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    val playPauseBusy = player.isLoading
    var queueNotice by remember { mutableStateOf<QueueActionNotice?>(null) }
    var queueNoticeId by remember { mutableIntStateOf(0) }
    val showQueueNotice: (String) -> Unit = { message ->
        queueNoticeId += 1
        queueNotice = QueueActionNotice(queueNoticeId, message)
    }
    LaunchedEffect(queueNotice?.id) {
        val shown = queueNotice ?: return@LaunchedEffect
        delay(3_000)
        if (queueNotice?.id == shown.id) queueNotice = null
    }
    /** The one play/pause every surface presses. */
    val togglePlayPause: () -> Unit = {
        controller?.let { c ->
            if (c.isPlaying) {
                c.pause()
            } else {
                c.play()
            }
        }
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()

    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off, reordering
    // them or changing the syllable preference applies to the track already
    // playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    LaunchedEffect(
        player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources,
        lyricsSourceOrder, prioritizeSyllableSync,
    ) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                // The player's own length, and the catalogue's where it has
                // none yet. Paused, ExoPlayer never finishes preparing the
                // track it was skipped to, so it reports no duration at all —
                // and a lookup that waits for one waits for ever, which left
                // the lyrics of a paused track loading until it was played.
                player.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
            )
        }
    }

    val historyListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val currentListState = searchListState
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    // A pushed album/artist/playlist page has a large header of its own — the
    // sleeve, or an artist's photo running edge to edge — which owns the title
    // until it is scrolled away, exactly as a tab's big heading does. The state
    // is hoisted because the bar lives beside that page rather than inside it,
    // and is rebuilt per page: pushing a second one must not inherit the
    // first's scroll offset.
    // As [detailListState], for Replay: its own large heading owns the title
    // until it is scrolled away, and the bar lives out here rather than on the
    // page. Rebuilt per opening so reopening starts at the top, but kept while
    // that opening is on the stack, so back from a page opened off it returns
    // to where it was.
    val replayEntryId = navigator.entries.lastOrNull { it.route == AppRoute.Replay }?.id
    val replayListState = remember(replayEntryId, replayLandingPage) { LazyListState() }
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    // AnimatedContent keeps the outgoing page composed during its fade. A
    // single state remembered from only the *current* detail id is therefore
    // handed to both the outgoing and incoming LazyColumns for that interval.
    // Compose lazy state is one-layout state: sharing it between those lists
    // can leave the incoming album attached to the disappearing artist/grid
    // layout and unable to consume scroll gestures. Keep one state per page
    // while it is on the navigation stack instead.
    val detailListStates = remember { mutableMapOf<String, LazyListState>() }
    val detailListState = topDetail?.browseId?.let { browseId ->
        detailListStates.getOrPut(browseId) { LazyListState() }
    } ?: remember { LazyListState() }
    // A device folder's tab and selection, kept the same way and for the same
    // reason: an album opened from the folder replaces its page until Back.
    val localFolderStates = remember { mutableMapOf<String, LocalFolderState>() }
    LaunchedEffect(detailStack.map { it.browseId }) {
        val open = detailStack.mapTo(HashSet()) { it.browseId }
        detailListStates.keys.retainAll(open)
        localFolderStates.keys.retainAll(open)
    }
    // Each pushed page's saved state (see the page switcher below), held while
    // the page is on the stack and dropped as it leaves it, so the next visit
    // to the same route starts fresh.
    val pageStates = rememberSaveableStateHolder()
    val pageKeysSeen = remember { mutableSetOf<String>() }
    val livePageKeys = navigator.entries.map { AppDestination.Route(it).key } +
        detailStack.map { AppDestination.Release(it.browseId).key }
    LaunchedEffect(livePageKeys) {
        val gone = pageKeysSeen.filter { it !in livePageKeys }
        gone.forEach(pageStates::removeState)
        pageKeysSeen.removeAll(gone.toSet())
    }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Held, not rebuilt. `listOf` hands back a new instance on every pass, and a
    // List is not a type the compiler can call stable, so under strong skipping
    // the bar this is handed to compares it by identity, never matches, and so
    // can never skip. This composable re-runs on every frame of a scroll — it
    // reads [scrolled] — which made the whole floating bar, both of its states
    // and every glass surface on them recompose once per frame for the length of
    // a fold. Keyed on the labels so a locale change still rebuilds it.
    val libraryLabel = stringResource(R.string.library)
    val searchLabel = stringResource(R.string.search)
    val historyLabel = stringResource(R.string.history)
    val queueLabel = stringResource(R.string.queue)
    val openedFileLabel = stringResource(R.string.opened_file)
    val tabs = remember(libraryLabel) {
        listOf(BottomTab(libraryLabel, JuniIcons.TabLibrary))
    }
    val localMusicRootOpen = detailStack.any { it.browseId == "local:all" }
    val localMusicState = if (localMusicRootOpen) {
        localFolderStates.getOrPut("local:all") { LocalFolderState() }
    } else {
        null
    }
    val artistsLabel = stringResource(R.string.artists)
    val albumsLabel = stringResource(R.string.albums)
    val songsLabel = stringResource(R.string.songs)
    val foldersLabel = stringResource(R.string.folders)
    val localMusicTabs = remember(artistsLabel, albumsLabel, searchLabel, songsLabel, foldersLabel) {
        listOf(
            BottomTab(songsLabel, SfIcons.MusicNote),
            BottomTab(albumsLabel, SfIcons.Album),
            BottomTab(searchLabel, SfIcons.Search),
            BottomTab(artistsLabel, SfIcons.Artist),
            BottomTab(foldersLabel, SfIcons.Folder),
        )
    }
    val bottomTabs = if (localMusicState != null) localMusicTabs else tabs
    val bottomSelectedIndex = when {
        localMusicState == null -> selectedTab
        localSearchOpen -> 2
        localMusicState.tab == LocalFolderTab.SONGS -> 0
        localMusicState.tab == LocalFolderTab.ALBUMS -> 1
        localMusicState.tab == LocalFolderTab.ARTISTS -> 3
        localMusicState.tab == LocalFolderTab.FOLDERS -> 4
        else -> selectedTab
    }

    val scope = rememberCoroutineScope()


    fun playFrom(
        allSongs: List<Song>,
        allIndex: Int,
        source: QueueSource,
        boundedContext: Boolean = false,
        continuationToken: String? = null,
    ) {
        // Play the selected row and queue the rest in their existing order.
        val songs = allSongs
        val index = songs.indexOf(allSongs.getOrNull(allIndex)).coerceAtLeast(0)
        playRequestGeneration++
        scope.launch {
            val c = controller ?: return@launch
            val currentTimeline = player.queue.takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex

            // A Cast session still owns the full queue on the phone so the
            // receiver can follow its existing queue semantics. The bounded
            // service path is local-player-only; the service repeats this
            // guard for commands that race a newly connected receiver.
            //
            // Any list too long to fit the window goes this way too, whatever
            // page it came from: a whole library in the player is a whole
            // library re-sent to every media controller on each queue edit.
            val bounded = boundedContext || songs.size > BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT
            if (bounded) {
                c.playBoundedContext(songs, index, source, continuationToken)
            } else {
                val result = QueueCoordinator.buildContextQueue(
                    currentTimeline = currentTimeline,
                    currentIndex = currentIndex,
                    newContextSongs = songs,
                    selectedIndex = index,
                    contextSource = source,
                )
                c.playSongs(result.timeline, result.startIndex)
            }
            // Start playback in the mini-player; the user opens the full view by tapping it.
        }
    }
    // Kept for entry points whose rows already carry their origin (notably a
    // collection action fetched before this callback). The explicit wrappers
    // below are preferred because a track's album is not necessarily where it
    // was played from.
    val play: (List<Song>, Int) -> Unit = { songs, index ->
        val first = songs.getOrNull(index)
        val source = QueueSource(
            title = first?.playbackSource ?: first?.albumName ?: queueLabel,
            type = first?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            id = first?.playbackSourceId,
        )
        playFrom(songs, index, source)
    }


    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            // The end of what the user queued, not the end of the queue: a song
            // asked for by name outranks whatever AutoPlay lined up behind it.
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = false,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_added_to_queue))
            }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = true,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_will_play_next))
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    /**
     * Opens an artist or release page given its browse id — or, failing that,
     * its name.
     *
     * Replay's charts are the reason this exists. An artist there is counted by
     * *name*, because a name is the only thing every track carries: a browse id
     * rides along only when the row that queued the track happened to have one,
     * which for a home-feed card or an AutoPlay suggestion it does not. So half
     * the rows on a chart would have nothing to open, and a row that does
     * nothing when tapped is worse than a row that isn't tappable — it reads as
     * the app having failed rather than as the app not offering.
     *
     * Searching for the name is what the user would do next anyway. A search
     * that finds nothing says so, which is at least an answer.
     */
    fun openByName(
        browseId: String?,
        name: String,
        subtitle: String?,
        type: BrowseType,
        artwork: String? = null,
    ) {
        if (browseId != null) {
            val credit = subtitle ?: context.getString(
                if (type == BrowseType.ARTIST) R.string.artist else R.string.album,
            )
            viewModel.openDetail(browseId, name, credit, artwork, type)
            return
        }
        // Everything this build can open is on the device, so a card without a
        // catalogue id still names a page: the artist or album among the rows
        // it was drawn from.
        val opened = when (type) {
            BrowseType.ARTIST -> viewModel.openLocalArtist(name)
            BrowseType.ALBUM -> viewModel.openLocalAlbum(name, subtitle)
            else -> false
        }
        if (opened) return
        Toast.makeText(
            context,
            context.getString(R.string.couldnt_find, name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * A whole album, playlist or library list onto the queue in one go.
     *
     * [next] picks which of the two positions the single-track menu already
     * offers it lands in — straight after the current track, or behind
     * everything else the user queued but still ahead of AutoPlay (see
     * [addToQueue]). The list keeps its own running order either way: this
     * *adds* a release, it doesn't start one, so [QueueShuffle] has no say here.
     *
     * There is usually nothing on screen to show for it — the queue panel is
     * shut, the current track carries on — so the count is said out loud, the
     * same way a batch download is.
     */
    val queueSongs: (List<Song>, Boolean) -> Unit = { songs, next ->
        if (songs.isNotEmpty()) {
            scope.launch {
                    val c = controller
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing to queue behind. "Add to queue" on a silent
                    // player can only mean start here — and adding without
                    // preparing would leave the list sitting in a player that
                    // never gets round to it.
                    play(songs, 0)
                } else {
                    val toAdd = songs
                    val timeline = player.queue.takeIf { q -> q.size == c.mediaItemCount }
                        ?: (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
                    val at = QueueCoordinator.findUserQueueInsertionIndex(
                        timeline = timeline,
                        currentIndex = c.currentMediaItemIndex,
                        isNext = next,
                    )
                    val current = c.currentMediaItem?.toSong()
                    c.addMediaItems(
                        at,
                        toAdd.map {
                            it.copy(
                                radioName = current?.radioName,
                                playbackSource = current?.playbackSource ?: queueLabel,
                                playbackSourceType = current?.playbackSourceType
                                    ?: PlaybackSourceType.QUEUE,
                                playbackSourceId = current?.playbackSourceId,
                            ).asQueueEntry(QueueTier.USER_QUEUE).toMediaItem()
                        },
                    )
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        toAdd.size,
                        toAdd.size,
                    )
                    showQueueNotice(message)
                }
            }
        }
    }
    val addSongsToQueue: (List<Song>) -> Unit = { songs -> queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> queueSongs(songs, true) }

    // ---- Links from outside the app ----

    /**
     * A YouTube Music link tapped elsewhere on the device, a link shared into
     * bit, or "play something" said to the assistant — see [MusicLink].
     *
     * Keyed on the controller as well as the request, because a link is as
     * often as not what cold-starts the app: the session it has to play into is
     * still connecting the first time this runs, and returning empty-handed
     * without spending the request is what lets the second run serve it.
     */
    val linkRequest by MusicLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(linkRequest, controller) {
        val request = linkRequest ?: return@LaunchedEffect
        // Nothing here can be served without somewhere to play it — even the
        // branches that only push a page are a beat away from a tap on one of
        // its rows, and half-serving a request would spend it.
        val session = controller ?: return@LaunchedEffect
        when (request) {
            // "Play music", nothing named. The playback service restores its
            // bounded queue before the controller connects, so this resumes
            // both a live session and one recovered after process death.
            LinkRequest.Resume -> if (session.mediaItemCount > 0) session.play()
            // A file from a file manager. The read grant that came with the
            // intent lasts as long as this task; a persistable one, when the
            // sender offered it, keeps the track playable after a restart.
            is LinkRequest.LocalFile -> {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        request.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val song = LocalMediaRepository.songFromUri(context, request.uri)
                playFrom(
                    listOf(song),
                    0,
                    QueueSource(openedFileLabel, PlaybackSourceType.SHARED_LINK, song.localUri),
                )
                showNowPlaying = true
            }
        }
        MusicLink.handled()
    }

    // ---- Album / playlist menu ----

    /**
     * Holding an album or playlist card, wherever one is drawn.
     *
     * Artists are left out. An artist page is a selection of their work rather
     * than a running order, and "add Radiohead to the queue" has no answer that
     * isn't a guess — so holding an artist card does nothing, as it did before.
     */

    /**
     * Hands [action] the target's whole track list.
     *
     * A card has no tracks behind it — its page was never opened — so the
     * listing is fetched first, all of it, and the menu that asked has already
     * closed by the time it lands. A release page's overflow passes the rows it
     * is already showing and this is immediate.
     *
     * Album rows arrive with no album name of their own, the same way they do on
     * the page (see `withAlbum` below), so the title is stamped on here too —
     * otherwise a track queued from an album card reaches the player and the
     * download folder with nothing to file it under.
     */
    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                songs.map { song ->
                    song.copy(
                        albumName = if (target.type == BrowseType.ALBUM) {
                            song.albumName ?: target.title
                        } else {
                            song.albumName
                        },
                        playbackSource = target.title,
                        playbackSourceType = PlaybackSourceType.BROWSE,
                        playbackSourceId = target.browseId,
                    )
                },
            )
        }
        when {
            target.songs.isNotEmpty() -> stamp(target.songs)
            target.browseId == null ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
            else -> viewModel.collectSongs(target.browseId, target.thumbnailUrl) { result ->
                result.fold(
                    onSuccess = stamp,
                    onFailure = {
                        val message = it.message ?: context.getString(R.string.couldnt_load_tracks)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    var openLocalMusicAfterPermission by remember { mutableStateOf(false) }
    var rescanAfterPermission by remember { mutableStateOf(false) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
            if (rescanAfterPermission) {
                rescanAfterPermission = false
                viewModel.rescanLibrary()
            }
            if (openLocalMusicAfterPermission) {
                openLocalMusicAfterPermission = false
                viewModel.openDetail(
                    browseId = "local:all",
                    title = context.getString(R.string.local_music),
                    subtitle = context.getString(R.string.audio_files_on_device),
                )
            }
        } else {
            openLocalMusicAfterPermission = false
            rescanAfterPermission = false
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    LaunchedEffect(linkRequest, isDirectAppLaunch) {
        if (linkRequest == null && isDirectAppLaunch &&
            detailStack.isEmpty() && navigator.entries.isEmpty() && selectedTab == TAB_LIBRARY
        ) {
            if (LocalMediaRepository.hasStoragePermission(context)) {
                viewModel.openDetail(
                    browseId = "local:all",
                    title = context.getString(R.string.local_music),
                    subtitle = context.getString(R.string.audio_files_on_device),
                )
            } else {
                openLocalMusicAfterPermission = true
                mediaPermissionLauncher.launch(mediaPermission)
            }
        }
    }
    // The Search tab's Library source reads the Local Music folder, so it asks
    // for the same permission opening that folder does.
    // Shared by the Library tab itself and by a shelf's "Show all" page, so a
    // card opens the same way from either.

    // Content padding leaves room for the frosted bar above and the tab bar
    // (plus mini player) below, so nothing is ever trapped under the glass.
    //
    // The top is measured off the bar rather than guessed at: the bar is pinned
    // under the status bar inset, and that inset varies by device and by window,
    // so a fixed number only ever lines up on the one device it was picked on.
    // See [topBarContentPadding].
    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    // What colour the page currently under the bars is. The fades either end
    // of the screen are flat colour wherever their blur has least to say, so
    // handing them the theme's background puts a black band on a page that is
    // washed in an artwork's colour instead. Off a detail page this resolves
    // to the theme's background anyway, which is exactly right there.
    //
    val detailPalette = rememberArtworkPalette(imageUrl = topDetail?.thumbnailUrl)

    // One set of numbers for the cards, the page, the stories and the shared
    // picture, so they cannot disagree. Read while any of them is on screen —
    // which includes the Library tab, since the cards live at the top of it.
    // See [rememberReplayState].
    val replayOpen = route == AppRoute.Replay || replayStory != null || showReplayShare ||
        (selectedTab == TAB_LIBRARY && detail == null && route == null)
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)

    // ---- The track in the player ----
    val playerSong = player.song

    // The player's whole parameter list, kept apart from the sheet that
    // mounts it so the sheet's own setup reads on its own.
    val nowPlaying: @Composable (Song) -> Unit = { song ->
        NowPlayingScreen(
            song = song,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            isPlaying = player.isPlaying,
            isLoading = playPauseBusy,
            position = player.position,
            durationMs = player.durationMs,
            onPlayPause = {
                togglePlayPause()
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    // Read at the moment of the seek, not from the
                    // polled snapshot the screen draws with: a track
                    // change updates the current item before it updates
                    // the duration, so a fraction dropped seconds after
                    // a transition would otherwise be scaled by the
                    // previous song's length.
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    // Clamped here rather than at each caller because
                    // not every caller can clamp. The scrubber's target
                    // is a fraction of the duration and cannot overrun,
                    // but a tapped lyric line seeks to a timestamp from
                    // whichever transcription matched on title, artist
                    // and duration — and a match against a slightly
                    // longer master puts every line late, so a tap near
                    // the end asks for a position past the end of this
                    // stream. Media3 answers that by clamping to the
                    // final millisecond, which ends the track and starts
                    // the next one: tapping the last line of a song
                    // skipped it.
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            likeStatus = likeStatusOf(song),
            onToggleLike = { toggleLikeOf(song) },
            // The service owns both the queue and the Shuffle state. Keeping
            // the toggle on that side prevents the UI from changing the icon
            // before its asynchronous reorder command has actually landed.
            onToggleShuffle = { controller?.toggleShuffle() },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Persist the new repeat mode so it survives app restarts.
                    AppSettings.setRepeatMode(next)
                    // Nothing else to do here: PlaybackService watches the
                    // repeat mode itself and takes AutoPlay's tracks out of
                    // the queue for the duration of repeat-all — and, unlike
                    // this screen, is still around to put them back when the
                    // loop ends.
                    it.repeatMode = next
                }
            },
            onJumpTo = { index ->
                controller?.let { c ->
                    // The session applies the jump plan to its own player (see
                    // its seekTo override); editing the queue from here would
                    // round-trip every item through the controller and lose
                    // their bounded-window metadata.
                    c.seekTo(index, 0L)
                    c.play()
                }
            },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            // The enriched copy, not player.song — otherwise the menu
            // hides the album and artist rows even once their browse
            // ids have been resolved.
            onOpenMenu = {
                menuFromPlayer = true
                songMenuOrigin = null
                songMenuAnchor = MenuAnchor.consume()
                songActions = song
                // Taken on the frame the menu opens on, which is long anyway,
                // rather than on one of its spring's.
                if (!AppSettings.reduceDynamicBlur.value && !playerLiveCanvas) playerBackdrop.capture()
            },
            // Pushed over whatever page is under the player, so back from it
            // returns there.
            onOpenAlbum = { id ->
                dismissPlayer()
                viewModel.openDetail(
                    id,
                    song.albumName ?: song.title,
                    song.artist,
                    song.thumbnailUrl,
                    BrowseType.ALBUM,
                )
            },
            // A credit with a browse id can open its page. Local files have no
            // online artist page to look up, so a name-only credit stays local.
            //
            // No artwork: this track's cover isn't the artist's
            // picture, and the page fills its own in once loaded.
            onOpenArtist = { id, name ->
                dismissPlayer()
                when {
                    id != null -> viewModel.openDetail(
                        id,
                        name,
                        context.getString(R.string.artist),
                        null,
                        BrowseType.ARTIST,
                    )
                    // A device file's byline names an artist as text, not as a
                    // page. The page it opens is the Local Music artist page for
                    // that name; only a name the device doesn't know falls back
                    // to saying so.
                    !viewModel.openLocalArtist(name) -> Toast.makeText(
                        context,
                        context.getString(R.string.couldnt_find, name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onOpenPlaybackSource = openSource@{
                val sourceType = song.playbackSourceType ?: PlaybackSourceType.QUEUE
                val sourceTitle = song.playbackSource
                    ?: song.albumName
                    ?: queueLabel
                val sourceId = song.playbackSourceId

                // A context link is navigation, not another page stacked over
                // Now Playing. Clear the current route before restoring it.
                dismissPlayer()
                navigator.clear()

                when (sourceType) {
                    PlaybackSourceType.BROWSE -> {
                        val id = sourceId ?: return@openSource
                        viewModel.openDetail(id, sourceTitle)
                    }
                    PlaybackSourceType.HISTORY -> {
                        navigator.push(AppRoute.History)
                        viewModel.loadHistory()
                    }
                    PlaybackSourceType.REPLAY -> {
                        replayLandingPage = ReplayStoryPage.INTRO
                        navigator.push(AppRoute.Replay)
                    }
                    // A station, a search hit or an opened file is over; what
                    // it built is the queue Now Playing opened from.
                    PlaybackSourceType.QUEUE,
                    PlaybackSourceType.SHARED_LINK,
                    PlaybackSourceType.SEARCH,
                    PlaybackSourceType.HOME,
                    PlaybackSourceType.EXPLORE -> Unit
                }
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = viewModel::selectLyricsProvider,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = showLyricsOffset,
            onDismissLyricsOffset = { showLyricsOffset = false },
            onClearQueue = {
                // Keep what's playing and context/autoplay; drop user-queued tracks.
                controller?.let { c ->
                    QueueCoordinator.clearUserQueue(c)
                }
            },
        )
    }

    val localMusicIsOnlyPage = detailStack.size == 1 &&
        detailStack.singleOrNull()?.browseId == "local:all" && navigator.entries.isEmpty()
    val removedLibraryLanding = route == null && detail == null && selectedTab == TAB_LIBRARY

    // One step back, from anywhere: the page on top of the stack, then a mood
    // or genre opened inside Explore, then any tab but Home back to Home. Only
    // Home itself hands back to the system, which is what actually closes or
    // minimizes the app. The gesture and the bar's back button both come here,
    // so they can never disagree about what is on top.
    val canGoBack = navigator.entries.isNotEmpty() || (detailStack.isNotEmpty() && !localMusicIsOnlyPage) ||
        selectedTab != TAB_LIBRARY
    val goBack: () -> Unit = {
        if (!navigator.pop()) selectedTab = TAB_LIBRARY
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Nothing of the page under a portrait player that is fully up:
            // that player is its own window, opaque from edge to edge, and
            // this one drawing on behind it — the page, the glass bar and the
            // blurs that sample it, a now-playing row's bars — is GPU time
            // taken from the player on every frame for nothing anyone can see.
            // The same test the player's own dim skips itself on. Composition
            // and layout carry on, so the page is current the moment the
            // player starts to come down and this draws again.
            .drawWithContent {
                if (!coveredByPlayer) drawContent()
            },
    ) {
        // A pushed page replaces the tab content but leaves the tab bar and
        // mini player in place.
        //
        // Back handlers answer newest-registered first, so the order below is
        // the order of precedence, lowest first: the page stack, then what is
        // drawn over a page, then the alerts drawn over everything. Each sheet
        // and dialog further down registers its own handler as it opens, and
        // so outranks all of these while it is up.
        BackHandler(enabled = canGoBack, onBack = goBack)
        // Replay's stories and its share sheet are drawn over everything, and
        // unwind in the order they were opened. Only while there is a summary
        // to draw them from — otherwise back would spend a step closing
        // something that never appeared.
        val replayDrawable = replay.summary?.isEmpty == false
        BackHandler(enabled = replayDrawable && replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(enabled = replayDrawable && showReplayShare) { showReplayShare = false }
        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }

        // On a tablet the page and the player stand side by side rather than
        // one over the other: everything a phone stacks in a single column —
        // the feed, the frosted bars, the tab row — becomes the left half of
        // a row, and the player is the right. Off a tablet the row has the
        // one child it always had and changes nothing.
        //
        // Everything a page's menu floats over, and so what [pageBackdrop]
        // takes its still of; the menus themselves are drawn outside it. On the
        // window's background, which the pages don't paint: see `paintBackdrop`.
        Row(
            Modifier
                .fillMaxSize()
                .snapshotBackdropSource(pageBackdrop, floor = MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    // Whatever is on top of the back stack — see [AppNavigator].
                    targetState = when {
                        visibleEntry != null -> AppDestination.Route(visibleEntry)
                        detail != null -> AppDestination.Release(detail.browseId)
                        else -> AppDestination.Tab(selectedTab)
                    },
                    // A route's entry is copied when the release pages under it
                    // change, which is not a different page.
                    contentKey = { it.key },
                    // Tabs swap outright; everything else crossfades.
                    //
                    // A tab is not a place you travel to — the bar is the whole
                    // navigation and it carries its own movement — so a fade
                    // between two of them only ever reads as a stutter. And it
                    // cannot read as anything else: neither page paints a
                    // background, so a crossfade dissolves both through to the
                    // window and the switch dips through a dimmer frame in the
                    // middle. Pushing a page or raising Settings is a real
                    // change of context and keeps the fade.
                    //
                    // "Show all" swapping with the Library tab underneath it is
                    // the same case as a tab swap, not a pushed page: it's still
                    // that tab, just laid out as a grid instead of a row, sharing
                    // its background rather than painting its own — so this one
                    // pair gets the tab's no-fade swap too, in both directions, or
                    // the dip through a dim frame shows up on every hold of a
                    // card there. A card opened *from* the grid is a real page
                    // and keeps the fade, same as one opened from the row.
                    transitionSpec = {
                        val tabSwap = initialState is AppDestination.Tab && targetState is AppDestination.Tab
                        if (tabSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (glassActive) {
                                Modifier
                                    // Not under "reduce dynamic blur": nothing
                                    // samples the layer then, and recording a
                                    // whole page into one for no reader is the
                                    // cost that setting exists to remove.
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    // Every page's scroll passes through here, so
                                    // the glass bar collapses on all of them
                                    // without each one having to know about it.
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { destination ->
                    // Every branch below reads `destination` rather than the
                    // stack that produced it. The two are the same thing only
                    // for the page being entered: the one on its way out is
                    // still composed, and asking it what is on top *now* has it
                    // redraw itself as its own replacement — which then fades
                    // out from under the identical copy fading in behind it.
                    val key = destination.key
                    val pageRoute = (destination as? AppDestination.Route)?.entry?.route
                    val live = (destination as? AppDestination.Release)?.let { release ->
                        detailStack.lastOrNull()?.takeIf { it.browseId == release.browseId }
                    }
                    // Held for the same reason, one step further on: a popped
                    // page is off the stack before it has finished animating
                    // out, so `live` goes null under it and it would spend its
                    // exit drawing whatever is underneath instead of itself.
                    // Per slot, since each is remembered against its own key.
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    // This state belongs to this AnimatedContent slot, not to
                    // whichever detail happens to be at the top of the stack
                    // while the slot is fading out.
                    val pageDetailListState = remember(key) {
                        detailListStates.getOrPut(key) { LazyListState() }
                    }
                    val pageContent: @Composable () -> Unit = {
                        if (pageRoute == AppRoute.History) {
                            HistoryScreen(
                                state = historyState,
                                listState = historyListState,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onSongClick = { songs, index ->
                                    playFrom(songs, index, QueueSource(historyLabel, PlaybackSourceType.HISTORY))
                                },
                                onSongLongPress = { song ->
                                    menuFromHistory = true
                                    openSongMenu(song)
                                },
                                onSongSwipe = onSongSwipe,
                                onRetry = viewModel::loadHistory,
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Replay) {
                            ReplayScreen(
                                state = replay,
                                holder = "",
                                onPeriodChange = setReplayPeriod,
                                onOpenStory = { replayStory = it },
                                // A track tapped on a chart is one the user already
                                // knows they like, so it starts a station off itself
                                // rather than queueing the chart it was on — the
                                // same reading [playRadio] makes of a search hit.
                                onPlaySong = { song -> play(listOf(song), 0) },
                                // Pushed over Replay, so back returns to it.
                                onOpenArtist = { id, name ->
                                    openByName(id, name, null, BrowseType.ARTIST)
                                },
                                onOpenAlbum = { id, title, artist, art ->
                                    openByName(id, title, artist, BrowseType.ALBUM, art)
                                },
                                onShare = {
                                    replaySharePage = null
                                    showReplayShare = true
                                },
                                contentPadding = listPadding,
                                listState = replayListState,
                                landingPage = replayLandingPage,
                            )
                        } else if (pageRoute == AppRoute.AccountScrobbling) {
                            LastFmSettingsOnly(
                                onOpenLogin = { showLastfmLogin = true },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Equalizer) {
                            EqualizerScreen(
                                contentPadding = listPadding,
                                onOpenPresets = { navigator.push(AppRoute.EqualizerPresets) },
                            )
                        } else if (pageRoute == AppRoute.EqualizerPresets) {
                            EqualizerPresetPage(
                                onChosen = { navigator.pop() },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute is AppRoute.SettingsChoice) {
                            SettingsChoicePage(
                                choice = pageRoute.choice,
                                onChosen = { navigator.pop() },
                                onConfirmBitPerfect = { confirmBitPerfect = it },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Settings || pageRoute is AppRoute.SettingsSection) {
                            // The main page and every category's page are the
                            // one screen, each showing its own share of the rows.
                            SettingsScreen(
                                section = (pageRoute as? AppRoute.SettingsSection)?.category,
                                onOpenSection = { navigator.push(AppRoute.SettingsSection(it)) },
                                onOpenChoice = { navigator.push(AppRoute.SettingsChoice(it)) },
                                windowWidth = windowWidth,
                                onAccountScrobbling = { navigator.push(AppRoute.AccountScrobbling) },
                                onEqualizer = { navigator.push(AppRoute.Equalizer) },
                                onOpenReplay = {
                                    replayLandingPage = ReplayStoryPage.INTRO
                                    navigator.push(AppRoute.Replay)
                                },
                                onLyricsSources = { showLyricsSources = true },
                                onTranslationLanguage = { showTranslationLanguage = true },
                                onAppLanguage = { showAppLanguage = true },
                                rescanningLibrary = libraryScanning,
                                lastScanLabel = lastScanLabel,
                                onRescanLibrary = {
                                    if (LocalMediaRepository.hasStoragePermission(context)) {
                                        viewModel.rescanLibrary()
                                    } else {
                                        // No files can be read yet: ask, then
                                        // run the scan this tap asked for.
                                        rescanAfterPermission = true
                                        mediaPermissionLauncher.launch(mediaPermission)
                                    }
                                },
                                onConfirmBitPerfect = { confirmBitPerfect = it },
                                onConfirmFixedVolume = { confirmFixedVolume = true },
                                contentPadding = listPadding,
                            )
                        } else if (page != null) {
                            // Local Music, Downloads and the other device folders are
                            // this same release page: the header any playlist has, then
                            // Songs / Artists / Albums under it. An album or artist
                            // picked there opens as a page of its own — see LocalGroups.
                            val folder = if (page.browseId.isDeviceFolder()) {
                                // The whole device: the one folder that is an offline
                                // player in its own right, with playlists of its own
                                // and the directories the files are kept in.
                                val isDevice = page.browseId == "local:all"
                                LocalFolder(
                                    state = localFolderStates.getOrPut(page.browseId) { LocalFolderState() },
                                    sort = folderSortOf(page.browseId),
                                    viewType = folderViewTypeOf(page.browseId),
                                    selectable = false,
                                    onOpenGroup = viewModel::openLocalGroup,
                                    onGroupLongPress = { group ->
                                        openBrowseMenu(
                                            BrowseTarget(
                                                browseId = group.browseId,
                                                title = group.title,
                                                subtitle = group.subtitle,
                                                thumbnailUrl = group.thumbnailUrl,
                                                type = group.type,
                                                songs = group.songs,
                                            ),
                                        )
                                    },
                                    tabs = if (isDevice) LocalFolderTab.DEVICE else LocalFolderTab.BASIC,
                                    showTabs = !isDevice,
                                    playlists = if (isDevice) offlinePlaylistGroups else emptyList(),
                                    onCreatePlaylist = if (isDevice) ({ creatingOfflinePlaylist = true }) else null,
                                )
                            } else {
                                null
                            }
                            // An album page's rows carry no album name of their own â€” the
                            // release is billed once, in the header the rows hang under â€” so
                            // the page title is stamped on as they leave for the track menu.
                            val withAlbum: (Song) -> Song = { song ->
                                if (page.type == BrowseType.ALBUM) {
                                    song.copy(albumName = song.albumName ?: page.title)
                                } else {
                                    song
                                }
                            }
                            // The visible list can be one render behind the page
                            // while fill-in is completing. For a local release
                            // handoff, cancel that fill and use the newest complete
                            // page snapshot; Cast deliberately keeps the old full
                            // queue path.
                            fun playDetailSongs(songs: List<Song>, index: Int) {
                                val source = QueueSource(
                                    page.title,
                                    PlaybackSourceType.BROWSE,
                                    page.browseId,
                                )
                                val selected = songs.getOrNull(index)
                                val pageSongKeys =
                                    (page.songs as? UiState.Success)?.data.orEmpty().mapTo(HashSet()) { it.contextEntryKey() }
                                val useBounded =
                                    (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
                                        selected?.contextEntryKey()?.let(pageSongKeys::contains) == true
                                val latestPage = if (useBounded) {
                                    viewModel.takePlaybackContext(page.browseId)
                                } else {
                                    null
                                }
                                val latestSongs = latestPage
                                    ?.let { (it.songs as? UiState.Success)?.data.orEmpty() }
                                    ?.map(withAlbum)
                                    ?.takeIf { it.isNotEmpty() }
                                val queue = latestSongs ?: songs.map(withAlbum)
                                if (queue.isEmpty()) return
                                val queueIndex = selected?.let { tapped ->
                                    queue.indexOfFirst { it.contextEntryKey() == tapped.contextEntryKey() }
                                }?.takeIf { it >= 0 } ?: index
                                playFrom(
                                    queue,
                                    queueIndex.coerceIn(queue.indices),
                                    source,
                                    boundedContext = useBounded,
                                    continuationToken = null,
                                )
                            }
                            DetailScreen(
                                page = page,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                listState = pageDetailListState,
                                // Album/playlist playback hands the page snapshot to
                                // the service, which then owns continuation loading.
                                onSongClick = ::playDetailSongs,
                                onSongLongPress = { openSongMenu(withAlbum(it)) },
                                onSongSwipe = { onSongSwipe(withAlbum(it)) },
                                onShuffle = { songs ->
                                    if (songs.isNotEmpty()) {
                                        // Shuffle goes on first so the queue is built shuffled
                                        // as it is set — the random pick here only decides
                                        // which track leads it.
                                        QueueShuffle.enableForNextQueue()
                                        playDetailSongs(songs, songs.indices.random())
                                    }
                                },
                                // Only the page on its way in answers to the bar's
                                // search button; one fading out keeps its list whole.
                                searching = if (isLocalDetail) localSearchOpen else {
                                    detailSearching && page.browseId == detail?.browseId
                                },
                                alwaysShowSearch = false,
                                onSearchingChange = {
                                    if (isLocalDetail) localSearchOpen = it else detailSearching = it
                                },
                                onArtistClick = { id, name ->
                                    if (id != null) {
                                        viewModel.openDetail(
                                            id,
                                            name,
                                            context.getString(R.string.artist),
                                            null,
                                            BrowseType.ARTIST,
                                        )
                                    } else {
                                        // A device row's credit: the local artist page.
                                        viewModel.openLocalArtist(name)
                                    }
                                },
                                // Saving is an account action, so it isn't offered to a
                                // guest at all — same as the like and add-to-playlist rows
                                // in the track menu.
                                songSort = songSort,
                                folder = folder,
                                contentPadding = listPadding,
                            )
                        } else when ((destination as? AppDestination.Tab)?.index ?: selectedTab) {
                            TAB_SEARCH -> SearchScreen(
                                query = query,
                                onQueryChange = viewModel::onQueryChange,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                listState = searchListState,
                                scrollResetTrigger = searchScrollReset,
                                focusRequested = searchFocusRequested,
                                onFocusHandled = { searchFocusRequested = false },
                                onSongClick = { songs, index -> play(songs, index) },
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                history = searchHistory,
                                onSubmit = viewModel::submitSearch,
                                onHistoryClick = { entity -> viewModel.onQueryChange(entity.title) },
                                onHistoryRemove = viewModel::removeSearch,
                                onHistoryClear = viewModel::clearSearchHistory,
                                contentPadding = listPadding,
                                topPadding = topBarHeight() + 4.dp,
                                showField = false,
                                searchingLibrary = true,
                                libraryResults = libraryResults,
                                onLibrarySongClick = { songs, index ->
                                    playFrom(
                                        songs,
                                        index,
                                        QueueSource(
                                            context.getString(R.string.local_music),
                                            PlaybackSourceType.BROWSE,
                                            "local:all",
                                        ),
                                    )
                                },
                            )
                            // Local Music is the app's home now. The old Library
                            // landing page is intentionally no longer a destination.
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                    // A pushed page keeps its saved state — a scroll position,
                    // a search typed into Settings — for as long as it is on
                    // the stack, so coming back to it finds it as it was left.
                    // Tabs keep theirs hoisted above, as they always have.
                    //
                    // The playing bars hold still with the player up, the app in
                    // the background, or this page on its way out: each frame of
                    // them redraws the page under the glass, for nobody.
                    val barsMotion = artworkMotion && transition.targetState == EnterExitState.Visible
                    CompositionLocalProvider(LocalPlayingBarsMotion provides barsMotion) {
                        if (destination is AppDestination.Tab) {
                            pageContent()
                        } else {
                            pageKeysSeen += key
                            pageStates.SaveableStateProvider(key, pageContent)
                        }
                    }
                }

                // The top-bar footprint is transparent on every page so the
                // shared app-level gradient is continuous; its controls float
                // as separate circles in both the glass and blur materials.
                val isDetailVisible = detail != null &&
                    (detail.type == BrowseType.ALBUM ||
                        detail.type == BrowseType.PLAYLIST ||
                        detail.type == BrowseType.ARTIST) &&
                    !isLocalDetail
                val chromePageColor = if (isDetailVisible) {
                    detailPalette.background
                } else {
                    MaterialTheme.colorScheme.background
                }
                // Apple's scroll edge: what passes up under the status bar and the
                // controls is blurred — hardest at the top, none just below the
                // controls — rather than faded out to the page colour. It is a
                // Liquid Glass option: with glass off, the Progressive Blur setting
                // off, or under reduce dynamic blur, the bottom floor's own fade
                // stands in, turned upside down, so both edges share its curve
                // and colour.
                // On an artist page the photograph and logo run up under the status
                // bar, and either across them would only muddy them. It comes in as
                // the page scrolls past that header, when content starts passing
                // under the bar and needs the cover.
                //
                // Home's wordmark sits up there too, lifted under the bar by
                // HOME_TITLE_LIFT, and at rest the fade washed it out. It is held
                // off the same way, from the first pixel of scroll rather than
                // partway through, since the wordmark is the top of its heading.
                if (reduceDynamicBlur || !glassActive || !progressiveBlur) {
                    BottomFadeScrim(
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .rotate(180f),
                    )
                } else {
                    TopFadeBlur(
                        hazeState = hazeState,
                        pageColor = chromePageColor,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                FrostedTopBar(
                    title = when (route) {
                        AppRoute.History -> stringResource(R.string.history)
                        AppRoute.AccountScrobbling -> stringResource(R.string.account_scrobbling)
                        AppRoute.Equalizer -> stringResource(R.string.equalizer)
                        AppRoute.Settings -> stringResource(R.string.settings)
                        is AppRoute.SettingsSection -> stringResource(route.category.title)
                        is AppRoute.SettingsChoice -> stringResource(route.choice.title)
                        AppRoute.EqualizerPresets -> stringResource(R.string.equalizer_preset)
                        AppRoute.Replay -> stringResource(R.string.replay)
                        null -> when {
                            detail != null -> detail.title.takeUnless { detail.browseId == "local:all" }.orEmpty()
                            else -> if (removedLibraryLanding || selectedTab == TAB_SEARCH) "" else libraryLabel
                        }
                    },
                    // Every page, in either material, uses separated floating
                    // circles over the shared gradient — no full-width pane.
                    transparentBackdrop = true,
                    artworkPageChrome = true,
                    backButtonHazeState = hazeState,
                    // Search has no large in-list header to hand the title back to —
                    // the field takes that space — so its bar title is always up.
                    scrolled = {
                        when {
                            // The page leads with its own large "Replay", so the bar
                            // stays out of the way until that has been scrolled off.
                            route == AppRoute.Replay -> replayScrolled
                            route != null -> true
                            detail != null -> detailScrolled
                            else -> scrolled || selectedTab == TAB_SEARCH
                        }
                    },
                    // The same step the back gesture takes — see [goBack] —
                    // offered on anything pushed over a tab.
                    onBack = when {
                        route != null || (detail != null && !localMusicIsOnlyPage) -> goBack
                        else -> null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                    // Only on the Search tab itself, not on anything pushed over it.
                    accessory = if (
                        selectedTab == TAB_SEARCH && route == null && detail == null
                    ) {
                        {
                            // The search field lives up here in the bar, beside the
                            // account photo, so it answers the nav bar's Search tap
                            // itself: focus and keyboard, the way the page used to.
                            val keyboard = LocalSoftwareKeyboardController.current
                            LaunchedEffect(searchFocusRequested) {
                                if (searchFocusRequested) {
                                    searchFieldFocus.requestFocus()
                                    keyboard?.show()
                                    searchFocusRequested = false
                                }
                            }
                            SearchField(
                                query = query,
                                onQueryChange = viewModel::onQueryChange,
                                onSubmit = viewModel::submitSearch,
                                focusRequester = searchFieldFocus,
                                placeholder = org.jetbrains.compose.resources.stringResource(SharedRes.string.search_library_hint),
                            )
                        }
                    } else null,
                    actions = {
                        // None on Settings or the pages opened from it: those are
                        // about the account, and the photo would only reopen them.
                        if (!(route != null && AppRoute.Settings in navigator)) {
                            // The entries that are not lists of tracks: what has
                            // been played, and every setting behind it. The app's
                            // home is Local Music now, which carries the same pair
                            // on its own bar below.
                            if (route == null && detail == null && selectedTab == TAB_LIBRARY) {
                                IconButton(
                                    onClick = {
                                        navigator.push(AppRoute.History)
                                        viewModel.loadHistory()
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.History,
                                        contentDescription = stringResource(R.string.listening_history),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                IconButton(onClick = { navigator.push(AppRoute.Settings) }) {
                                    Icon(
                                        Icons.Rounded.Settings,
                                        contentDescription = stringResource(R.string.settings),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            // Left of the account photo, and only on a Library
                            // "Show all" grid — the same control the Downloads
                            // folder offers (see `LocalSearchField`), adapted to
                            // the one thing a playlist or album card carries: a
                            // title.
                            // Sort, only on an album or playlist page or a device
                            // folder — an artist page has no single track list to
                            // reorder. A folder's opens its own menu: its sort
                            // orders, and list or grid for its tabs.
                            //
                            // Search and the overflow flank it, as the share and
                            // "…" pair do on Apple Music's album page: the header
                            // below is left with just the sleeve, the title and
                            // Shuffle / Play / Add.
                            if ((isReleaseDetail || isLocalDetail) && detail != null) {
                                val releaseSongs = (detail.songs as? UiState.Success)?.data.orEmpty()
                                // Local Music's own page is the one with tabs: its
                                // search filters every tab, and its sort orders the
                                // folder. A page opened *out* of it — an artist,
                                // album or directory — is an ordinary track list, so
                                // it takes the per-list search and song sort any
                                // release page takes. The test is the folder itself,
                                // not "is Local Music in the stack": that stays true
                                // on its sub-pages and used to hand them the folder's
                                // controls, whose order those pages ignore.
                                if (!isLocalDetail && releaseSongs.isNotEmpty()) {
                                    IconButton(onClick = { detailSearching = !detailSearching }) {
                                        Icon(
                                            if (detailSearching) Icons.Rounded.Close else JuniIcons.Search,
                                            contentDescription = stringResource(
                                                if (detailSearching) R.string.close_search else R.string.search_this_list,
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (isLocalDetail) {
                                    IconButton(
                                        onClick = { localSearchOpen = !localSearchOpen },
                                    ) {
                                        Icon(
                                            if (localSearchOpen) Icons.Rounded.Close else JuniIcons.Search,
                                            contentDescription = stringResource(
                                                if (localSearchOpen) R.string.close_search else R.string.search_this_list,
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    IconButton(onClick = { folderMenuOpen = true }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Sort,
                                            contentDescription = stringResource(R.string.sort_music),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                } else {
                                    // The menu itself is [FrostedSortMenu], composed
                                    // with the app's other frosted overlays further
                                    // down — in the main hierarchy, where the haze
                                    // can see the content it blurs.
                                    IconButton(onClick = { songSortMenuOpen = true }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Sort,
                                            contentDescription = stringResource(R.string.sort_songs),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                // History and Settings: the app's home entries, kept
                                // on Local Music and the pages opened out of it.
                                if (localMusicRootOpen) {
                                    IconButton(
                                        onClick = {
                                            navigator.push(AppRoute.History)
                                            viewModel.loadHistory()
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.History,
                                            contentDescription = stringResource(R.string.listening_history),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    IconButton(onClick = { navigator.push(AppRoute.Settings) }) {
                                        Icon(
                                            Icons.Rounded.Settings,
                                            contentDescription = stringResource(R.string.settings),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (isReleaseDetail && releaseSongs.isNotEmpty()) {
                                    // The page's own tracks, in the order the page
                                    // shows them, so the sheet has them already and
                                    // Play, Shuffle and Open are the buttons beside
                                    // the one that opened it rather than rows on it.
                                    // Download lives in the sheet too — and the
                                    // tracks arrive stamped with the album they came
                                    // off, which is what the download record groups
                                    // them under.
                                    IconButton(
                                        modifier = Modifier.menuAnchor(),
                                        onClick = {
                                            openBrowseMenu(BrowseTarget(
                                                browseId = detail.browseId,
                                                title = detail.title,
                                                subtitle = detail.subtitle,
                                                thumbnailUrl = detail.thumbnailUrl,
                                                type = detail.type,
                                                songs = releaseSongs.sortedForDetail(songSort).map { song ->
                                                    if (detail.type == BrowseType.ALBUM) {
                                                        song.copy(albumName = song.albumName ?: detail.title)
                                                    } else {
                                                        song
                                                    }
                                                },
                                                fromCard = false,
                                            ))
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.MoreHoriz,
                                            contentDescription = stringResource(R.string.more),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )

                // Drawn before the bars so their own glass reads on top of it.
                BottomFadeScrim(
                    withMiniPlayer = player.song != null,
                    // Not the wash: by the foot of the screen the page has finished
                    // easing out of it and into this, so this is what is actually
                    // under the tab bar.
                    pageColor = chromePageColor,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Whichever bar is drawing reports its height here, for the
                // guard to read at layout — never in composition, see the guard.
                val floatingBarsHeight = remember { mutableIntStateOf(0) }
                FloatingBarsTapGuard(
                    barsHeight = { floatingBarsHeight.intValue },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // One tab handler, whichever bar is drawing it.
                // A tab is a fresh start: everything pushed over the old one —
                // routes and release pages alike — goes with it.
                val onTabSelected: (Int) -> Unit = { index ->
                    if (localMusicState != null) {
                        // Settings and other pushed pages sit above the local
                        // library. Clear that stack before changing the local
                        // category, otherwise the selected icon changes while
                        // the pushed page keeps covering the library.
                        navigator.clearRoutes()
                        while (
                            viewModel.detailStack.value.isNotEmpty() &&
                            viewModel.detailStack.value.lastOrNull()?.browseId != "local:all"
                        ) {
                            viewModel.closeDetail()
                        }
                        if (index == 2) {
                            localSearchOpen = true
                        } else {
                            localSearchOpen = false
                            localMusicState.tab = when (index) {
                                0 -> LocalFolderTab.SONGS
                                1 -> LocalFolderTab.ALBUMS
                                3 -> LocalFolderTab.ARTISTS
                                else -> LocalFolderTab.FOLDERS
                            }
                        }
                    } else {
                        navigator.clear()
                        selectedTab = index

                        // Every search tab tap resets the field, focuses it, and opens
                        // the keyboard through SearchScreen's focus request.
                        if (index == TAB_SEARCH) {
                            viewModel.onQueryChange("")
                            if (LocalMediaRepository.hasStoragePermission(context)) {
                                viewModel.loadLibrarySongs()
                            } else {
                                mediaPermissionLauncher.launch(mediaPermission)
                            }
                            searchFocusRequested = true
                        }
                    }
                }

                if (!removedLibraryLanding) {
                if (glassActive) Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                ) {
                    QueueActionNoticeHost(queueNotice)
                    // Liquid glass replaces the two stacked bars with the single
                    // component they are stacked to imitate: the now playing
                    // controls dock into the tab bar rather than riding above it,
                    // and the pair folds together on scroll. See [GlassNavBar].
                    GlassNavBar(
                        tabs = bottomTabs,
                        selectedIndex = bottomSelectedIndex,
                        standaloneIndex = if (localMusicState != null) -1 else null,
                        onTabSelected = onTabSelected,
                        scrollConnection = navBarScroll,
                        song = player.song,
                        isPlaying = player.isPlaying,
                        isLoading = playPauseBusy,
                        onPlayPause = {
                            togglePlayPause()
                        },
                        onNext = { controller?.seekToNextMediaItem() },
                        onPrevious = { controller?.seekToPrevious() },
                        onExpand = if (reducePlayerMotion) {
                            { showNowPlaying = true }
                        } else {
                            playerSheetMotion::open
                        },
                                    dock = activeDock,
                        pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Capped and centred rather than run to the page's edges
                        // — see [FLOATING_BAR_MAX_WIDTH]. It sits on the Column
                        // rather than on each bar so the two are held to the same
                        // width and keep the shared left and right edge they have
                        // on a phone. Before fillMaxWidth, so the fill has
                        // already been bounded by the time it is applied.
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QueueActionNoticeHost(queueNotice)
                    player.song?.let { song ->
                        MiniPlayer(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = playPauseBusy,
                            hazeState = hazeState,
                            onPlayPause = {
                                togglePlayPause()
                            },
                            onNext = { controller?.seekToNextMediaItem() },
                            onPrevious = { controller?.seekToPrevious() },
                            onExpand = if (reducePlayerMotion) {
                                { showNowPlaying = true }
                            } else {
                                playerSheetMotion::open
                            },
                                            dock = activeDock,
                            pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    FloatingBottomBar(
                        tabs = bottomTabs,
                        selectedIndex = bottomSelectedIndex,
                        hazeState = hazeState,
                        onTabSelected = onTabSelected,
                        standaloneIndex = if (localMusicState != null) -1 else null,
                    )
                }
                }
            }

        }

        val playerRaised = showNowPlaying && playerSong != null

        // ---- The page's still, for a menu hung over it ----
        // Taken as a ⋮ or ⋯ menu opens over a page, let go once it has played
        // its way out — see [pageBackdrop]. Read in a scope of its own, as
        // the menus are below, so a menu opening recomposes only this.
        Isolated {
            val pageMenuUp =
                (songActions != null && songMenuOrigin == null && !(menuFromPlayer && playerRaised)) ||
                    (browseActions != null && browseMenuOrigin == null && !browseRenameInSheet)
            LaunchedEffect(pageMenuUp, reduceDynamicBlur) {
                if (pageMenuUp && !reduceDynamicBlur && isGlassSupported()) {
                    pageBackdrop.capture()
                } else {
                    delay(SNAPSHOT_RELEASE_DELAY_MS)
                    pageBackdrop.release()
                }
            }
        }

        // ---- Long-press track actions ----
        // One body for every frame the track menu comes in: the panel hung off
        // a ⋮ or the player's ⋯, and the one a held row lifts into. Every
        // callback below is the same in each; only the frame differs.
        val songMenuBody: @Composable (Song) -> Unit = { heldSong ->
            // Set by whoever opened it — see [menuFromPlayer]. It cannot be
            val fromPlayer = menuFromPlayer
            val song = heldSong
            // Navigating has to take the player down with the sheet, or the
            // page it opens lands behind a still-covering player.
            // The track's cover stands in for an album's, but never for an
            // artist's picture — that page loads its own.
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                dismissPlayer()
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(id, title, sub, art, type)
            }
            val automixOn by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
            SongActionsMenu(
                song = song,
                likeStatus = likeStatusOf(song),
                onPlayNext = { playNext(song); songActions = null },
                onAddToQueue = { addToQueue(song); songActions = null },
                // Stays open: the row it replaces itself with is the
                // progress, and closing the sheet would hide the only
                // answer to "did that work?".
                // The sheet stays up for a rating: it shows the new state
                // in place, and people often thumb a song and then queue it.
                onToggleLike = { toggleLikeOf(song) },
                onAddToPlaylist = {
                    songActions = null
                    offlinePlaylistTarget = song
                },
                onRemoveFromPlaylist = detail?.browseId
                    ?.takeIf { !fromPlayer && song.localUri != null && OfflinePlaylists.isOfflinePlaylist(it) }
                    ?.let { id ->
                        {
                            songActions = null
                            OfflinePlaylists.remove(id, song)
                        }
                    },
                onRemoveFromHistory = if (menuFromHistory && song.localUri != null) {
                    {
                        songActions = null
                        viewModel.removeFromHistory(song)
                    }
                } else {
                    null
                },
                onOpenAlbum = { id ->
                    if (id.isBlank()) {
                        // A device row knows its release by name only.
                        songActions = null
                        dismissPlayer()
                        val album = song.albumName
                        if (album.isNullOrBlank() || !viewModel.openLocalAlbum(album, song.artist)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.couldnt_find, album ?: song.title),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        openPage(
                            id,
                            song.albumName ?: song.title,
                            song.artist,
                            BrowseType.ALBUM,
                        )
                    }
                },
                onOpenArtist = { id ->
                    if (id.isBlank()) {
                        songActions = null
                        dismissPlayer()
                        if (!viewModel.openLocalArtist(song.artist)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.couldnt_find, song.artist),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        openPage(id, song.artist, context.getString(R.string.artist), BrowseType.ARTIST)
                    }
                },
                showSleepTimer = fromPlayer,
                onClearQueue = if (fromPlayer) {
                    {
                        // No confirmation and no notice: the queue panel behind
                        // the player is the feedback, showing one row left.
                        songActions = null
                        controller?.clearQueue()
                    }
                } else {
                    null
                },
                onCopyLog = if (fromPlayer) {
                    {
                        songActions = null
                        scope.launch {
                            val text = TrackLog.forTrack(song, NerdStats.current.value)
                            clipboard.setText(AnnotatedString(text))
                            // The line count, not just "copied": it is the
                            // one thing the system's own paste confirmation
                            // doesn't say, and an empty log is a real
                            // outcome worth seeing rather than a silent one.
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.log_copied_line_count,
                                    text.lineSequence().count(),
                                    text.lineSequence().count(),
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                } else {
                    null
                },
                onLyricsOffset = if (fromPlayer) {
                    {
                        songActions = null
                        showLyricsOffset = true
                    }
                } else {
                    null
                },
                // The popup lives in the player, so the menu only has to close
                // and ask for it; it lays the next transition out on its own.
                onAutomixOverview = if (fromPlayer && automixOn) {
                    {
                        songActions = null
                        MixViewRequest.open = true
                    }
                } else {
                    null
                },
            )
        }
        // ---- Now Playing ----
        if (playerRaised) {
            // What rememberModalBottomSheetState builds, but able to start open,
            // and with every hide routed through [playerSheetMotion] — a drag
            // released low, a back press, a tap on the scrim. Refused here and
            // carried out there, on a curve slow enough to watch the artwork
            // fly home on, rather than on the sheet's own.
            val sheetDensity = LocalDensity.current
            val confirmSheetValue: (SheetValue) -> Boolean = { value ->
                // Read when asked, not when the sheet was made: the setting
                // can change while the player is up.
                if (value == SheetValue.Hidden && !reducePlayerMotion) {
                    playerSheetMotion.close()
                    false
                } else {
                    true
                }
            }
            val nowPlayingSheetState = rememberSaveable(
                saver = SheetState.Saver(
                    skipPartiallyExpanded = true,
                    confirmValueChange = confirmSheetValue,
                    density = sheetDensity,
                    skipHiddenState = false,
                ),
            ) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = sheetDensity,
                    // Already open when the app is the one raising it: its
                    // content is held down, and slid up, by the motion.
                    initialValue = if (playerSheetMotion.holding) SheetValue.Expanded else SheetValue.Hidden,
                    confirmValueChange = confirmSheetValue,
                )
            }
            DisposableEffect(nowPlayingSheetState) {
                playerSheetMotion.sheet = nowPlayingSheetState
                playerDock.sheetOffset = playerSheetMotion::position
                onDispose {
                    playerDock.sheetOffset = null
                    playerSheetMotion.onSheetGone()
                }
            }
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                sheetState = nowPlayingSheetState,
                // None of M3's: it follows the sheet's own animation, which is
                // no longer what moves the player. The player draws its own,
                // off where the player actually is — see the content below.
                scrimColor = if (reducePlayerMotion) BottomSheetDefaults.ScrimColor else Color.Transparent,
                // The player fills the screen and paints its own background to
                // the very top, so the sheet's default 28.dp top corners would
                // only cut two notches out of the artwork behind the status bar.
                // Square, and not clipped along its top — see [PlayerSheetShape].
                shape = PlayerSheetShape,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                // M3 caps a bottom sheet at [BottomSheetDefaults.SheetMaxWidth]
                // (640.dp) and centres it once the window is wider than that —
                // built for a sheet that's meant to look like a sheet next to
                // visible content either side. This one is the whole player;
                // capped at 640dp on a tablet it renders as a narrow card with
                // the page it's supposed to be covering visible down both
                // sides. Unspecified opts out of the cap entirely, so the
                // sheet always spans the full window this app draws it for.
                sheetMaxWidth = Dp.Unspecified,
                // Back is the app's to carry out, like every other close — see
                // the handler below. Left to M3, a back press is the one close
                // that never asks `confirmValueChange`: the sheet hides itself
                // straight away on its own curve, then the window leaves on its
                // exit animation with the artwork still drawn in it.
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = reducePlayerMotion),
            ) {
                // With M3's own back off, the dialog's dispatcher ends here.
                // Composed ahead of the player, so the player's own handlers —
                // the lyrics and the queue putting themselves away first — are
                // newer and are asked before this one.
                BackHandler(enabled = !reducePlayerMotion) { playerSheetMotion.close() }
                // Keeps a sheet still "settling" after a lyrics or queue
                // scroll from taking the next touch meant for that list.
                // See [PlayerSheetMotion.attachWindow].
                val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                // The player's window holds focus while it is up, so the volume
                // keys reach it rather than the activity.
                DacVolumeKeys()
                DisposableEffect(sheetWindow, reducePlayerMotion) {
                    if (!reducePlayerMotion) playerSheetMotion.attachWindow(sheetWindow)
                    onDispose { }
                }
                Box(
                    Modifier
                        // Where the app is holding the player, if it is — see
                        // [PlayerSheetMotion.contentOffsetPx]. Placement only.
                        .offset { IntOffset(0, playerSheetMotion.contentOffsetPx()) }
                        // The dim behind the player, from the window's top to
                        // its bottom whatever the player's offset, and as deep
                        // as the player is open.
                        .drawBehind {
                            val open = playerDock.openFraction()
                            // Not at all under a portrait player that is fully up:
                            // its own background is opaque, and a full-screen
                            // blend behind it is paid on every frame the window
                            // draws for nothing anyone can see.
                            if (!reducePlayerMotion && open > 0f && !(open >= 1f && playerDock.attached)) {
                                val top = playerSheetMotion.position().takeUnless { it.isNaN() } ?: 0f
                                drawRect(
                                    color = Color.Black.copy(alpha = PLAYER_SCRIM_ALPHA * open),
                                    topLeft = Offset(0f, -top.toInt().toFloat()),
                                    size = Size(size.width, size.height),
                                )
                            }
                        }
                        .guardSheetFromContentTouches(nowPlayingSheetState)
                        // The sheet fills the window, so its height is the
                        // whole of its travel: hidden sits that far down.
                        .onSizeChanged {
                            playerDock.sheetTravel = it.height.toFloat()
                            playerSheetMotion.laidOut = true
                        },
                ) {
                    // The still the ⋯ menu below blurs under its panel — taken
                    // by the tap that opens it (see onOpenMenu), not the player
                    // live, which re-rendered all of it under the panel on every
                    // frame anything in it moved (see [SnapshotBackdrop]). Let
                    // go once the menu has played its way out. Drawn in this
                    // sheet's own window, as the menu has to be to sit above
                    // the player. Not under "reduce dynamic blur": the panel
                    // is opaque then.
                    val playerMenuUp = songActions != null && songMenuOrigin == null && menuFromPlayer
                    LaunchedEffect(playerMenuUp, reduceDynamicBlur) {
                        if (!playerMenuUp || reduceDynamicBlur) {
                            delay(SNAPSHOT_RELEASE_DELAY_MS)
                            playerBackdrop.release()
                        }
                    }
                    Box(
                        when {
                            reduceDynamicBlur -> Modifier
                            playerLiveCanvas -> Modifier.layerBackdrop(livePlayerBackdrop)
                            else -> Modifier.snapshotBackdropSource(playerBackdrop)
                        },
                    ) {
                        CompositionLocalProvider(LocalPlayerDock provides activeDock) {
                            nowPlaying(playerSong)
                        }
                    }
                    IosMenuPopover(
                        item = songActions?.takeIf { songMenuOrigin == null && menuFromPlayer },
                        anchor = songMenuAnchor,
                        onDismiss = { songActions = null },
                        backdrop = if (playerLiveCanvas) livePlayerBackdrop else playerBackdrop,
                    ) { song ->
                        songMenuBody(song)
                    }
                }
            }
        }

        // ---- Replay stories ----
        // Mounted out here rather than inside the pane above, because a story
        // covers the window: on a tablet the page is only the left half of the
        // row, and a story laid out inside it would run alongside the player
        // instead of over it.
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card ->
                        replaySharePage = card
                        showReplayShare = true
                    },
                    paused = showReplayShare,
                )
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    // Straight to full height. The sheet is a picture and two
                    // buttons, and half-open it showed the picture with both
                    // buttons below the fold — a sheet whose only two controls
                    // need a drag to reach.
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = "",
                        memberSince = replay.memberSince,
                        page = replaySharePage,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }

        // ---- Track menu ----
        // In a recompose scope of its own: whether a menu is up is read in
        // here, and read in the app's own body it recomposed all of the app
        // on the frame the menu starts to grow — the stutter at the start of
        // its spring, blur or no blur.
        Isolated {
            // Drawn over everything, tab bar and mini player included, and kept
            // composed through its own exit — so it is not gated on songActions.
            HeldContextMenu(
                item = songActions,
                held = songMenuOrigin,
                hazeState = hazeState,
                onDismiss = { songActions = null },
                // Tapping the lifted track opens its album, as Apple Music's does.
                onPreviewClick = { song ->
                    song.albumId?.let { id ->
                        songActions = null
                        viewModel.openDetail(id, song.albumName ?: song.title, song.artist, song.thumbnailUrl, BrowseType.ALBUM)
                    }
                },
            ) { song ->
                songMenuBody(song)
            }
            // A tap on a ⋮ — or on the player's ⋯ while the player is part of the
            // page, as on a tablet — hangs the same menu off that button. While the
            // player is a sheet the sheet draws its own (above), so it can sit
            // over the player and blur it.
            // A player menu goes with the player: left up, it would carry on here,
            // over the page.
            LaunchedEffect(playerRaised) {
                if (!playerRaised && menuFromPlayer) songActions = null
            }
            IosMenuPopover(
                item = songActions?.takeIf { songMenuOrigin == null && !(menuFromPlayer && playerRaised) },
                anchor = songMenuAnchor,
                onDismiss = { songActions = null },
                backdrop = pageBackdrop,
                hazeState = hazeState,
            ) { song ->
                songMenuBody(song)
            }
            // A closed menu forgets where it was held and what it hung off, so
            // whatever opens it next starts from nothing.
            LaunchedEffect(songActions == null) {
                if (songActions == null) {
                    songMenuOrigin = null
                    songMenuAnchor = null
                    menuFromHistory = false
                }
            }
        }


        // ---- Add to a device playlist / new device playlist ----
        // The sheet above over the offline player's own playlists, for a track
        // that is a file on this device. Nothing here waits on a network, so
        // there is no loading state and the outcome is known on the tap.
        if (offlinePlaylistTarget != null || creatingOfflinePlaylist) {
            val target = offlinePlaylistTarget
            val dismiss = {
                offlinePlaylistTarget = null
                creatingOfflinePlaylist = false
            }
            val choices = remember(offlinePlaylists) {
                offlinePlaylists.map { playlist ->
                    UserPlaylist(
                        playlistId = playlist.id,
                        title = playlist.title,
                        subtitle = context.resources.getQuantityString(
                            R.plurals.song_count_plural,
                            playlist.songs.size,
                            playlist.songs.size,
                        ),
                        thumbnailUrl = playlist.songs.firstNotNullOfOrNull { it.thumbnailUrl }
                            .takeUnless { playlist.isLiked },
                    )
                }
            }
            PlaylistPickerSheet(
                playlists = choices,
                loading = false,
                song = target,
                startCreating = target == null,
                offline = true,
                onDismiss = dismiss,
                onAdd = { picked ->
                    target?.let { song ->
                        val result = OfflinePlaylists.add(picked.mapTo(HashSet()) { it.playlistId }, listOf(song))
                        showQueueNotice(
                            when {
                                result.added > 1 -> context.resources.getQuantityString(
                                    R.plurals.added_to_playlists_notice, result.added, result.added,
                                )
                                result.added == 1 -> context.getString(R.string.song_added_to_playlist)
                                result.alreadyThere > 0 -> context.getString(R.string.song_already_in_playlist)
                                else -> context.getString(R.string.failed)
                            },
                        )
                    }
                },
                onCreate = { title, _ ->
                    OfflinePlaylists.create(title, listOfNotNull(target))
                    if (target != null) showQueueNotice(context.getString(R.string.song_added_to_playlist))
                },
            )
        }


        // ---- Album / playlist actions ----
        // Opened by holding a card on any tab, or from the release page's own
        // overflow. What a track's long-press menu is to one song, this is to
        // the whole release — the queue rows above all.
        // One body for every frame alike — see songMenuBody.
        val browseMenuBody: @Composable (BrowseTarget) -> Unit = { target ->
            // Every row here closes the menu first: the tracks may still have to
            // be fetched, and leaving the sheet up over a request nothing on it
            // reports on reads as a tap that didn't land.
            val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
                {
                    browseActions = null
                    withBrowseSongs(target, action)
                }
            }
            // One of the offline player's own playlists, which are the user's
            // to rename and delete — all but Liked, which is the app's.
            val offlineEditable = OfflinePlaylists.get(target.browseId)?.takeUnless { it.isLiked }
            BrowseActionsMenu(
                // The live answer, not the one the target was built with.
                target = target.copy(
                    playlist = offlineEditable?.let {
                        UserPlaylist(playlistId = it.id, title = it.title, subtitle = "", thumbnailUrl = null)
                    },
                ),
                onRenameInSheet = {
                    browseRenameInSheet = true
                    browseMenuOrigin = null
                },
                startRenaming = browseRenameInSheet,
                onPlayNext = act(playSongsNext),
                onAddToQueue = act(addSongsToQueue),
                onPlay = act { songs -> play(songs, 0) }.takeIf { target.fromCard },
                onShuffle = act { songs ->
                    // As on a release page: shuffle goes on before the queue
                    // is built, so it is built shuffled rather than played
                    // out of order.
                    QueueShuffle.enableForNextQueue()
                    play(songs, songs.indices.random())
                }.takeIf { target.fromCard },
                onOpen = target.browseId
                    ?.takeIf { target.fromCard }
                    ?.let { id ->
                        {
                            browseActions = null
                            viewModel.openDetail(
                                browseId = id,
                                title = target.title,
                                subtitle = target.subtitle,
                                thumbnailUrl = target.thumbnailUrl,
                                type = target.type,
                            )
                        }
                    },
                onRename = offlineEditable?.let { p ->
                    { name: String ->
                        browseActions = null
                        OfflinePlaylists.rename(p.browseId, name)
                        viewModel.retitleDetail(p.browseId, name.trim())
                    }
                },
                onDelete = offlineEditable?.let { p ->
                    {
                        browseActions = null
                        // Its page has nothing left to show; take it away too.
                        if (detail?.browseId == p.browseId) viewModel.closeDetail()
                        OfflinePlaylists.delete(p.browseId)
                    }
                },
            )
        }
        // In a recompose scope of its own, as the track menu's is.
        Isolated {
            // Rename is a text field and a keyboard, which have no place on a menu
            // floating over the page: the menu hands it on, and the form comes up
            // here on a sheet of its own.
            browseActions?.takeIf { browseRenameInSheet }?.let { target ->
                ModalBottomSheet(
                    onDismissRequest = { browseActions = null },
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    browseMenuBody(target)
                }
            }
            HeldContextMenu(
                item = browseActions,
                held = browseMenuOrigin,
                hazeState = hazeState,
                onDismiss = { browseActions = null },
                // Tapping the lifted card opens it, as a tap on it in the list
                // would have. Not for a Local Music grouping, which has no page.
                onPreviewClick = { target ->
                    target.browseId?.let { id ->
                        browseActions = null
                        viewModel.openDetail(
                            browseId = id,
                            title = target.title,
                            subtitle = target.subtitle,
                            thumbnailUrl = target.thumbnailUrl,
                            type = target.type,
                        )
                    }
                },
            ) { target ->
                browseMenuBody(target)
            }
            // A tap on a ⋯ — the release page's own — hangs the menu off it.
            IosMenuPopover(
                item = browseActions?.takeIf { browseMenuOrigin == null && !browseRenameInSheet },
                anchor = browseMenuAnchor,
                onDismiss = { browseActions = null },
                backdrop = pageBackdrop,
                hazeState = hazeState,
            ) { target ->
                browseMenuBody(target)
            }
            LaunchedEffect(browseActions == null) {
                if (browseActions == null) {
                    browseMenuOrigin = null
                    browseMenuAnchor = null
                    browseRenameInSheet = false
                }
            }
        }


        // ---- Diwali greeting ----
        AnimatedVisibility(
            visible = showDiwali,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300)),
        ) {
            val dismissDiwali = {
                showDiwali = false
                AppSettings.setDiwaliGreetingShown()
            }
            BackHandler(onBack = dismissDiwali)
            DiwaliGreetingScreen(onDismiss = dismissDiwali)
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (songSortMenuOpen) {
            BackHandler { songSortMenuOpen = false }
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                onDismiss = { songSortMenuOpen = false },
            )
        }

        val menuFolder = detail?.browseId?.takeIf { it.isDeviceFolder() }
        if (folderMenuOpen && menuFolder != null) {
            BackHandler { folderMenuOpen = false }
            val viewType = folderViewTypeOf(menuFolder)
            FrostedFolderMenu(
                hazeState = hazeState,
                sort = folderSortOf(menuFolder),
                viewType = viewType,
                onSort = { option ->
                    AppSettings.setLocalMusicSort(option)
                    folderMenuOpen = false
                },
                onToggleViewType = {
                    val next = if (viewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                    AppSettings.setLocalMusicViewType(next)
                    folderMenuOpen = false
                },
                onDismiss = { folderMenuOpen = false },
            )
        }


        if (showAppLanguage) {
            BackHandler { showAppLanguage = false }
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
            )
        }

        if (showTranslationLanguage) {
            BackHandler { showTranslationLanguage = false }
            TranslationLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showTranslationLanguage = false },
            )
        }

        if (showLastfmLogin) {
            // API key/secret belong to the build. They are only editable when
            // this build has none (a local build without the secrets).
            val appCredentialsMissing = AppSettings.lastfmApiKey.value.isBlank() ||
                AppSettings.lastfmSecret.value.isBlank()
            var apiKeyInput by remember { mutableStateOf(AppSettings.lastfmApiKey.value) }
            var apiSecretInput by remember { mutableStateOf(AppSettings.lastfmSecret.value) }
            var usernameInput by remember { mutableStateOf("") }
            var passwordInput by remember { mutableStateOf("") }
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                showAppCredentials = appCredentialsMissing,
                apiKeyInput = apiKeyInput,
                onApiKeyInputChange = { apiKeyInput = it },
                apiSecretInput = apiSecretInput,
                onApiSecretInputChange = { apiSecretInput = it },
                usernameInput = usernameInput,
                onUsernameInputChange = { usernameInput = it },
                passwordInput = passwordInput,
                onPasswordInputChange = { passwordInput = it },
                error = lastfmError,
                loading = lastfmLoading,
                onSignIn = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            if (appCredentialsMissing) {
                                AppSettings.setLastfmApiKey(apiKeyInput.trim())
                                AppSettings.setLastfmSecret(apiSecretInput.trim())
                            }
                            LastFM.initialize(
                                apiKey = AppSettings.lastfmApiKey.value,
                                secret = AppSettings.lastfmSecret.value,
                            )
                            LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                .onSuccess { auth ->
                                    AppSettings.setLastfmSessionKey(auth.session.key)
                                    AppSettings.setLastfmUsername(auth.session.name)
                                    AppSettings.setLastfmEnabled(true)
                                    showLastfmLogin = false
                                }
                                .onFailure { e ->
                                    lastfmError = e.message ?: context.getString(R.string.login_failed)
                                }
                        } catch (e: Exception) {
                            lastfmError = e.message ?: context.getString(R.string.login_failed)
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
            )
        }

        confirmBitPerfect?.let { mode ->
            ConfirmationAlert(
                hazeState = hazeState,
                title = stringResource(R.string.bit_perfect_confirm_title),
                description = stringResource(R.string.bit_perfect_confirm_message),
                confirmLabel = stringResource(R.string.enable),
                onConfirm = {
                    AppSettings.setBitPerfectMode(mode)
                    confirmBitPerfect = null
                },
                onDismiss = { confirmBitPerfect = null },
            )
        }

        if (confirmFixedVolume) {
            ConfirmationAlert(
                hazeState = hazeState,
                title = stringResource(R.string.usb_allow_fixed_volume_confirm_title),
                description = stringResource(R.string.usb_allow_fixed_volume_confirm_message),
                confirmLabel = stringResource(R.string.enable),
                onConfirm = {
                    AppSettings.setUsbAllowFixedVolume(true)
                    confirmFixedVolume = false
                },
                onDismiss = { confirmFixedVolume = false },
            )
        }

        // Over the page and its frosted bars, under nothing but the alerts.
        DacVolumeHud(hazeState)

    }
}


/** UIKit's sheet curve: leaves at once, settles long — a sheet let go of, not pushed. */

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

@Composable
private fun LibrarySort.localizedLabel(): String = when (this) {
    LibrarySort.DEFAULT -> stringResource(R.string.sort_default)
    LibrarySort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LibrarySort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

/**
 * The track-list sort menu, styled after the account switcher: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onDismiss: () -> Unit,
) {
    FrostedMenuPanel(hazeState = hazeState, onDismiss = onDismiss) {
        // The same orderings as the folder's sort menu, in the same order —
        // the two menus differ only in what they order: the folder's tabs, or
        // this page's track list. "Default order" restores an album's own
        // track order (or the page's incoming order) after a sort.
        SongSort.entries
            .filter { it != SongSort.DATE_ADDED_ASC }
            .forEach { option ->
                FrostedMenuRow(
                    label = option.localizedLabel(),
                    checked = option == selected,
                    onClick = { onSelect(option) },
                )
            }
    }
}

/**
 * A device folder's menu, off the same button: list or grid for its tabs, then
 * its sort orders — the Files app's arrangement, view first and order under it.
 */
@Composable
private fun FrostedFolderMenu(
    hazeState: HazeState,
    sort: LocalMusicSort,
    viewType: LibraryViewType,
    onSort: (LocalMusicSort) -> Unit,
    onToggleViewType: () -> Unit,
    onDismiss: () -> Unit,
) {
    FrostedMenuPanel(hazeState = hazeState, onDismiss = onDismiss) {
        val grid = viewType == LibraryViewType.GRID
        FrostedMenuRow(
            label = stringResource(if (grid) R.string.switch_to_list_view else R.string.switch_to_grid_view),
            checked = false,
            onClick = onToggleViewType,
            icon = if (grid) JuniIcons.ListView else JuniIcons.GridView,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        LocalMusicSort.entries.forEach { option ->
            FrostedMenuRow(
                label = option.localizedLabel(),
                checked = option == sort,
                onClick = { onSort(option) },
            )
        }
    }
}

/** The scrim and frosted panel both top-bar menus are drawn on. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedMenuPanel(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

/** One option on a frosted menu: its label, and a tick or [icon] at the end. */
@Composable
private fun FrostedMenuRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (icon != null) {
            Spacer(Modifier.width(16.dp))
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
    SongSort.DATE_MODIFIED_DESC -> stringResource(R.string.sort_date_modified)
    SongSort.SIZE_DESC -> stringResource(R.string.sort_size_descending)
}

/**
 * Whether this page id is one of the device folders — `local:all` and the
 * rest, the pages with Songs / Artists / Albums tabs.
 *
 * Asked rather than `startsWith("local:")` because that prefix also covers an
 * album or artist grouped out of a folder: a plain track listing that wants
 * the same chrome every other release page gets. See [LocalGroups.PREFIX].
 */
private fun String?.isDeviceFolder(): Boolean =
    this != null && startsWith("local:") &&
        !startsWith(LocalGroups.PREFIX) &&
        !startsWith(OfflinePlaylists.PREFIX)


/**
 * How far short of the end a seek is allowed to land.
 *
 * Seeking to the final millisecond is indistinguishable from the track running
 * out, so it starts the next song — which is not what anyone dragging to the end
 * of the bar, or tapping the last line of a lyric, is asking for. A second back
 * from the end plays the outro instead.
 */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * How far a detail page scrolls before its title moves up into the bar.
 *
 * Roughly the height of the sleeve and the credit stacked above the Play pair,
 * so the two titles hand over as the header one leaves rather than sitting on
 * screen together. The bar cross-fades over 220ms, which absorbs the difference
 * between that estimate and a particular page's real header.
 */
private val DETAIL_TITLE_DROP = 320.dp

/**
 * How fast, a second, a mini-player pull has to be moving when the finger
 * lifts to decide open or closed on its own, wherever it got to — see
 * [MiniPlayerPull]. A flick is a whole gesture; only a slow pull is judged by
 * the distance it covered.
 */
private val PLAYER_PULL_FLING_VELOCITY = 400.dp

/** M3's own scrim strength, which the player's dim stands in for. */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * [content] in a recompose scope of its own, so state read inside it
 * recomposes it alone rather than everything it is written in.
 */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

/** Longer than the ⋯ menu's exit, so its panel keeps its frost on the way out. */
private const val SNAPSHOT_RELEASE_DELAY_MS = 400L

/**
 * The player sheet's shape: square, as [RectangleShape] was, but reaching a
 * whole sheet's height above the sheet's top edge.
 *
 * The sheet's Surface clips its content to this shape, and on the way down to
 * the mini player the artwork travels ahead of the sheet carrying it — up
 * above the sheet's top edge, where a plain rectangle cut it off mid-air,
 * leaving only its lower part to arrive in the bar. Nothing else is drawn
 * outside the sheet (its background is transparent and it casts no shadow),
 * so at rest this is the rectangle it replaces.
 */
private object PlayerSheetShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, -size.height, size.width, size.height))
}

private const val TAB_LIBRARY = 0
private const val TAB_SEARCH = 3
