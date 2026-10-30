package ir.gplus.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Outline
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.ConnectivityManager
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
val AppFont: FontFamily = FontFamily.Default   // فونت وزیرمتن را در res/font بگذارید و اینجا عوض کنید
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
    fontFamily = AppFont, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis
)

@Composable fun VS(h: Int) = Spacer(Modifier.height(h.u))
@Composable fun HS(w: Int) = Spacer(Modifier.width(w.u))

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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
            chIdx = (chIdx + d + channels.size) % channels.size
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
            channels = emptyList(); chIdx = 0
        } else {
            val list = withContext(Dispatchers.IO) { TvData.channels(ctx, inp.id) }
            channels = list
            chIdx = prefs.getInt("ch_${inp.id}", 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
        channelsFor = inp.id
    }
    // پخش
    val videoActive = resumed && (fullscreen || screen == Screen.Home)
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
            status = "کانالی پیدا نشد (اسکن کانال یا اجازه‌ی خواندن کانال‌ها لازم است)"
            tvView.reset()
        } else {
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
    LaunchedEffect(fullscreen, screen) {
        if (!fullscreen && screen == Screen.Home) { delay(150); runCatching { tileFr.requestFocus() } }
    }
    BackHandler(enabled = showPicker || fullscreen || screen != Screen.Home) {
        when {
            showPicker -> showPicker = false
            fullscreen -> fullscreen = false
            else -> screen = Screen.Home
        }
    }

    val popular = remember(apps) {
        val byPkg = apps.associateBy { it.pkg }
        val pinned = PINNED.mapNotNull { byPkg[it] }.distinctBy { it.pkg }
        (pinned + apps.filter { a -> pinned.none { it.pkg == a.pkg } }).take(7)
    }
    val bgId = remember { ctx.resources.getIdentifier("bg", "drawable", ctx.packageName) }
    val logoId = remember { ctx.resources.getIdentifier("logo", "drawable", ctx.packageName) }

    Box(Modifier.fillMaxSize()) {
        Background(bgId)

        if (!fullscreen) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    now, online, weather, inputs, inputId, popular, logoId, tileFr,
                    onSelectInput = { selectInput(it) },
                    onTileBounds = { tileRect = it },
                    onTileFocus = { tileFocused = it },
                    onFullscreen = { fullscreen = true },
                    onApps = { screen = Screen.Apps },
                    onSettings = { openSettings(ctx) },
                    onSearch = { runCatching { ctx.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    onLaunch = { launchApp(ctx, it) }
                )
                Screen.Apps -> AppsScreen(apps) { launchApp(ctx, it) }
            }
        }

        // لایه‌ی ویدیو (یک TvView برای هر دو حالت کادر و تمام‌صفحه)
        val showVideo = fullscreen || (screen == Screen.Home && tileRect.width > 0f)
        val r = if (fullscreen) Rect(0f, 0f, sw.toFloat(), sh.toFloat()) else tileRect
        val w = with(density) { r.width.toDp() }
        val h = with(density) { r.height.toDp() }
        val cornerPx = with(density) { (24 * uu).dp.toPx() }
        Box(
            Modifier
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
                val chName = if (cur?.passthrough == false)
                    channels.getOrNull(chIdx)?.let { "${it.number}  ${it.name}" }.orEmpty() else ""
                Box(
                    Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.clip(RoundedCornerShape(24.u)))
                ) {
                    LiveOverlay(cur?.label ?: "—", chName, status, !fullscreen || overlayShown, fullscreen)
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
            if (showPicker) {
                InputPicker(inputs, inputId) { selectInput(it); showPicker = false }
            }
        }
    }
}

