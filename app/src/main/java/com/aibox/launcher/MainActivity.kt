package com.aibox.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ---------- Paleta (cámbiala aquí) ---------- */
private val BgTop = Color(0xFF060A11)
private val BgBottom = Color(0xFF0D1522)
private val Glass = Color(0x14FFFFFF)
private val IconBg = Color(0xFF1B2433)
private val TextPrimary = Color(0xFFF2F5F9)
private val TextSecondary = Color(0xFF8B97A8)

/** Colores de acento; el botón "Tema" los va rotando. */
private val Accents = listOf(
    Color(0xFF3DDC84), // verde
    Color(0xFF4DA3FF), // azul
    Color(0xFFFF9F43), // naranja
    Color(0xFFB388FF)  // violeta
)
private val LocalAccent = compositionLocalOf { Accents[0] }

data class AppEntry(val label: String, val pkg: String, val icon: Bitmap)

private class MediaInfo(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val art: Bitmap?,
    val controller: MediaController
)

/* Ciudad del clima (Open-Meteo, sin clave). Cambia estas 3 líneas para otra ciudad. */
private const val WEATHER_CITY = "Ambato"
private const val WEATHER_LAT = -1.2417
private const val WEATHER_LON = -78.6197

private class WeatherInfo(val temp: Int, val desc: String, val symbol: String)

private fun describeWeather(code: Int): Pair<String, String> = when (code) {
    0 -> "Despejado" to "☀"
    1, 2 -> "Parcialmente nublado" to "⛅"
    3 -> "Nublado" to "☁"
    45, 48 -> "Neblina" to "☁"
    in 51..57 -> "Llovizna" to "☂"
    in 61..67 -> "Lluvia" to "☂"
    in 71..77 -> "Nieve" to "❄"
    in 80..82 -> "Chubascos" to "☂"
    in 95..99 -> "Tormenta" to "⚡"
    else -> "—" to "☁"
}

private fun fetchWeather(): WeatherInfo? = try {
    val url = URL(
        "https://api.open-meteo.com/v1/forecast?latitude=$WEATHER_LAT&longitude=$WEATHER_LON" +
            "&current=temperature_2m,weather_code&timezone=auto"
    )
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    val cur = JSONObject(body).getJSONObject("current")
    val (desc, sym) = describeWeather(cur.getInt("weather_code"))
    WeatherInfo(Math.round(cur.getDouble("temperature_2m")).toInt(), desc, sym)
} catch (_: Exception) {
    null
}

private const val PREFS = "aibox_launcher"
private const val KEY_AUTO = "auto_pkg"      // null = sin definir, "" = ninguna
private const val KEY_FAVS = "favs"          // paquetes separados por coma
private const val KEY_THEME = "theme"
private const val AUTO_DELAY_SECONDS = 5

class MainActivity : ComponentActivity() {

    companion object {
        // true solo en la primera apertura tras arrancar el proceso (= encendido del equipo)
        private var autoLaunchHandled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val runAuto = !autoLaunchHandled
        autoLaunchHandled = true

        setContent { Launcher(runAutoLaunch = runAuto) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/* ---------- Datos y acciones ---------- */
private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                pkg = it.activityInfo.packageName,
                icon = it.loadIcon(pm).toBitmap(128, 128)
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun startSafe(ctx: Context, intent: Intent?) {
    try {
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    } catch (_: Exception) {
    }
}

private fun openApp(ctx: Context, pkg: String) =
    startSafe(ctx, ctx.packageManager.getLaunchIntentForPackage(pkg))

private fun openDialer(ctx: Context) = startSafe(ctx, Intent(Intent.ACTION_DIAL))

private fun openMapsFallback(ctx: Context) =
    startSafe(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS))

private fun openMusicFallback(ctx: Context) =
    startSafe(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))

/** Busca una app por parte del nombre o del paquete (sin distinguir mayúsculas). */
private fun findApp(apps: List<AppEntry>, vararg keys: String): AppEntry? =
    apps.firstOrNull { a ->
        keys.any { k -> a.label.lowercase().contains(k) || a.pkg.lowercase().contains(k) }
    }

/** Devuelve (música actual, tiene acceso a notificaciones). */
private fun readMedia(ctx: Context): Pair<MediaInfo?, Boolean> {
    return try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (c == null) {
            null to true
        } else {
            val md = c.metadata
            MediaInfo(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Sin título",
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                controller = c
            ) to true
        }
    } catch (_: SecurityException) {
        null to false
    } catch (_: Exception) {
        null to true
    }
}

