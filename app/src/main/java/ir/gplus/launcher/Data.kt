package ir.gplus.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.icu.text.NumberFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** برنامه‌هایی که اگر نصب باشند اول نمایش داده می‌شوند. هر پکیجی خواستید اضافه کنید. */
val PINNED = listOf(
    "com.google.android.youtube.tv", "com.google.android.youtube",
    "com.netflix.ninja", "com.netflix.mediaclient",
    "com.disney.disneyplus",
    "com.amazon.amazonvideo.livingroom",
    "com.spotify.tv.android",
    "tv.twitch.android.app",
    "com.android.vending"
)

object Fa {
    private val loc = ULocale("fa_IR@calendar=persian")
    private val nf = NumberFormat.getInstance(ULocale("fa_IR"))
    private val tf = SimpleDateFormat("H:mm", loc)
    private val df = SimpleDateFormat("EEEE d MMMM", loc)
    private val wf = SimpleDateFormat("EEE", loc)
    fun n(v: Int): String = nf.format(v.toLong())
    fun time(d: Date): String = tf.format(d)
    fun date(d: Date): String = df.format(d)
    fun dow(d: Date): String = wf.format(d)
}

data class TvInput(val id: String, val label: String, val passthrough: Boolean, val type: Int)
data class TvChannel(val id: Long, val number: String, val name: String)

object TvData {
    fun inputs(ctx: Context): List<TvInput> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        return tim.tvInputList
            .filterNot { it.isHidden(ctx) }
            .map { info ->
                val raw = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx))?.toString().orEmpty()
                val label = when (info.type) {
                    TvInputInfo.TYPE_TUNER -> if (raw.isBlank()) "آنتن" else "آنتن · $raw"
                    TvInputInfo.TYPE_HDMI -> raw.ifBlank { "HDMI" }
                    else -> raw.ifBlank { info.id }
                }
                TvInput(info.id, label, info.isPassthroughInput, info.type)
            }
            .sortedWith(compareBy<TvInput>({ rank(it.type) }, { it.label }))
    }

    private fun rank(t: Int) = when (t) {
        TvInputInfo.TYPE_TUNER -> 0
        TvInputInfo.TYPE_HDMI -> 1
        else -> 2
    }

    fun channels(ctx: Context, inputId: String): List<TvChannel> = try {
        val out = mutableListOf<TvChannel>()
        ctx.contentResolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) out += TvChannel(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }
        out.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    } catch (e: Exception) {
        emptyList()
    }
}

data class AppEntry(val label: String, val pkg: String, val component: ComponentName, val icon: ImageBitmap)

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    fun q(cat: String) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
    // اول اپ‌های مخصوص تلویزیون، بعد بقیه (برای اپ‌های نصب‌شده‌ی دستی که دسته‌ی لین‌بک ندارند)
    val all = q(Intent.CATEGORY_LEANBACK_LAUNCHER) + q(Intent.CATEGORY_LAUNCHER)
    return all.asSequence()
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            AppEntry(
                ri.loadLabel(pm).toString(),
                ri.activityInfo.packageName,
                ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

fun launchApp(ctx: Context, a: AppEntry) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }
}

fun openSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

data class Day(val label: String, val temp: Int, val code: Int)
data class Weather(val temp: Int, val code: Int, val days: List<Day>)

/** آب‌وهوای ساری از Open-Meteo (رایگان، بدون کلید). برای شهر دیگر مختصات را عوض کنید. */
suspend fun fetchWeather(): Weather? = withContext(Dispatchers.IO) {
    try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=36.5633&longitude=53.0601" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,weather_code" +
                "&forecast_days=6&timezone=Asia%2FTehran"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val times = d.getJSONArray("time")
        val max = d.getJSONArray("temperature_2m_max")
        val codes = d.getJSONArray("weather_code")
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = (1 until times.length()).map { i ->
            Day(Fa.dow(parse.parse(times.getString(i))!!), max.getDouble(i).roundToInt(), codes.getInt(i))
        }
        Weather(cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"), days)
    } catch (e: Exception) {
        null
    }
}

fun wmo(code: Int): Pair<String, String> = when (code) {
    0 -> "☀️" to "آفتابی"
    1 -> "🌤️" to "کمی ابری"
    2 -> "⛅" to "نیمه‌ابری"
    3 -> "☁️" to "ابری"
    45, 48 -> "🌫️" to "مه‌آلود"
    in 51..57 -> "🌦️" to "نم‌نم باران"
    in 61..67, in 80..82 -> "🌧️" to "بارانی"
    in 71..77, 85, 86 -> "❄️" to "برفی"
    95, 96, 99 -> "⛈️" to "رعدوبرق"
    else -> "☁️" to "—"
}