// ───────────── پس‌زمینه ─────────────
@Composable
private fun Background(bgId: Int) {
    if (bgId != 0) {
        Image(painterResource(bgId), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
    popular: List<AppEntry>, logoId: Int, tileFr: FocusRequester,
    onSelectInput: (String) -> Unit, onTileBounds: (Rect) -> Unit, onTileFocus: (Boolean) -> Unit,
    onFullscreen: () -> Unit, onApps: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit,
    onLaunch: (AppEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.u, vertical = 20.u)) {
        TopBar(now, online, logoId, onSearch, onSettings)
        VS(14)
        Row(Modifier.fillMaxWidth().height(431.u)) {
            Sidebar(Modifier.width(180.u).fillMaxHeight(), onFullscreen, onApps, onSettings)
            HS(24)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                InputChips(inputs, inputId, onSelectInput)
                VS(8)
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onGloballyPositioned { onTileBounds(it.boundsInRoot()) }
                        .focusRequester(tileFr)
                        .onFocusChanged { onTileFocus(it.isFocused) }
                        .tap(onFullscreen)
                )
            }
            HS(24)
            WeatherCard(Modifier.width(280.u).fillMaxHeight(), weather)
        }
        VS(14)
        T("برنامه‌های محبوب", 16, weight = FontWeight.Medium)
        VS(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.u)) {
            popular.forEach { a -> AppTile(a, Modifier.weight(1f)) { onLaunch(a) } }
            repeat(7 - popular.size) { Spacer(Modifier.weight(1f)) }
            FocusCard(Modifier.weight(1f).height(100.u), 18, onApps) {
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
            ) { T("G+", 20, Color.White, FontWeight.Bold) }
            HS(10)
            T("G Plus", 24, Ink, FontWeight.Bold)
        }
        HS(32)
        FocusCard(Modifier.width(470.u).height(46.u), 23, onSearch) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.u), verticalAlignment = Alignment.CenterVertically) {
                T("🔍", 18); HS(10)
                T("جستجوی برنامه‌ها، فیلم‌ها و بازی‌ها…", 14, Ink.copy(0.75f))
            }
        }
        Spacer(Modifier.weight(1f))
        T("📶", 22, modifier = Modifier.alpha(if (online) 1f else 0.3f))
        HS(16)
        FocusCard(Modifier.size(44.u), 22, onSettings) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("⚙️", 20) }
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
        SideItem("🏠", "خانه", true) {}
        SideItem("📺", "تلویزیون زنده", false, onLive)
        SideItem("🧩", "برنامه‌ها", false, onApps)
        SideItem("⚙️", "تنظیمات", false, onSettings)
    }
}

@Composable
private fun SideItem(icon: String, text: String, selected: Boolean, onClick: () -> Unit) {
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
        T(icon, 22); HS(12)
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
    Column(modifier.glass(24, 0.26f).padding(20.u)) {
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

@Composable
private fun AppTile(a: AppEntry, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(modifier.height(100.u), 18, onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.u), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(a.icon, null, Modifier.size(46.u).clip(RoundedCornerShape(10.u)))
            VS(6)
            T(a.label, 12, align = TextAlign.Center)
        }
    }
}

@Composable
private fun AppsScreen(apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.u, vertical = 28.u)) {
        T("همه‌ی برنامه‌ها", 26, weight = FontWeight.Bold)
        VS(18)
        LazyVerticalGrid(
            GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(14.u),
            verticalArrangement = Arrangement.spacedBy(14.u),
            contentPadding = PaddingValues(8.u)
        ) {
            items(apps, key = { it.pkg }) { a -> AppTile(a, Modifier.fillMaxWidth()) { onLaunch(a) } }
        }
    }
}

// ───────────── روی ویدیو ─────────────
@Composable
private fun LiveOverlay(inputLabel: String, channel: String, status: String, chrome: Boolean, fullscreen: Boolean) {
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
                    if (fullscreen) T("OK: انتخاب ورودی   •   بالا/پایین یا CH: تغییر کانال   •   Back: بازگشت", 12, Color.White.copy(0.8f))
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
private fun InputPicker(inputs: List<TvInput>, selected: String?, onPick: (String) -> Unit) {
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
        }
    }
}