private fun Modifier.card(): Modifier {
    val shape = RoundedCornerShape(26.dp)
    return this
        .clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF16202E), Color(0xFF0F1723))))
        .border(1.dp, Color(0x1FFFFFFF), shape)
}

/* ---------- Pantalla principal ---------- */
@Composable
private fun Launcher(runAutoLaunch: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var autoPkg by remember { mutableStateOf(prefs.getString(KEY_AUTO, null)?.ifBlank { null }) }
    var favs by remember {
        mutableStateOf(prefs.getString(KEY_FAVS, "")!!.split(",").filter { it.isNotBlank() })
    }
    var accentIdx by remember { mutableStateOf(prefs.getInt(KEY_THEME, 0).coerceIn(0, Accents.lastIndex)) }
    var countdown by remember { mutableStateOf(0) }
    var showDrawer by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var hasAccess by remember { mutableStateOf(true) }
    var weather by remember { mutableStateOf<WeatherInfo?>(null) }

    // Clima: se actualiza cada 15 minutos (si falla, conserva el último dato)
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { fetchWeather() }?.let { weather = it }
            delay(15 * 60 * 1000L)
        }
    }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.Default) { loadApps(ctx) }
        apps = list

        // Primera vez: CarPlay se abre solo al encender
        if (prefs.getString(KEY_AUTO, null) == null) {
            findApp(list, "carplay")?.let {
                autoPkg = it.pkg
                prefs.edit().putString(KEY_AUTO, it.pkg).apply()
            }
        }
        // Primera vez: favoritas por defecto
        if (!prefs.contains(KEY_FAVS)) {
            val defaults = listOf("maps", "waze", "youtube", "spotify", "whatsapp")
                .mapNotNull { findApp(list, it)?.pkg }
                .distinct()
                .take(5)
            favs = defaults
            prefs.edit().putString(KEY_FAVS, defaults.joinToString(",")).apply()
        }
        if (runAutoLaunch && autoPkg != null && list.any { it.pkg == autoPkg }) {
            countdown = AUTO_DELAY_SECONDS
        }
    }

    LaunchedEffect(countdown) {
        val pkg = autoPkg
        if (countdown > 0 && pkg != null) {
            delay(1000)
            if (countdown == 1) {
                countdown = 0
                openApp(ctx, pkg)
            } else countdown -= 1
        }
    }

    // Música: se consulta cada 1,5 s
    LaunchedEffect(Unit) {
        while (true) {
            val (m, ok) = withContext(Dispatchers.Default) { readMedia(ctx) }
            media = m
            hasAccess = ok
            delay(1500)
        }
    }

    BackHandler {
        if (menuFor != null) menuFor = null else showDrawer = false
    }

    val accent = Accents[accentIdx]
    val autoApp = apps.firstOrNull { it.pkg == autoPkg }
    val carplay = findApp(apps, "carplay")
    val androidAuto = findApp(apps, "android auto", "androidauto", "projection")
    val favApps = favs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.take(5)

    fun saveAuto(pkg: String?) {
        autoPkg = pkg
        prefs.edit().putString(KEY_AUTO, pkg ?: "").apply()
        countdown = 0
    }

    fun saveFavs(list: List<String>) {
        favs = list
        prefs.edit().putString(KEY_FAVS, list.joinToString(",")).apply()
    }

    val openNav = {
        countdown = 0
        findApp(apps, "waze", "maps", "navig")?.let { openApp(ctx, it.pkg) } ?: openMapsFallback(ctx)
    }
    val openMusic = {
        countdown = 0
        findApp(apps, "spotify", "music")?.let { openApp(ctx, it.pkg) } ?: openMusicFallback(ctx)
    }

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SideRail(
                    onNav = { openNav() },
                    onMedia = { openMusic() },
                    onPhone = { countdown = 0; openDialer(ctx) },
                    modifier = Modifier
                        .width(84.dp)
                        .fillMaxHeight()
                )

                ClockCard(
                    autoApp = autoApp,
                    countdown = countdown,
                    onCancel = { countdown = 0 },
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight()
                )

                Column(
                    Modifier
                        .weight(0.70f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        MediaCard(
                            media = media,
                            hasAccess = hasAccess,
                            onGrant = {
                                startSafe(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onOpenMusic = { openMusic() },
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight()
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            WeatherCard(weather, Modifier.weight(1f).fillMaxWidth())
                            DateCard(Modifier.weight(1f).fillMaxWidth())
                        }
                    }

                    FavoritesRow(
                        favApps = favApps,
                        autoPkg = autoPkg,
                        onOpen = { countdown = 0; openApp(ctx, it.pkg) },
                        onLong = { menuFor = it },
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxWidth()
                    )

                    Row(
                        Modifier
                            .weight(0.7f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        carplay?.let {
                            BigButton(
                                "CarPlay", "▶",
                                Brush.linearGradient(listOf(Color(0xFF22B455), Color(0xFF0C6A34))),
                                Modifier.weight(1f)
                            ) { countdown = 0; openApp(ctx, it.pkg) }
                        }
                        androidAuto?.let {
                            BigButton(
                                "Android Auto", "▲",
                                Brush.linearGradient(listOf(Color(0xFF3A7BF0), Color(0xFF1B3FA8))),
                                Modifier.weight(1f)
                            ) { countdown = 0; openApp(ctx, it.pkg) }
                        }
                        BigButton("Apps", "⁝⁝⁝", null, Modifier.weight(1f)) {
                            countdown = 0
                            showDrawer = true
                        }
                        BigButton("Tema", "◐", null, Modifier.weight(1f)) {
                            accentIdx = (accentIdx + 1) % Accents.size
                            prefs.edit().putInt(KEY_THEME, accentIdx).apply()
                        }
                    }
                }
            }

            if (showDrawer) {
                AppDrawer(
                    apps = apps,
                    autoPkg = autoPkg,
                    onOpen = { openApp(ctx, it.pkg) },
                    onLong = { menuFor = it },
                    onClose = { showDrawer = false }
                )
            }

            menuFor?.let { app ->
                OptionsMenu(
                    app = app,
                    isAuto = app.pkg == autoPkg,
                    isFav = app.pkg in favs,
                    onToggleAuto = { saveAuto(if (autoPkg == app.pkg) null else app.pkg); menuFor = null },
                    onToggleFav = {
                        saveFavs(if (app.pkg in favs) favs - app.pkg else favs + app.pkg)
                        menuFor = null
                    },
                    onClose = { menuFor = null }
                )
            }
        }
    }
}

