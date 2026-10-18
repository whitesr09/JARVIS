package com.nshd.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

data class WeatherInfo(val tempC: Int, val feelsC: Int, val humidity: Int, val hiC: Int, val loC: Int, val summary: String, val place: String, val at: Long)

/**
 * Real weather only: coarse location (permission required) + Open-Meteo (no key). One request per tap or
 * when the cached value is older than 20 minutes; never polled in the background.
 */
object Weather {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var cached: WeatherInfo? = null

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun describe(code: Int) = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"; else -> "Weather"
    }

    fun load(ctx: Context, force: Boolean, cb: (WeatherInfo?, String?) -> Unit) {
        val c = cached
        if (!force && c != null && System.currentTimeMillis() - c.at < 20 * 60_000L) { cb(c, null); return }
        if (!hasPermission(ctx)) { cb(null, "Location permission needed"); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun fetch(loc: Location) = io.execute {
            val res = try { request(ctx, loc) } catch (_: Exception) { null }
            main.post { if (res != null) { cached = res; cb(res, null) } else cb(null, "Couldn't load weather") }
        }
        try {
            val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }.maxByOrNull { it.time }
            if (last != null) { fetch(last); return }
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { cb(null, "Turn on location to see weather"); return }
            var done = false
            val l = object : LocationListener {
                override fun onLocationChanged(loc: Location) { if (!done) { done = true; lm.removeUpdates(this); fetch(loc) } }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            @Suppress("DEPRECATION") lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, l, Looper.getMainLooper())
            main.postDelayed({ if (!done) { done = true; lm.removeUpdates(l); cb(null, "Location unavailable right now") } }, 12000)
        } catch (_: SecurityException) { cb(null, "Location permission needed") }
    }

    private fun request(ctx: Context, loc: Location): WeatherInfo? {
        val u = "https://api.open-meteo.com/v1/forecast?latitude=${"%.2f".format(Locale.US, loc.latitude)}&longitude=${"%.2f".format(Locale.US, loc.longitude)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        try {
            if (conn.responseCode !in 200..299) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = j.getJSONObject("current"); val day = j.getJSONObject("daily")
            var place = ""
            try { if (Geocoder.isPresent()) @Suppress("DEPRECATION") { place = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: "" }.orEmpty() } } catch (_: Exception) {}
            return WeatherInfo(Math.round(cur.getDouble("temperature_2m")).toInt(), Math.round(cur.getDouble("apparent_temperature")).toInt(),
                cur.getInt("relative_humidity_2m"), Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)).toInt(), describe(cur.getInt("weather_code")), place, System.currentTimeMillis())
        } finally { conn.disconnect() }
    }
}
