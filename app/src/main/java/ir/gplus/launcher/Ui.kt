package ir.gplus.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import java.io.File
import android.graphics.Outline
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.ConnectivityManager
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt
import android.view.KeyEvent as AKey

// ───────────── تنظیمات ظاهری ─────────────
val Ink = Color(0xFF1F3A5F)
val LocalFont = compositionLocalOf<FontFamily> { FontFamily.Default }
const val LIVE_PREVIEW = false   // false = به‌جای پخش زنده، انیمیشن لوپ نشان بده
const val PREVIEW_VOLUME = 0f                  // صدای پیش‌نمایش داخل کادر (۰ = بی‌صدا، ۱ = صدا)

enum class Screen { Home, Apps }

val LocalU = compositionLocalOf { 1f }
val Int.u: Dp
    @Composable get() = (this * LocalU.current).dp
val Int.us: TextUnit
    @Composable get() = (this * LocalU.current).sp

// ───────────── اجزای کوچک ─────────────
@Composable
fun T(
    text: String, size: Int, color: Color = Ink, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = 1
) = Text(
    text, modifier = modifier, color = color, fontSize = size.us, fontWeight = weight,
    fontFamily = LocalFont.current, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis
)

@Composable fun VS(h: Int) = Spacer(Modifier.height(h.u))
@Composable fun HS(w: Int) = Spacer(Modifier.width(w.u))

@Composable
fun Ico(res: Int, size: Int, tint: Color, modifier: Modifier = Modifier) =
    Image(painterResource(res), null, modifier.size(size.u), colorFilter = ColorFilter.tint(tint))

@Composable
fun Modifier.glass(radius: Int, alpha: Float): Modifier {
    val s = RoundedCornerShape(radius.u)
    return this.clip(s).background(Color.White.copy(alpha)).border(1.u, Color.White.copy(0.55f), s)
}

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun FocusCard(modifier: Modifier, radius: Int = 18, onClick: () -> Unit, content: @Composable BoxScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "scale")
    val shape = RoundedCornerShape(radius.u)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Color.White.copy(if (focused) 0.6f else 0.28f))
            .border(if (focused) 3.u else 1.u, Color.White.copy(if (focused) 1f else 0.55f), shape)
            .onFocusChanged { focused = it.isFocused }
            .tap(onClick),
        content = { content(focused) }
    )
}

// ───────────── ریشه ─────────────
@Composable
fun LauncherApp(resumed: Boolean, homeSignal: Int, reloadKey: Int) {
    val appCtx = LocalContext.current
    val font = remember { loadFont(appCtx) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalFont provides font) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sw = constraints.maxWidth
            val sh = constraints.maxHeight
            CompositionLocalProvider(LocalU provides maxWidth.value / 1280f) {
                Launcher(sw, sh, resumed, homeSignal, reloadKey)
            }
        }
    }
}