/* ---------- Componentes ---------- */
@Composable
private fun SideRail(onNav: () -> Unit, onMedia: () -> Unit, onPhone: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    Column(
        modifier
            .card()
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        RailItem("⌂", "Inicio", selected = true, onClick = {})
        RailItem("➤", "Mapas", false, onNav)
        RailItem("♫", "Música", false, onMedia)
        RailItem("✆", "Teléfono", false, onPhone)
    }
}

@Composable
private fun RailItem(symbol: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(symbol, color = if (selected) accent else TextPrimary, fontSize = 24.sp)
        Text(label, color = if (selected) accent else TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun ClockCard(autoApp: AppEntry?, countdown: Int, onCancel: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) {
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now)
            .replaceFirstChar { it.uppercase() }
    }
    val seconds = remember(now) { Calendar.getInstance().apply { time = now }.get(Calendar.SECOND) }

    Column(
        modifier
            .card()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Glass, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arc, style = Stroke(stroke)
                    )
                    rotate(-90f) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.25f), accent)),
                            startAngle = 0f,
                            sweepAngle = 360f * (seconds + 1) / 60f,
                            useCenter = false,
                            topLeft = topLeft, size = arc,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(time, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Light)
                    Text(date, color = TextSecondary, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (countdown > 0 && autoApp != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Abriendo ${autoApp.label} en ${countdown}s", color = TextPrimary, fontSize = 13.sp)
                Text(
                    "Cancelar",
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancel)
                        .padding(6.dp)
                )
            }
        } else {
            Text(
                if (autoApp != null) "Al encender: ${autoApp.label}" else "Sin app de inicio",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MediaCard(
    media: MediaInfo?,
    hasAccess: Boolean,
    onGrant: () -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier
) {
    val accent = LocalAccent.current
    Column(
        modifier
            .card()
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Glass),
                contentAlignment = Alignment.Center
            ) {
                if (media?.art != null) {
                    Image(media.art.asImageBitmap(), "Carátula", Modifier.fillMaxSize())
                } else {
                    Text("♫", color = accent, fontSize = 28.sp)
                }
            }
            Column {
                when {
                    !hasAccess -> {
                        Text("Controla tu música desde aquí", color = TextPrimary, fontSize = 16.sp)
                        LinkText("Dar acceso a notificaciones", onGrant)
                    }
                    media == null -> {
                        Text("Sin música activa", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        LinkText("Abrir app de música", onOpenMusic)
                    }
                    else -> {
                        Text(
                            media.title, color = TextPrimary, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(media.artist, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tc = media?.controller?.transportControls
            Text(
                "◀◀", color = TextPrimary, fontSize = 20.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { tc?.skipToPrevious() }
                    .padding(16.dp)
            )
            Box(
                Modifier
                    .padding(horizontal = 14.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable {
                        if (media == null) onOpenMusic()
                        else if (media.playing) tc?.pause() else tc?.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (media?.playing == true) "❚❚" else "▶", color = Color(0xFF06101A), fontSize = 20.sp)
            }
            Text(
                "▶▶", color = TextPrimary, fontSize = 20.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { tc?.skipToNext() }
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = LocalAccent.current,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun DateCard(modifier: Modifier) {
    val accent = LocalAccent.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(30_000)
        }
    }
    val weekday = remember(now) { SimpleDateFormat("EEEE", Locale.getDefault()).format(now).uppercase() }
    val day = remember(now) { SimpleDateFormat("d", Locale.getDefault()).format(now) }
    val month = remember(now) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now).replaceFirstChar { it.uppercase() }
    }
    Row(
        modifier
            .card()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(day, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Column {
            Text(weekday, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text(month, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherInfo?, modifier: Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier
            .card()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (weather == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(WEATHER_CITY, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Sin datos del clima", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            Text(weather.symbol, color = accent, fontSize = 32.sp)
            Column {
                Text("${weather.temp}°C", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text(
                    "$WEATHER_CITY · ${weather.desc}", color = TextSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesRow(
    favApps: List<AppEntry>,
    autoPkg: String?,
    onOpen: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
    modifier: Modifier
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .card()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (favApps.isEmpty()) {
            Text(
                "Abre «Apps», mantén pulsada una app y elige «Agregar a favoritas»",
                color = TextSecondary, fontSize = 14.sp
            )
        }
        favApps.forEach { app ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onLong(app) })
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(IconBg)
                        .border(2.dp, if (app.pkg == autoPkg) Color.White else accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(app.icon.asImageBitmap(), app.label, Modifier.size(44.dp))
                }
                Text(
                    app.label, color = TextPrimary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 84.dp)
                )
            }
        }
    }
}

@Composable
private fun BigButton(
    label: String,
    symbol: String,
    brush: Brush?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(brush ?: Brush.linearGradient(listOf(Color(0xFF16202E), Color(0xFF0F1723))))
            .border(1.dp, if (brush == null) accent.copy(alpha = 0.6f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(symbol, color = if (brush == null) accent else Color.White, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    autoPkg: String?,
    onOpen: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
    onClose: () -> Unit
) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2060A11))
            .clickable(enabled = false) {}
    ) {
        Column(Modifier.padding(horizontal = 36.dp, vertical = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Todas las apps", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "✕  Cerrar", color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onClose)
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(apps, key = { it.pkg }) { app ->
                    AppTile(app, app.pkg == autoPkg, { onOpen(app) }, { onLong(app) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, isAuto: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(IconBg),
                contentAlignment = Alignment.Center
            ) {
                Image(app.icon.asImageBitmap(), app.label, Modifier.size(50.dp))
            }
            if (isAuto) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
        Text(
            app.label, color = TextPrimary, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OptionsMenu(
    app: AppEntry,
    isAuto: Boolean,
    isFav: Boolean,
    onToggleAuto: () -> Unit,
    onToggleFav: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF151D2B))
                .clickable(enabled = false) {}
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(app.icon.asImageBitmap(), app.label, Modifier.size(44.dp))
                Text(app.label, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            MenuButton(if (isAuto) "Quitar apertura al encender" else "Abrir al encender", onToggleAuto)
            MenuButton(if (isFav) "Quitar de favoritas" else "Agregar a favoritas", onToggleFav)
            MenuButton("Cerrar", onClose, subtle = true)
        }
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, subtle: Boolean = false) {
    Text(
        text,
        color = if (subtle) TextSecondary else TextPrimary,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (subtle) Color.Transparent else Glass)
            .clickable(onClick = onClick)
            .padding(16.dp)
    )
}
