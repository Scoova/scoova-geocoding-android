package com.scoova.geocoding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─── Public option DTOs ───────────────────────────────────────────────

data class FocusPoint(val lat: Double, val lon: Double)
data class BoundaryCircle(val lat: Double, val lon: Double, val radiusKm: Double)
data class BoundaryRect(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

// ─── Result shapes ────────────────────────────────────────────────────

data class GeoFeature(val lon: Double, val lat: Double, val properties: JSONObject) {
    val label: String get() =
        properties.optString("label").ifEmpty { properties.optString("name") }
}

data class GeoResponse(val features: List<GeoFeature>, val raw: JSONObject) {
    companion object {
        fun fromJson(j: JSONObject): GeoResponse {
            val arr = j.optJSONArray("features") ?: return GeoResponse(emptyList(), j)
            val out = ArrayList<GeoFeature>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates")
                val lon = coords?.optDouble(0) ?: 0.0
                val lat = coords?.optDouble(1) ?: 0.0
                val props = f.optJSONObject("properties") ?: JSONObject()
                out.add(GeoFeature(lon, lat, props))
            }
            return GeoResponse(out, j)
        }
    }
}

/** One row in a `batch` response. `top` is the highest-confidence feature
 *  for the corresponding input query, or `null` if the query produced an
 *  error (in which case `error` is set). */
data class BatchResultRow(
    val id: String?,
    val top: GeoFeature?,
    val error: String?,
) {
    companion object {
        fun fromJson(j: JSONObject): BatchResultRow {
            val topObj = j.optJSONObject("top")
            val topFeature = topObj?.let {
                val coords = it.optJSONObject("geometry")?.optJSONArray("coordinates")
                val lon = coords?.optDouble(0) ?: 0.0
                val lat = coords?.optDouble(1) ?: 0.0
                val props = it.optJSONObject("properties") ?: JSONObject()
                GeoFeature(lon, lat, props)
            }
            return BatchResultRow(
                id = if (j.isNull("id")) null else j.optString("id", null),
                top = topFeature,
                error = if (j.isNull("error")) null else j.optString("error", null),
            )
        }
    }
}

data class BatchResponse(val count: Int, val results: List<BatchResultRow>) {
    companion object {
        fun fromJson(j: JSONObject): BatchResponse {
            // Server returns `{ success, data: { count, results } }`; unwrap if
            // present, otherwise read the fields off the top level.
            val source = j.optJSONObject("data") ?: j
            val rows = mutableListOf<BatchResultRow>()
            val arr = source.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) rows.add(BatchResultRow.fromJson(arr.getJSONObject(i)))
            }
            return BatchResponse(source.optInt("count", rows.size), rows)
        }
    }
}

/** One item in a batch geocoding request. Provide `text` for a forward
 *  query, `lat`+`lon` for a reverse query. `id` is echoed back on the
 *  matching result row so you can join to your own records. */
data class BatchItem(
    val id: String? = null,
    val text: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it) }
        text?.let { put("text", it) }
        lat?.let { put("lat", it) }
        lon?.let { put("lon", it) }
    }
}

// ─── Errors + transport ───────────────────────────────────────────────

sealed class GeocodingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val statusCode: Int, body: String) : GeocodingException("HTTP $statusCode: ${body.take(200)}")
    class Decode(message: String) : GeocodingException(message)
    class Transport(cause: Throwable) : GeocodingException(cause.message ?: "transport error", cause)
}

/**
 * Pluggable HTTP transport for tests. The default implementation uses
 * `HttpURLConnection` on the IO dispatcher. The lambda must honour the
 * request method (`GET` or `POST`), the headers, and (for POST) the body.
 */
typealias GeocodingHttp = suspend (
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String?,
) -> Pair<Int, String>

// ─── Client ───────────────────────────────────────────────────────────