@Composable
private fun Launcher(sw: Int, sh: Int, resumed: Boolean, homeSignal: Int, reloadKey: Int) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val uu = LocalU.current
    val prefs = remember { ctx.getSharedPreferences("gplus", Context.MODE_PRIVATE) }

    var screen by remember { mutableStateOf(Screen.Home) }
    var fullscreen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var tunerBlocked by remember { mutableStateOf(false) }
    var guessed by remember { mutableStateOf(false) }
    var pinnedList by remember {
        mutableStateOf<List<String>?>(prefs.getString("pinned", null)?.split(",")?.filter { it.isNotBlank() })
    }
    var status by remember { mutableStateOf("") }
    var ping by remember { mutableIntStateOf(0) }
    var overlayShown by remember { mutableStateOf(true) }
    var tileRect by remember { mutableStateOf(Rect.Zero) }
    var tileFocused by remember { mutableStateOf(false) }

    val inputs = remember { mutableStateListOf<TvInput>() }
    var inputId by remember { mutableStateOf<String?>(prefs.getString("input", null)) }
    var channels by remember { mutableStateOf(emptyList<TvChannel>()) }
    var channelsFor by remember { mutableStateOf<String?>(null) }
    var chIdx by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var appsKey by remember { mutableIntStateOf(0) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var now by remember { mutableStateOf(Date()) }
    var online by remember { mutableStateOf(true) }
    val tileFr = remember { FocusRequester() }

    val tvView = remember {
        TvView(ctx).apply {
            setZOrderMediaOverlay(true)
            isFocusable = false
            setCallback(object : TvView.TvInputCallback() {
                override fun onVideoAvailable(id: String) { status = "" }
                override fun onVideoUnavailable(id: String, reason: Int) {
                    status = when (reason) {
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING,
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> ""
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "سیگنال ضعیف است"
                        TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "فقط صدا پخش می‌شود"
                        else -> "سیگنال موجود نیست"
                    }
                }
                override fun onConnectionFailed(id: String) { status = "اتصال به این ورودی ناموفق بود" }
            })
        }
    }

    fun selectInput(id: String) {
        inputId = id
        prefs.edit().putString("input", id).apply()
        ping++
    }
    fun changeChannel(d: Int) {
        if (channels.size > 1) {
            chIdx = Math.floorMod(chIdx + d, channels.size)
            prefs.edit().putInt("ch_$inputId", chIdx).apply()
            ping++
        }
    }

    // ورودی‌ها
    LaunchedEffect(reloadKey, resumed) {
        if (!resumed) return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { TvData.inputs(ctx) }
        inputs.clear(); inputs.addAll(list)
        if (inputId == null || list.none { it.id == inputId }) inputId = list.firstOrNull()?.id
    }
    // کانال‌های ورودی (فقط برای آنتن/تیونر)
    LaunchedEffect(inputId, inputs.size, reloadKey) {
        val inp = inputs.firstOrNull { it.id == inputId } ?: return@LaunchedEffect
        if (inp.passthrough) {
            channels = emptyList(); chIdx = 0; guessed = false
        } else {
            val real = withContext(Dispatchers.IO) { TvData.channels(ctx, inp.id) }
            guessed = real.isEmpty()
            val list = if (real.isEmpty()) guessedChannels() else real
            channels = list
            chIdx = prefs.getInt("ch_${inp.id}", 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
        channelsFor = inp.id
    }
    // پخش
    val videoActive = LIVE_PREVIEW && resumed && (fullscreen || screen == Screen.Home)
    LaunchedEffect(videoActive, inputId, inputs.size, channels, channelsFor, chIdx) {
        val inp = inputs.firstOrNull { it.id == inputId }
        if (!videoActive || inp == null) { tvView.reset(); return@LaunchedEffect }
        val uri: Uri? = if (inp.passthrough) {
            TvContract.buildChannelUriForPassthroughInput(inp.id)
        } else if (channelsFor != inp.id) {
            return@LaunchedEffect
        } else {
            channels.getOrNull(chIdx)?.let { TvContract.buildChannelUri(it.id) }
        }
        if (uri == null) {
            tunerBlocked = true
            val hasPerm = ctx.checkSelfPermission("com.android.providers.tv.permission.READ_EPG_DATA") ==
                PackageManager.PERMISSION_GRANTED
            status = if (!hasPerm) "اجازه‌ی خواندن کانال‌ها داده نشده است"
            else "این تلویزیون لیست کانال‌های آنتن را به برنامه‌های دیگر نمی‌دهد"
            tvView.reset()
        } else {
            tunerBlocked = false
            status = ""
            tvView.tune(inp.id, uri)
        }
    }
    LaunchedEffect(fullscreen) { tvView.setStreamVolume(if (fullscreen) 1f else PREVIEW_VOLUME) }

    // برنامه‌ها
    LaunchedEffect(appsKey) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { appsKey++ }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        }
        ctx.registerReceiver(r, f)
        onDispose { ctx.unregisterReceiver(r) }
    }
    // ساعت، شبکه، آب‌وهوا
    LaunchedEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        while (true) {
            now = Date()
            online = cm.activeNetworkInfo?.isConnected == true
            delay(10_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            fetchWeather()?.let { weather = it }
            delay(if (weather == null) 60_000L else 30 * 60_000L)
        }
    }
    // نمایش موقت اطلاعات در تمام‌صفحه
    LaunchedEffect(fullscreen, ping) {
        overlayShown = true
        if (fullscreen) { delay(4000); overlayShown = false }
    }
    // دکمه Home
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) { fullscreen = false; showPicker = false; screen = Screen.Home }
    }
    LaunchedEffect(fullscreen, screen, showPicker) {
        if (!fullscreen && screen == Screen.Home && !showPicker) { delay(150); runCatching { tileFr.requestFocus() } }
    }
    BackHandler(enabled = showPicker || fullscreen || screen != Screen.Home) {
        when {
            showPicker -> showPicker = false
            fullscreen -> fullscreen = false
            else -> screen = Screen.Home
        }
    }

    val popular = remember(apps, pinnedList) {
        val byPkg = apps.associateBy { it.pkg }
        val saved = pinnedList
        if (saved != null) {
            saved.mapNotNull { byPkg[it] }.take(9)
        } else {
            val pinned = PINNED.mapNotNull { byPkg[it] }.distinctBy { it.pkg }
            (pinned + apps.filter { a -> pinned.none { it.pkg == a.pkg } }).take(9)
        }
    }
    val liveApp = remember(apps) { findLiveApp(apps) }
    fun togglePin(pkg: String) {
        val cur = (pinnedList ?: popular.map { it.pkg }).toMutableList()
        if (!cur.remove(pkg) && cur.size < 9) cur.add(pkg)
        pinnedList = cur
        prefs.edit().putString("pinned", cur.joinToString(",")).apply()
    }
    fun resetPins() {
        pinnedList = null
        prefs.edit().remove("pinned").apply()
    }
    fun openLive() {
        if (!LIVE_PREVIEW) {
            if (liveApp != null) launchApp(ctx, liveApp)
            else Toast.makeText(ctx, "برنامه‌ی تلویزیون پیدا نشد", Toast.LENGTH_SHORT).show()
        } else if (tunerBlocked && liveApp != null) launchApp(ctx, liveApp) else fullscreen = true
    }
    val bgId = remember { findRes(ctx, "drawable", listOf("bg", "background", "wallpaper")) }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (bgId == 0) add("پس‌زمینه (bg)")
            if (findRes(ctx, "drawable", WEATHER_NAMES) == 0) add("عکس هوا (weather_bg)")
            if (findRes(ctx, "font", DIPLOMAT_NAMES) == 0) add("فونت (diplomat)")
        }
        if (missing.isNotEmpty()) {
            delay(1500)
            Toast.makeText(ctx, "داخل برنامه پیدا نشد: " + missing.joinToString("، "), Toast.LENGTH_LONG).show()
        }
    }
    val logoId = remember { ctx.resources.getIdentifier("logo", "drawable", ctx.packageName) }

    Box(Modifier.fillMaxSize()) {
        Background(bgId)

        if (!fullscreen) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    now, online, weather, inputs, inputId, popular, logoId, tileFr, resumed,
                    onPickInput = { if (LIVE_PREVIEW) showPicker = true },
                    onTileBounds = { tileRect = it },
                    onTileFocus = { tileFocused = it },
                    onFullscreen = { openLive() },
                    onApps = { screen = Screen.Apps },
                    onSettings = { openSettings(ctx) },
                    onSearch = { runCatching { ctx.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    onLaunch = { launchApp(ctx, it) }
                )
                Screen.Apps -> AppsScreen(apps, popular.map { it.pkg }, { togglePin(it) }, { resetPins() }) { launchApp(ctx, it) }
            }
        }

        // لایه‌ی ویدیو (یک TvView برای هر دو حالت کادر و تمام‌صفحه)
        val showVideo = fullscreen || (LIVE_PREVIEW && screen == Screen.Home && tileRect.width > 0f)
        val r = if (fullscreen) Rect(0f, 0f, sw.toFloat(), sh.toFloat()) else tileRect
        val w = with(density) { r.width.toDp() }
        val h = with(density) { r.height.toDp() }
        val cornerPx = with(density) { (24 * uu).dp.toPx() }
        Box(
            Modifier
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(if (showVideo) w else 0.dp, if (showVideo) h else 0.dp)
        ) {
            AndroidView(
                factory = { tvView },
                modifier = Modifier.fillMaxSize(),
                update = { v ->
                    val rad = if (fullscreen) 0f else cornerPx
                    v.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, rad)
                        }
                    }
                    v.clipToOutline = true
                    v.invalidateOutline()
                    v.visibility = if (showVideo) View.VISIBLE else View.INVISIBLE
                }
            )
            if (showVideo) {
                val cur = inputs.firstOrNull { it.id == inputId }
                val shownStatus = when {
                    tunerBlocked && liveApp != null && !fullscreen ->
                        status + "\nبرای تماشا OK را بزنید (برنامه‌ی تلویزیون باز می‌شود)"
                    guessed && !fullscreen && status.isNotEmpty() ->
                        status + "\nحالت آزمایشی: OK را بزنید و با CH+/CH- یا بالا/پایین کانال را پیدا کنید"
                    else -> status
                }
                val chName = if (cur?.passthrough == false)
                    channels.getOrNull(chIdx)?.let { "${it.number}  ${it.name}" }.orEmpty() else ""
                Box(
                    Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.clip(RoundedCornerShape(24.u)))
                ) {
                    LiveOverlay(cur?.label ?: "—", chName, shownStatus, !fullscreen || overlayShown, fullscreen, tileFocused)
                }
                if (!fullscreen && tileFocused) {
                    Box(Modifier.fillMaxSize().border(4.u, Color.White, RoundedCornerShape(24.u)))
                }
            }
        }

        if (fullscreen) {
            val fr = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize()
                    .onKeyEvent { ev ->
                        val code = ev.nativeKeyEvent.keyCode
                        val mine = !showPicker && code in listOf(
                            AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MENU,
                            AKey.KEYCODE_CHANNEL_UP, AKey.KEYCODE_CHANNEL_DOWN,
                            AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_DPAD_DOWN, AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_DPAD_RIGHT
                        )
                        if (mine && ev.type == KeyEventType.KeyDown) {
                            when (code) {
                                AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MENU -> showPicker = true
                                AKey.KEYCODE_CHANNEL_UP, AKey.KEYCODE_DPAD_UP -> changeChannel(1)
                                AKey.KEYCODE_CHANNEL_DOWN, AKey.KEYCODE_DPAD_DOWN -> changeChannel(-1)
                                AKey.KEYCODE_DPAD_RIGHT -> changeChannel(10)
                                AKey.KEYCODE_DPAD_LEFT -> changeChannel(-10)
                                else -> ping++
                            }
                        }
                        mine
                    }
                    .focusRequester(fr)
                    .focusable()
            )
            LaunchedEffect(showPicker) {
                if (!showPicker) { delay(60); runCatching { fr.requestFocus() } }
            }
        }
        if (showPicker) {
            InputPicker(inputs, inputId, onOpenLive = liveApp?.let { app -> { showPicker = false; launchApp(ctx, app) } }) { selectInput(it); showPicker = false }
        }
    }
}

