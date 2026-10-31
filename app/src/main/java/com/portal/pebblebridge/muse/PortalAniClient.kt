package com.portal.pebblebridge.muse

import android.content.Context
import android.net.Uri
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges Portal Muse Bridge (`com.portal.pebblebridge`) to PortalAni (`com.portal.portalani`) on
 * the same Meta Portal device.
 *
 * Primary transport: Android `ContentProvider.call` (`content://com.portal.portalani.bridge`),
 * which wakes PortalAni on demand even when PortalAni is in the background.
 * Fallback transport: PortalAni's local HTTP server (`http://127.0.0.1:8788`), or an injected test
 * handler for JVM unit tests.
 */
object PortalAniClient {
  const val PROVIDER_AUTHORITY = "com.portal.portalani.bridge"
  private val PROVIDER_URI: Uri by lazy { Uri.parse("content://$PROVIDER_AUTHORITY") }
  private const val LOCAL_HTTP_BASE = "http://127.0.0.1:8788"

  @Volatile private var appContext: Context? = null

  /** Optional override for JVM unit tests where Android ContentResolver is unavailable. */
  @Volatile var testHandler: ((String, JSONObject) -> JSONObject)? = null

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  fun call(action: String, params: JSONObject = JSONObject()): JSONObject {
    testHandler?.let { handler ->
      return runCatching { handler(action, params) }.getOrElse { e ->
        errorJson("PortalAni test handler error: ${e.message}")
      }
    }

    // 1. Try Android ContentProvider IPC (works even if PortalAni UI is closed)
    val ctx = appContext
    if (ctx != null) {
      val bundleResult =
          runCatching {
            ctx.contentResolver.call(PROVIDER_URI, action, params.toString(), null)
          }.getOrNull()
      val raw = bundleResult?.getString("result")
      if (!raw.isNullOrBlank()) {
        runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
      }
    }

    // 2. Fallback to PortalAni's local HTTP server on 127.0.0.1:8788
    return callLocalHttp(action, params)
  }

