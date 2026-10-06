package com.portal.pebblebridge.home

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Current conditions. [code] is a WMO weather code. */
data class WeatherNow(val temp: Int, val code: Int, val isDay: Boolean, val unit: String, val place: String)

data class GeoPlace(val lat: Double, val lon: Double, val label: String)

enum class WeatherKind { SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, THUNDER }

/** WMO code → icon family (same buckets as portalani). */
fun weatherKind(code: Int, isDay: Boolean): WeatherKind = when (code) {
  0 -> if (isDay) WeatherKind.SUN else WeatherKind.MOON
  1, 2 -> WeatherKind.PARTLY
  3 -> WeatherKind.CLOUD
  45, 48 -> WeatherKind.FOG
  in 51..57, in 61..67, in 80..82 -> WeatherKind.RAIN
  in 71..77, 85, 86 -> WeatherKind.SNOW
  95, 96, 99 -> WeatherKind.THUNDER
  else -> WeatherKind.CLOUD
}

/** Short label shown next to the temperature. */
fun weatherLabel(kind: WeatherKind): String = when (kind) {
  WeatherKind.SUN -> "SUNNY"
  WeatherKind.MOON -> "CLEAR"
  WeatherKind.PARTLY -> "PARTLY CLOUDY"
  WeatherKind.CLOUD -> "CLOUDY"
  WeatherKind.FOG -> "FOGGY"
  WeatherKind.RAIN -> "RAIN"
  WeatherKind.SNOW -> "SNOW"
  WeatherKind.THUNDER -> "STORM"
}

/**
 * Open-Meteo (free, keyless, no Google services) for weather and city search. When no city is
 * set, geojs.io gives an approximate location from the public IP.
 */
class WeatherClient(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build(),
) {

  @Throws(IOException::class)
  fun fetch(settings: HomeSettings): WeatherNow {
    val place = if (settings.weatherCity.isBlank()) locateByIp()
    else geocode(settings.weatherCity).firstOrNull() ?: throw IOException("city not found: ${settings.weatherCity}")
    return current(place, settings.fahrenheit)
  }

  @Throws(IOException::class)
  fun current(place: GeoPlace, fahrenheit: Boolean): WeatherNow {
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
      "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit"
    return parseCurrent(get(url), fahrenheit, place.label)
  }

  @Throws(IOException::class)
  fun geocode(query: String): List<GeoPlace> {
    val name = query.substringBefore(',').trim()
    if (name.isEmpty()) return emptyList()
    val q = URLEncoder.encode(name, "UTF-8")
    return parseGeocode(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=5&language=en&format=json"))
  }

  private var ipPlace: GeoPlace? = null

  /** Approximate location from the public IP, looked up once per app run. */
  @Throws(IOException::class)
  private fun locateByIp(): GeoPlace {
    ipPlace?.let { return it }
    return parseIpLocation(get("https://get.geojs.io/v1/ip/geo.json")).also { ipPlace = it }
  }

  @Throws(IOException::class)
  private fun get(url: String): String =
    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${url.substringBefore('?')}")
      body
    }

  companion object {
    fun parseCurrent(body: String, fahrenheit: Boolean, place: String): WeatherNow {
      val cur = JSONObject(body).getJSONObject("current")
      return WeatherNow(
        temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
        code = cur.getInt("weather_code"),
        isDay = cur.optInt("is_day", 1) == 1,
        unit = if (fahrenheit) "F" else "C",
        place = place,
      )
    }

    /** geojs.io returns coordinates as strings. */
    fun parseIpLocation(body: String): GeoPlace {
      val o = JSONObject(body)
      val lat = o.optString("latitude").toDoubleOrNull() ?: throw IOException("IP location failed")
      val lon = o.optString("longitude").toDoubleOrNull() ?: throw IOException("IP location failed")
      return GeoPlace(lat, lon, o.optString("city"))
    }

    fun parseGeocode(body: String): List<GeoPlace> {
      val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
      return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val label = listOfNotNull(
          o.optString("name").ifBlank { null },
          o.optString("country_code").ifBlank { null },
        ).joinToString(", ")
        GeoPlace(o.getDouble("latitude"), o.getDouble("longitude"), label)
      }
    }
  }
}