// ───────────── پس‌زمینه ─────────────
@Composable
private fun Background(bgId: Int) {
    val ctx = LocalContext.current
    val bmp = remember(bgId) { if (bgId != 0) loadScaled(ctx, bgId, 1920) else null }
    if (bmp != null) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF86B6E8), Color(0xFFCFE3F6), Color(0xFFA9C8E6), Color(0xFF7FA3C7))))
                .drawBehind {
                    val c = Offset(size.width * 0.7f, size.height * 0.2f)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(0.55f), Color.Transparent), center = c, radius = size.width * 0.45f),
                        radius = size.width * 0.45f, center = c
                    )
                }
        )
    }
}

// ───────────── صفحه‌ی اصلی ─────────────
@Composable
private fun HomeScreen(
    now: Date, online: Boolean, weather: Weather?, inputs: List<TvInput>, inputId: String?,
    popular: List<AppEntry>, logoId: Int, tileFr: FocusRequester, resumed: Boolean,
    onPickInput: () -> Unit, onTileBounds: (Rect) -> Unit, onTileFocus: (Boolean) -> Unit,
    onFullscreen: () -> Unit, onApps: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit,
    onLaunch: (AppEntry) -> Unit
) {
    val longPressed = remember { mutableStateOf(false) }
    val tileHasFocus = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.u, vertical = 20.u)) {
        TopBar(now, online, logoId, onSearch, onSettings)
        VS(14)
        Row(Modifier.fillMaxWidth().height(431.u)) {
            Sidebar(Modifier.width(180.u).fillMaxHeight(), onFullscreen, onApps, onSettings)
            HS(24)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onGloballyPositioned { onTileBounds(it.boundsInRoot()) }
                        .onPreviewKeyEvent { ev ->
                            val code = ev.nativeKeyEvent.keyCode
                            val center = code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER
                            when {
                                code == AKey.KEYCODE_MENU && ev.type == KeyEventType.KeyDown -> { onPickInput(); true }
                                center && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount > 0 -> {
                                    if (!longPressed.value) { longPressed.value = true; onPickInput() }
                                    true
                                }
                                center && ev.type == KeyEventType.KeyUp && longPressed.value -> { longPressed.value = false; true }
                                else -> false
                            }
                        }
                        .focusRequester(tileFr)
                        .onFocusChanged { onTileFocus(it.isFocused); tileHasFocus.value = it.isFocused }
                        .tap(onFullscreen)
                ) {
                    if (!LIVE_PREVIEW) LoopTile(resumed, tileHasFocus.value, Modifier.fillMaxSize())
                }
            }
            HS(24)
            WeatherCard(Modifier.width(280.u).fillMaxHeight(), weather)
        }
        VS(14)
        T("برنامه‌های محبوب", 16, weight = FontWeight.Medium)
        VS(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.u)) {
            popular.forEach { a -> AppTile(a, Modifier.width(96.u)) { onLaunch(a) } }
            FocusCard(Modifier.width(96.u).height(100.u), 18, onApps) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    T("＋", 32); T("بیشتر", 12)
                }
            }
        }
    }
}