class GeocodingClient(
    baseUrl: String = "https://api.scoo-va.info/api/v1/geocoding",
    /**
     * Scoova API key. Sent on every request as `X-API-Key`. If null, the
     * client falls back to the `SCOOVA_API_KEY` environment variable, then
     * to the public `demo` key (rate-limited; not for production traffic).
     */
    apiKey: String? = null,
    /**
     * Default locale (e.g. `"en"`, `"fr"`, `"ar-EG"`). Sent as both
     * `?locale=` and `Accept-Language` on every request. A per-call `lang`
     * argument still overrides it. Defaults to `"en"` so labels come back
     * in a single language — without it, Pelias mixes per-field.
     */
    private val locale: String = "en",
    private val androidPackage: String? = null,
    private val iosBundleId: String? = null,
    private val timeoutMs: Int = 30_000,
    private val http: GeocodingHttp? = null,
) {
    private val baseUrl: String = baseUrl.trimEnd('/')
    private val apiKey: String = apiKey
        ?: System.getenv("SCOOVA_API_KEY")
        ?: "demo"

    /** Forward search — "Burj Khalifa" → list of features. */
    suspend fun search(
        text: String,
        focusPoint: FocusPoint? = null,
        boundaryCircle: BoundaryCircle? = null,
        boundaryRect: BoundaryRect? = null,
        boundaryCountry: List<String>? = null,
        layers: List<String>? = null,
        sources: List<String>? = null,
        size: Int? = null,
        lang: String? = null,
    ): GeoResponse {
        val params = LinkedHashMap<String, String>()
        params["text"] = text
        applySearchParams(
            params, focusPoint, boundaryCircle, boundaryRect,
            boundaryCountry, layers, sources, size, lang,
        )
        return getJson("/v1/search", params)
    }

    /** Type-ahead autocomplete — partial text → suggestions. */
    suspend fun autocomplete(
        text: String,
        focusPoint: FocusPoint? = null,
        boundaryCountry: List<String>? = null,
        layers: List<String>? = null,
        sources: List<String>? = null,
        size: Int? = null,
        lang: String? = null,
    ): GeoResponse {
        val params = LinkedHashMap<String, String>()
        params["text"] = text
        focusPoint?.let {
            params["focus.point.lat"] = it.lat.toString()
            params["focus.point.lon"] = it.lon.toString()
        }
        boundaryCountry?.takeIf { it.isNotEmpty() }?.let { params["boundary.country"] = it.joinToString(",") }
        layers?.takeIf { it.isNotEmpty() }?.let { params["layers"] = it.joinToString(",") }
        sources?.takeIf { it.isNotEmpty() }?.let { params["sources"] = it.joinToString(",") }
        size?.let { params["size"] = it.toString() }
        params["lang"] = lang ?: locale
        return getJson("/v1/autocomplete", params)
    }

    /** Reverse geocode — coordinates → nearest features. */
    suspend fun reverse(
        lat: Double,
        lon: Double,
        size: Int? = null,
        layers: List<String>? = null,
        sources: List<String>? = null,
        boundaryCircleRadiusKm: Double? = null,
        boundaryCountry: List<String>? = null,
        lang: String? = null,
    ): GeoResponse {
        val params = LinkedHashMap<String, String>()
        params["point.lat"] = lat.toString()
        params["point.lon"] = lon.toString()
        size?.let { params["size"] = it.toString() }
        layers?.takeIf { it.isNotEmpty() }?.let { params["layers"] = it.joinToString(",") }
        sources?.takeIf { it.isNotEmpty() }?.let { params["sources"] = it.joinToString(",") }
        boundaryCircleRadiusKm?.let { params["boundary.circle.radius"] = it.toString() }
        boundaryCountry?.takeIf { it.isNotEmpty() }?.let { params["boundary.country"] = it.joinToString(",") }
        params["lang"] = lang ?: locale
        return getJson("/v1/reverse", params)
    }

    /** Lookup one or more Pelias gids (e.g. `whosonfirst:locality:101751119`). */
    suspend fun place(ids: List<String>): GeoResponse =
        getJson("/v1/place", linkedMapOf("ids" to ids.joinToString(","), "lang" to locale))

    /** Structured search — address, locality, country etc. as separate fields. */
    suspend fun searchStructured(
        query: Map<String, String>,
        focusPoint: FocusPoint? = null,
        boundaryCircle: BoundaryCircle? = null,
        boundaryRect: BoundaryRect? = null,
        boundaryCountry: List<String>? = null,
        layers: List<String>? = null,
        sources: List<String>? = null,
        size: Int? = null,
        lang: String? = null,
    ): GeoResponse {
        val params = LinkedHashMap<String, String>()
        params.putAll(query)
        applySearchParams(
            params, focusPoint, boundaryCircle, boundaryRect,
            boundaryCountry, layers, sources, size, lang,
        )
        return getJson("/v1/search/structured", params)
    }

    /**
     * Batch geocode — up to 100 mixed forward (`text`) or reverse
     * (`lat` + `lon`) queries in a single round-trip. Each result is
     * returned in input order with the supplied `id` echoed back.
     */
    suspend fun batch(items: List<BatchItem>): BatchResponse {
        require(items.isNotEmpty()) { "batch: items cannot be empty" }
        require(items.size <= 100) { "batch: max 100 items per request" }
        val body = JSONObject().put("items", JSONArray(items.map { it.toJson() }))
        val responseJson = postJson("/v1/batch", body.toString())
        return BatchResponse.fromJson(responseJson)
    }

    // ─── internals ────────────────────────────────────────────────────

    private fun applySearchParams(
        params: LinkedHashMap<String, String>,
        focusPoint: FocusPoint?,
        boundaryCircle: BoundaryCircle?,
        boundaryRect: BoundaryRect?,
        boundaryCountry: List<String>?,
        layers: List<String>?,
        sources: List<String>?,
        size: Int?,
        lang: String?,
    ) {
        focusPoint?.let {
            params["focus.point.lat"] = it.lat.toString()
            params["focus.point.lon"] = it.lon.toString()
        }
        boundaryCircle?.let {
            params["boundary.circle.lat"] = it.lat.toString()
            params["boundary.circle.lon"] = it.lon.toString()
            params["boundary.circle.radius"] = it.radiusKm.toString()
        }
        boundaryRect?.let {
            params["boundary.rect.min_lon"] = it.minLon.toString()
            params["boundary.rect.min_lat"] = it.minLat.toString()
            params["boundary.rect.max_lon"] = it.maxLon.toString()
            params["boundary.rect.max_lat"] = it.maxLat.toString()
        }
        boundaryCountry?.takeIf { it.isNotEmpty() }?.let { params["boundary.country"] = it.joinToString(",") }
        layers?.takeIf { it.isNotEmpty() }?.let { params["layers"] = it.joinToString(",") }
        sources?.takeIf { it.isNotEmpty() }?.let { params["sources"] = it.joinToString(",") }
        size?.let { params["size"] = it.toString() }
        params["lang"] = lang ?: locale
    }

    /** Standard headers for every request — auth + identity + locale. */
    private fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["X-API-Key"] = apiKey
        h["Accept"] = "application/json"
        h["Accept-Language"] = locale
        androidPackage?.let { h["X-Android-Package"] = it }
        iosBundleId?.let { h["X-Ios-Bundle-Identifier"] = it }
        h.putAll(extra)
        return h
    }

    private fun buildUrl(path: String, params: Map<String, String>): String {
        // Always send the client-default locale as `?locale=`. Any per-call
        // `lang=` already in `params` rides alongside; the gateway picks the
        // one it understands.
        val merged = LinkedHashMap<String, String>()
        merged["locale"] = locale
        merged.putAll(params)
        val qs = merged.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8.name())}=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
        }
        return if (qs.isEmpty()) "$baseUrl$path" else "$baseUrl$path?$qs"
    }

    private val defaultHttp: GeocodingHttp = { url, method, headers, body ->
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                code to text
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun getJson(path: String, params: Map<String, String>): GeoResponse {
        val url = buildUrl(path, params)
        val (code, body) = invoke(url, "GET", authHeaders(), null)
        if (code !in 200..299) throw GeocodingException.Http(code, body)
        return try {
            GeoResponse.fromJson(JSONObject(body))
        } catch (t: Throwable) {
            throw GeocodingException.Decode("Invalid JSON: ${t.message}")
        }
    }

    private suspend fun postJson(path: String, body: String): JSONObject {
        val url = buildUrl(path, emptyMap())
        val (code, text) = invoke(url, "POST", authHeaders(), body)
        if (code !in 200..299) throw GeocodingException.Http(code, text)
        return try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw GeocodingException.Decode("Invalid JSON: ${t.message}")
        }
    }

    private suspend fun invoke(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        return try {
            (http ?: defaultHttp).invoke(url, method, headers, body)
        } catch (t: Throwable) {
            throw GeocodingException.Transport(t)
        }
    }
}
