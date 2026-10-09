package com.mali.nbeta.data.glance

import com.mali.nbeta.system.DiagLog
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.compose.runtime.Immutable
import com.mali.nbeta.R
import com.mali.nbeta.data.JsonStore
import com.mali.nbeta.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

@Immutable
@Serializable
data class Weather(
    val tempC: Double,
    val code: Int,
    val isDay: Boolean,
    val highC: Double,
    val lowC: Double,
    val place: String? = null,
    val fetched: Long,
)

@Immutable
data class CalEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
)

object WeatherCodes {
    /** WMO weather interpretation codes, as used by Open-Meteo: an emoji and a description string resource (null when unknown). */
    fun describe(code: Int, day: Boolean): Pair<String, Int?> = when (code) {
        0 -> (if (day) "☀️" else "🌙") to R.string.weather_clear
        1 -> (if (day) "🌤️" else "🌙") to R.string.weather_mostly_clear
        2 -> "⛅" to R.string.weather_partly_cloudy
        3 -> "☁️" to R.string.weather_cloudy
        45, 48 -> "🌫️" to R.string.weather_fog
        51, 53, 55, 56, 57 -> "🌦️" to R.string.weather_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️" to R.string.weather_rain
        71, 73, 75, 77, 85, 86 -> "🌨️" to R.string.weather_snow
        95, 96, 99 -> "⛈️" to R.string.weather_thunderstorm
        else -> "🌡️" to null
    }
}

class GlanceRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    httpProvider: () -> OkHttpClient,
) {
    // Built on first network use (always on an IO thread), never on the main thread at startup.
    private val http by lazy(httpProvider)
    private val weatherStore = JsonStore(File(context.filesDir, "weather.json"), Weather.serializer().nullable, { null }, scope)
    val weather: StateFlow<Weather?> = weatherStore.flow

    private val _events = MutableStateFlow<List<CalEvent>>(emptyList())
    val events: StateFlow<List<CalEvent>> = _events

    private val _nextAlarm = MutableStateFlow<Long?>(null)
    val nextAlarm: StateFlow<Long?> = _nextAlarm

    private val weatherLock = Mutex()

    /** Cheap; call on every resume. Network only when the weather is older than 30 minutes. */
    fun refresh(forceWeather: Boolean = false) {
        val s = settings.value
        _nextAlarm.value = if (s.glanceAlarm) context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime else null
        scope.launch(Dispatchers.IO) {
            _events.value = if (s.glanceCalendar) upcomingEvents() else emptyList()
        }
        val w = weather.value
        if (s.glanceWeather && (forceWeather || w == null || System.currentTimeMillis() - w.fetched > 30 * 60_000)) {
            scope.launch(Dispatchers.IO) { runCatching { fetchWeather() }.onFailure { DiagLog.w(TAG, "Weather failed: ${it.message}") } }
        }
    }

    private suspend fun fetchWeather() = weatherLock.withLock {
        val s = settings.value
        val (lat, lon, place) = if (s.weatherLat != null && s.weatherLon != null) {
            Triple(s.weatherLat, s.weatherLon, s.weatherCity)
        } else {
            val loc = lastLocation() ?: return@withLock
            Triple(loc.latitude, loc.longitude, placeName(loc))
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        val root = Json.parseToJsonElement(body).jsonObject
        val cur = root["current"]!!.jsonObject
        val daily = root["daily"]!!.jsonObject
        weatherStore.replace(
            Weather(
                tempC = cur["temperature_2m"]!!.jsonPrimitive.double,
                code = cur["weather_code"]!!.jsonPrimitive.int,
                isDay = cur["is_day"]!!.jsonPrimitive.int == 1,
                highC = daily["temperature_2m_max"]!!.jsonArray[0].jsonPrimitive.double,
                lowC = daily["temperature_2m_min"]!!.jsonArray[0].jsonPrimitive.double,
                place = place,
                fetched = System.currentTimeMillis(),
            ),
        )
    }

    /** City name to coordinates via Open-Meteo's free geocoder; used by the weather setting. */
    suspend fun geocode(city: String): Triple<String, Double, Double>? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(city, "UTF-8")
        runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
            val r = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
            Triple(r["name"]!!.jsonPrimitive.content, r["latitude"]!!.jsonPrimitive.double, r["longitude"]!!.jsonPrimitive.double)
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun placeName(loc: Location): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    } catch (_: Exception) {
        null
    }

    fun hasCalendar() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun upcomingEvents(): List<CalEvent> {
        if (!hasCalendar()) return emptyList()
        val now = System.currentTimeMillis()
        val end = now + 36 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 24 * 3600_000L)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = ArrayList<CalEvent>()
        val tz = java.util.TimeZone.getDefault()
        try {
            context.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = c.getInt(4) == 1
                    // All-day instances are stored in UTC midnights; shift into local time.
                    var b = c.getLong(2)
                    var e = c.getLong(3)
                    if (allDay) {
                        b -= tz.getOffset(b)
                        e -= tz.getOffset(e)
                    }
                    if (e <= now) continue
                    out += CalEvent(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.glance_no_title), b, e, allDay, c.getInt(5), c.getString(6)?.takeIf { it.isNotBlank() })
                }
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "Calendar query failed", e)
        }
        return out.sortedWith(compareBy({ it.allDay }, { it.begin })).take(4)
    }

    companion object {
        private const val TAG = "Glance"
    }
}