@Composable
private fun TopBar(now: Date, online: Boolean, logoId: Int, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.u), verticalAlignment = Alignment.CenterVertically) {
        if (logoId != 0) {
            Image(painterResource(logoId), null, Modifier.height(46.u))
        } else {
            Box(
                Modifier.size(46.u).clip(RoundedCornerShape(12.u))
                    .background(Brush.linearGradient(listOf(Color(0xFF2F80ED), Color(0xFF56CCF2)))),
                contentAlignment = Alignment.Center
            ) { T("\u200EG+\u200E", 20, Color.White, FontWeight.Bold) }
            HS(10)
            T("G Plus", 24, Ink, FontWeight.Bold)
        }
        HS(32)
        FocusCard(Modifier.width(470.u).height(46.u), 23, onSearch) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.u), verticalAlignment = Alignment.CenterVertically) {
                Ico(R.drawable.ic_search, 20, Ink.copy(0.75f)); HS(10)
                T("جستجوی برنامه‌ها، فیلم‌ها و بازی‌ها…", 14, Ink.copy(0.75f))
            }
        }
        Spacer(Modifier.weight(1f))
        Ico(R.drawable.ic_wifi, 30, Ink, Modifier.alpha(if (online) 1f else 0.3f))
        HS(16)
        FocusCard(Modifier.size(44.u), 22, onSettings) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Ico(R.drawable.ic_settings, 26, Ink) }
        }
        HS(18)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(Fa.time(now), 26, Ink, FontWeight.Medium)
            T(Fa.date(now), 12)
        }
    }
}