  private fun callLocalHttp(action: String, params: JSONObject): JSONObject =
      try {
        val (method, path) =
            when (action) {
              "status", "health", "anilist_status" -> "GET" to "/anilist/status"
              "lists", "get_lists", "anilist_get_lists" -> "GET" to "/anilist/lists${toQueryString(params)}"
              "airing", "calendar", "get_airing", "anilist_get_airing" -> "GET" to "/anilist/airing${toQueryString(params)}"
              "search", "anilist_search" -> "GET" to "/anilist/search${toQueryString(params)}"
              "media", "get_media", "anilist_get_media" -> "GET" to "/anilist/media${toQueryString(params)}"
              "update_list", "save_list_entry", "list-entry", "anilist_update_list" -> "POST" to "/anilist/list-entry"
              "toggle_favourite", "favourite", "anilist_toggle_favourite" -> "POST" to "/anilist/favourite"
              "graphql", "anilist_graphql" -> "POST" to "/anilist/graphql"
              else -> return errorJson("Unsupported PortalAni action: $action", 400)
            }

        val conn = (URL("$LOCAL_HTTP_BASE$path").openConnection() as HttpURLConnection).apply {
          requestMethod = method
          connectTimeout = 5_000
          readTimeout = 25_000
          setRequestProperty("Accept", "application/json")
          if (method == "POST") {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
          }
        }
        if (method == "POST") {
          OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(params.toString()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (body.isNotBlank()) {
          JSONObject(body)
        } else {
          errorJson("Empty response from PortalAni (HTTP $code)", code)
        }
      } catch (e: Exception) {
        errorJson(
            "Could not reach PortalAni on this Portal (${e.message}). Make sure PortalAni is installed and updated.",
            503,
        )
      }

  private fun toQueryString(params: JSONObject): String {
    val keys = params.keys().asSequence().toList()
    if (keys.isEmpty()) return ""
    val pairs =
        keys.mapNotNull { k ->
          val v = params.opt(k)
          if (v == null || v == JSONObject.NULL) null
          else "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
        }
    return if (pairs.isEmpty()) "" else "?${pairs.joinToString("&")}"
  }

  fun errorJson(message: String, statusCode: Int = 502): JSONObject =
      JSONObject()
          .put("ok", false)
          .put("error", message)
          .put("statusCode", statusCode)

  fun mcpToolsSchema(): JSONArray =
      JSONArray()
          .put(
              toolDef(
                  name = "anilist_status",
                  description = "Check PortalAni AniList sign-in status, viewer profile, and active slideshow/calendar settings.",
                  properties = JSONObject(),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_get_lists",
                  description = "Fetch the user's personal AniList anime lists (Watching/CURRENT, Planning, Completed, Paused, Dropped, Repeating) via PortalAni.",
                  properties =
                      JSONObject()
                          .put("status", prop("string", "List status: CURRENT (or WATCHING), PLANNING, COMPLETED, PAUSED, DROPPED, REPEATING, comma-separated, or ALL."))
                          .put("query", prop("string", "Optional title or genre filter."))
                          .put("limit", prop("integer", "Max items to return (1-200, default 50)."))
                          .put("refresh", prop("boolean", "If true, bypass the 24h local cache and fetch fresh from AniList.")),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_get_airing",
                  description = "Fetch airing anime episode schedule for today or upcoming days via PortalAni, with user list status.",
                  properties =
                      JSONObject()
                          .put("days", prop("integer", "Number of days from today (1-14, default 7)."))
                          .put("onlyMyList", prop("boolean", "If true, only return episodes for anime on the user's AniList."))
                          .put("limit", prop("integer", "Max episodes to return (1-200, default 80)."))
                          .put("refresh", prop("boolean", "If true, bypass the 24h local cache and fetch fresh from AniList.")),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_search",
                  description = "Search anime on AniList by title via PortalAni, including the user's list status, progress, and score.",
                  properties =
                      JSONObject()
                          .put("query", prop("string", "Anime title to search."))
                          .put("limit", prop("integer", "Max results (1-25, default 10).")),
                  required = listOf("query"),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_get_media",
                  description = "Get full details (and optional franchise/recommendations) for an anime by its AniList mediaId via PortalAni.",
                  properties =
                      JSONObject()
                          .put("mediaId", prop("integer", "AniList anime ID."))
                          .put("includeRelated", prop("boolean", "Whether to include prequels, sequels, and recommendations.")),
                  required = listOf("mediaId"),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_update_list",
                  description = "Add or update an anime on the user's AniList (status, watched episode progress, or score 0-10) via PortalAni.",
                  properties =
                      JSONObject()
                          .put("mediaId", prop("integer", "AniList anime ID."))
                          .put("status", prop("string", "CURRENT (or WATCHING), PLANNING, COMPLETED, PAUSED, DROPPED, or REPEATING."))
                          .put("progress", prop("integer", "Watched episode number (>= 0)."))
                          .put("score", prop("number", "Score on 0-10 scale.")),
                  required = listOf("mediaId"),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_toggle_favourite",
                  description = "Toggle favourite status for an anime on the user's AniList via PortalAni.",
                  properties = JSONObject().put("mediaId", prop("integer", "AniList anime ID.")),
                  required = listOf("mediaId"),
              ),
          )
          .put(
              toolDef(
                  name = "anilist_graphql",
                  description = "Execute any raw AniList GraphQL v2 query or mutation through PortalAni using the Portal's home IP and signed-in OAuth token.",
                  properties =
                      JSONObject()
                          .put("query", prop("string", "GraphQL query or mutation string."))
                          .put("variables", JSONObject().put("type", "object").put("description", "Optional GraphQL variables object.")),
                  required = listOf("query"),
              ),
          )

  private fun prop(type: String, description: String): JSONObject =
      JSONObject().put("type", type).put("description", description)

  private fun toolDef(
      name: String,
      description: String,
      properties: JSONObject,
      required: List<String> = emptyList(),
  ): JSONObject =
      JSONObject()
          .put("name", name)
          .put("description", description)
          .put(
              "inputSchema",
              JSONObject()
                  .put("type", "object")
                  .put("properties", properties)
                  .apply {
                    if (required.isNotEmpty()) put("required", JSONArray(required))
                  },
          )
}