@Composable
private fun Sidebar(modifier: Modifier, onLive: () -> Unit, onApps: () -> Unit, onSettings: () -> Unit) {
    Column(modifier.glass(24, 0.22f).padding(10.u), verticalArrangement = Arrangement.spacedBy(8.u)) {
        SideItem(R.drawable.ic_home, "خانه", true) {}
        SideItem(R.drawable.ic_tv, "تلویزیون زنده", false, onLive)
        SideItem(R.drawable.ic_apps, "برنامه‌ها", false, onApps)
        SideItem(R.drawable.ic_settings, "تنظیمات", false, onSettings)
    }
}

@Composable
private fun SideItem(icon: Int, text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.u)
    Row(
        Modifier.fillMaxWidth().height(60.u).clip(shape)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF3D8BFF), Color(0xFF7FBDFF))))
                else Modifier.background(Color.White.copy(if (focused) 0.5f else 0f))
            )
            .then(if (focused) Modifier.border(3.u, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .tap(onClick)
            .padding(horizontal = 14.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ico(icon, 26, if (selected) Color.White else Ink); HS(12)
        T(text, 16, if (selected) Color.White else Ink, if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun InputChips(inputs: List<TvInput>, selected: String?, onPick: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(34.u), horizontalArrangement = Arrangement.spacedBy(8.u)) {
        items(inputs, key = { it.id }) { inp ->
            val sel = inp.id == selected
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(17.u)
            Box(
                Modifier.height(34.u).clip(shape)
                    .background(if (sel) Color(0xFF3D8BFF) else Color.White.copy(0.4f))
                    .then(if (focused) Modifier.border(3.u, Color.White, shape) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .tap { onPick(inp.id) }
                    .padding(horizontal = 14.u),
                contentAlignment = Alignment.Center
            ) { T(inp.label, 13, if (sel) Color.White else Ink, FontWeight.Medium) }
        }
    }
}

@Composable
private fun WeatherCard(modifier: Modifier, w: Weather?) {
    val ctx = LocalContext.current
    val wid = remember { findRes(ctx, "drawable", WEATHER_NAMES) }
    val wbmp = remember(wid) { if (wid != 0) loadScaled(ctx, wid, 800) else null }
    Box(modifier.glass(24, 0.26f)) {
    if (wbmp != null) {
        Image(wbmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.White.copy(0.3f)))
    }
    Column(Modifier.fillMaxSize().padding(20.u)) {
        T("📍 ساری، ایران", 16)
        VS(14)
        if (w == null) {
            T("در حال دریافت وضعیت هوا…", 14)
        } else {
            val (icon, desc) = wmo(w.code)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(icon, 54); HS(14)
                Column {
                    T("${Fa.n(w.temp)}°", 44, Ink, FontWeight.Medium)
                    T(desc, 16)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.u).background(Color.White.copy(0.55f)))
            VS(10)
            Row(Modifier.fillMaxWidth()) {
                w.days.forEach { d ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        T(d.label, 11); VS(6)
                        T(wmo(d.code).first, 20); VS(6)
                        T("${Fa.n(d.temp)}°", 13)
                    }
                }
            }
            VS(6)
        }
    }
    }
}

@Composable
private fun AppTile(a: AppEntry, modifier: Modifier, badge: Boolean = false, onClick: () -> Unit) {
    FocusCard(modifier.height(100.u), 18, onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.u), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(a.icon, null, Modifier.size(52.u).clip(RoundedCornerShape(12.u)))
            VS(6)
            T(a.label, 11, align = TextAlign.Center)
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.u).size(22.u).clip(CircleShape).background(Color(0xFF3D8BFF)),
                contentAlignment = Alignment.Center
            ) { T("✓", 14, Color.White, FontWeight.Bold) }
        }
    }
}

@Composable
private fun AppsScreen(
    apps: List<AppEntry>, pinned: List<String>,
    onToggle: (String) -> Unit, onReset: () -> Unit, onLaunch: (AppEntry) -> Unit
) {
    var edit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.u, vertical = 28.u)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("همه‌ی برنامه‌ها", 26, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (edit) {
                FocusCard(Modifier.height(44.u).width(150.u), 22, onReset) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("برگشت به پیش‌فرض", 13) }
                }
                HS(12)
            }
            FocusCard(Modifier.height(44.u).width(if (edit) 120.u else 260.u), 22, { edit = !edit }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T(if (edit) "پایان" else "انتخاب برنامه‌های محبوب", 14, Ink, FontWeight.Medium)
                }
            }
        }
        if (edit) {
            VS(8)
            T("روی هر برنامه بزنید تا به صفحه‌ی اصلی اضافه یا حذف شود (حداکثر ۹ برنامه). ترتیب نمایش همان ترتیب انتخاب شماست.", 13, Ink.copy(0.85f), maxLines = 2)
        }
        VS(14)
        LazyVerticalGrid(
            GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(14.u),
            verticalArrangement = Arrangement.spacedBy(14.u),
            contentPadding = PaddingValues(8.u)
        ) {
            items(apps, key = { it.pkg }) { a ->
                AppTile(a, Modifier.fillMaxWidth(), badge = edit && a.pkg in pinned) {
                    if (edit) onToggle(a.pkg) else onLaunch(a)
                }
            }
        }
    }
}

// ───────────── روی ویدیو ─────────────
@Composable
private fun LiveOverlay(inputLabel: String, channel: String, status: String, chrome: Boolean, fullscreen: Boolean, hint: Boolean) {
    val dark = Color(0x99000000)
    Box(Modifier.fillMaxSize()) {
        if (chrome) {
            Row(
                Modifier.align(Alignment.TopStart).padding(14.u).clip(RoundedCornerShape(10.u)).background(dark)
                    .padding(horizontal = 10.u, vertical = 5.u),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.u).clip(CircleShape).background(Color(0xFFFF3B30)))
                HS(7); T("تلویزیون زنده", 13, Color.White)
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.u).clip(RoundedCornerShape(10.u)).background(dark)
                    .padding(horizontal = 10.u, vertical = 5.u)
            ) { T(inputLabel, 13, Color.White) }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 18.u, vertical = 12.u)
            ) {
                Column {
                    T(if (channel.isNotEmpty()) channel else inputLabel, if (fullscreen) 22 else 17, Color.White, FontWeight.Bold)
                    if (hint && !fullscreen) T("OK: تمام‌صفحه   •   OK را نگه دارید: انتخاب ورودی", 11, Color.White.copy(0.85f))
                    if (fullscreen) T("OK: انتخاب ورودی   •   بالا/پایین یا CH: کانال بعدی/قبلی   •   چپ/راست: ۱۰ کانال   •   Back: بازگشت", 12, Color.White.copy(0.8f))
                }
            }
        }
        if (status.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.Center).padding(24.u).clip(RoundedCornerShape(14.u)).background(Color(0xCC000000))
                    .padding(horizontal = 18.u, vertical = 12.u)
            ) { T(status, 15, Color.White, align = TextAlign.Center, maxLines = 3) }
        }
    }
}

@Composable
private fun InputPicker(inputs: List<TvInput>, selected: String?, onOpenLive: (() -> Unit)? = null, onPick: (String) -> Unit) {
    val fr = remember { FocusRequester() }
    val firstId = selected ?: inputs.firstOrNull()?.id
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(40.u).width(320.u)
                .clip(RoundedCornerShape(24.u)).background(Color(0xEE16324F)).padding(18.u),
            verticalArrangement = Arrangement.spacedBy(8.u)
        ) {
            T("انتخاب ورودی", 18, Color.White, FontWeight.Bold)
            inputs.forEach { inp ->
                val sel = inp.id == selected
                var focused by remember(inp.id) { mutableStateOf(false) }
                val shape = RoundedCornerShape(14.u)
                Box(
                    Modifier.fillMaxWidth().height(48.u).clip(shape)
                        .background(if (sel) Color(0xFF3D8BFF) else Color.White.copy(if (focused) 0.25f else 0.08f))
                        .then(if (focused) Modifier.border(3.u, Color.White, shape) else Modifier)
                        .then(if (inp.id == firstId) Modifier.focusRequester(fr) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .tap { onPick(inp.id) }
                        .padding(horizontal = 16.u),
                    contentAlignment = Alignment.CenterStart
                ) { T(inp.label, 16, Color.White, if (sel) FontWeight.Bold else FontWeight.Normal) }
            }
            if (onOpenLive != null) {
                var f2 by remember { mutableStateOf(false) }
                val shape2 = RoundedCornerShape(14.u)
                Box(
                    Modifier.fillMaxWidth().height(48.u).clip(shape2)
                        .background(Color.White.copy(if (f2) 0.25f else 0.08f))
                        .then(if (f2) Modifier.border(3.u, Color.White, shape2) else Modifier)
                        .onFocusChanged { f2 = it.isFocused }
                        .tap(onOpenLive)
                        .padding(horizontal = 16.u),
                    contentAlignment = Alignment.CenterStart
                ) { T("باز کردن برنامه‌ی Live TV دستگاه", 14, Color.White) }
            }
        }
    }
}


// ───────────── فونت و تصاویر ─────────────
/** اگر فایل‌های diplomat.ttf (و اختیاری diplomat_bold.ttf) در res/font باشند استفاده می‌شوند. */
private fun loadFont(ctx: Context): FontFamily {
    val r = ctx.resources
    val reg = findRes(ctx, "font", DIPLOMAT_NAMES)
    val bold = findRes(ctx, "font", listOf("diplomat_bold", "bold"))
    val list = mutableListOf<Font>()
    if (reg != 0) list += Font(reg, FontWeight.Normal)
    if (bold != 0) list += Font(bold, FontWeight.Bold)
    return if (list.isEmpty()) FontFamily.Default else FontFamily(list)
}

private val WEATHER_NAMES = listOf("weather_bg", "weather", "weather_card")
private val DIPLOMAT_NAMES = listOf("diplomat", "diplomat_regular", "regular")

private fun findRes(ctx: Context, type: String, names: List<String>): Int {
    for (n in names) {
        val id = ctx.resources.getIdentifier(n, type, ctx.packageName)
        if (id != 0) return id
    }
    return 0
}

/** عکس بزرگ (حتی 4K) را کوچک‌تر می‌خواند تا حافظه‌ی تلویزیون پر نشود. */
private fun loadScaled(ctx: Context, resId: Int, maxW: Int): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeResource(ctx.resources, resId, o) }
    var sample = 1
    while (o.outWidth > 0 && o.outWidth / (sample * 2) >= maxW) sample *= 2
    repeat(3) {
        val r = runCatching {
            BitmapFactory.decodeResource(ctx.resources, resId, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        if (r != null) return r.asImageBitmap()
        sample *= 2
    }
    return null
}

// ───────────── انیمیشن لوپ به‌جای پخش زنده ─────────────
/** اگر فایل res/raw/loop.mp4 باشد همان را بی‌صدا و بی‌نهایت پخش می‌کند، وگرنه صحنه‌ی پیش‌فرض را. */
@Composable
private fun LoopTile(playing: Boolean, focused: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val rawId = remember { findRes(ctx, "raw", listOf("loop")) }
    Box(modifier.clip(RoundedCornerShape(24.u))) {
        if (rawId != 0) {
            AndroidView(
                factory = { LoopView(it, rawId) },
                modifier = Modifier.fillMaxSize(),
                update = { v -> v.setPlaying(playing) }
            )
        } else {
            DefaultLoopScene(Modifier.fillMaxSize())
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(horizontal = 18.u, vertical = 12.u)
        ) {
            Column {
                T("تلویزیون زنده", 20, Color.White, FontWeight.Bold)
                if (focused) T("OK: باز کردن برنامه‌ی تلویزیون", 12, Color.White.copy(0.85f))
            }
        }
        if (focused) Box(Modifier.fillMaxSize().border(4.u, Color.White, RoundedCornerShape(24.u)))
    }
}

private class LoopView(ctx: Context, private val resId: Int) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var wantPlay = true

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        runCatching {
            val f = File(context.cacheDir, "loop.mp4")
            val upd = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!f.exists() || f.lastModified() < upd) {
                resources.openRawResource(resId).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            val sf = Surface(st)
            surface = sf
            mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setSurface(sf)
                isLooping = true
                setVolume(0f, 0f)
                runCatching { setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) }
                setOnPreparedListener { p ->
                    runCatching { p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) }
                    if (wantPlay) p.start()
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        runCatching { mp?.release() }
        mp = null
        runCatching { surface?.release() }
        surface = null
        return true
    }

    fun setPlaying(play: Boolean) {
        wantPlay = play
        runCatching { if (play) mp?.start() else mp?.pause() }
    }
}

@Composable
private fun DefaultLoopScene(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "scene").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "t"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF2C5282), Color(0xFF6FA0D8), Color(0xFFF4C9A3), Color(0xFFFFE6BE)),
                startY = 0f, endY = h * 0.8f
            )
        )
        val sun = Offset(w * 0.74f, h * 0.5f)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xE6FFF3CF), Color.Transparent), center = sun, radius = h * 0.55f),
            radius = h * 0.55f, center = sun
        )
        // ابرهای دور (هر ابر در هر دور عدد صحیحی از عرض را طی می‌کند، پس لوپ بی‌درز است)
        for (i in 0 until 5) {
            val k = if (i % 2 == 0) 1 else 2
            val cw = w * (0.22f + 0.04f * (i % 3))
            val x = (((i * 0.21f) + t * k) % 1f) * (w + cw * 2) - cw
            val y = h * (0.08f + 0.09f * i)
            val a = 0.35f + 0.08f * (i % 3)
            drawOval(Color.White.copy(a), Offset(x, y), Size(cw, cw * 0.28f))
            drawOval(Color.White.copy(a), Offset(x + cw * 0.2f, y - cw * 0.08f), Size(cw * 0.6f, cw * 0.28f))
        }
        val far = Path().apply {
            moveTo(0f, h); lineTo(0f, h * 0.7f)
            lineTo(w * 0.16f, h * 0.52f); lineTo(w * 0.28f, h * 0.64f); lineTo(w * 0.46f, h * 0.38f)
            lineTo(w * 0.62f, h * 0.62f); lineTo(w * 0.8f, h * 0.46f); lineTo(w, h * 0.66f)
            lineTo(w, h); close()
        }
        drawPath(far, Brush.verticalGradient(listOf(Color(0xFF6B86AA), Color(0xFF9DB2CE)), startY = h * 0.38f, endY = h))
        val near = Path().apply {
            moveTo(0f, h); lineTo(0f, h * 0.82f)
            cubicTo(w * 0.2f, h * 0.7f, w * 0.38f, h * 0.9f, w * 0.55f, h * 0.8f)
            cubicTo(w * 0.72f, h * 0.7f, w * 0.88f, h * 0.88f, w, h * 0.78f)
            lineTo(w, h); close()
        }
        drawPath(near, Color(0xFF35566E))
        // ابرهای نزدیک جلوی کوه‌ها
        for (i in 0 until 3) {
            val cw = w * 0.34f
            val x = (((i * 0.37f) + t * 3) % 1f) * (w + cw * 2) - cw
            val y = h * (0.55f + 0.1f * i)
            drawOval(Color.White.copy(0.28f), Offset(x, y), Size(cw, cw * 0.22f))
        }
    }
}
