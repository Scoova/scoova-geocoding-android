package com.scoova.geocoding

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

/** Capture the most recent invocation so tests can assert on URL + headers + body. */
private class Capture(
    var url: String = "",
    var method: String = "",
    var headers: Map<String, String> = emptyMap(),
    var body: String? = null,
)

private fun stub(capture: Capture, status: Int = 200, response: String = EMPTY): GeocodingHttp =
    { url, method, headers, body ->
        capture.url = url
        capture.method = method
        capture.headers = headers
        capture.body = body
        status to response
    }

class GeocodingClientTest {
    @Test fun searchHitsV1Search() = runTest {
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k", http = stub(cap),
        )
        client.search("Cairo")
        assertEquals("GET", cap.method)
        assertTrue(cap.url.startsWith("https://example.test/v1/search?"), cap.url)
        assertTrue(cap.url.contains("text=Cairo"))
    }

    @Test fun forwardsFocusBoundaryAndSize() = runTest {
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k", http = stub(cap),
        )
        client.search(
            text = "coffee",
            focusPoint = FocusPoint(30.04, 31.24),
            boundaryCountry = listOf("EG", "AE"),
            layers = listOf("venue", "address"),
            size = 5,
            lang = "ar-EG",
        )
        assertTrue(cap.url.contains("focus.point.lat=30.04"), cap.url)
        assertTrue(cap.url.contains("boundary.country=EG%2CAE"), cap.url)
        assertTrue(cap.url.contains("layers=venue%2Caddress"), cap.url)
        assertTrue(cap.url.contains("size=5"), cap.url)
        assertTrue(cap.url.contains("lang=ar-EG"), cap.url)
    }

    @Test fun reverseUsesPointParams() = runTest {
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k", http = stub(cap),
        )
        client.reverse(30.04, 31.24, size = 1)
        assertTrue(cap.url.contains("/v1/reverse?"), cap.url)
        assertTrue(cap.url.contains("point.lat=30.04"))
        assertTrue(cap.url.contains("point.lon=31.24"))
    }

    @Test fun placeJoinsIdsWithCommas() = runTest {
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k", http = stub(cap),
        )
        client.place(listOf("place data:locality:101751119", "place data:country:85632343"))
        assertTrue(cap.url.contains("ids=place data%3Alocality%3A101751119%2Cplace data%3Acountry%3A85632343"))
    }

    @Test fun parsesFeatures() = runTest {
        val body = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[31.24,30.04]},
           "properties":{"name":"Cairo","label":"Cairo, Egypt"}}
        ]}
        """.trimIndent()
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k",
            http = stub(cap, response = body),
        )
        val res = client.search("Cairo")
        assertEquals(1, res.features.size)
        assertEquals(31.24, res.features[0].lon, 0.0001)
        assertEquals(30.04, res.features[0].lat, 0.0001)
        assertEquals("Cairo, Egypt", res.features[0].label)
    }

    @Test fun throwsOnNon2xx() = runTest {
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k",
            http = stub(Capture(), status = 502, response = "boom"),
        )
        val ex = assertFailsWith<GeocodingException.Http> { client.search("Cairo") }
        assertEquals(502, ex.statusCode)
    }

    @Test fun sendsApiKeyAndLocaleHeaders() = runTest {
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "sk_live", locale = "fr",
            http = stub(cap),
        )
        client.search("Paris")
        assertEquals("sk_live", cap.headers["X-API-Key"])
        assertEquals("fr", cap.headers["Accept-Language"])
        assertTrue(cap.url.contains("locale=fr"), cap.url)
        assertTrue(cap.url.contains("lang=fr"), cap.url)
    }

    @Test fun batchPostsToV1BatchWithItems() = runTest {
        val body = """{"success":true,"data":{"count":2,"results":[
          {"id":"a","top":{"type":"Feature","geometry":{"type":"Point","coordinates":[31.24,30.04]},"properties":{"label":"Cairo"}}},
          {"id":"b","error":"no result"}
        ]}}"""
        val cap = Capture()
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k",
            http = stub(cap, response = body),
        )
        val res = client.batch(listOf(
            BatchItem(id = "a", text = "Cairo"),
            BatchItem(id = "b", lat = 1.0, lon = 2.0),
        ))
        assertEquals("POST", cap.method)
        assertTrue(cap.url.contains("/v1/batch"), cap.url)
        assertTrue((cap.body ?: "").contains("\"items\""))
        assertTrue((cap.body ?: "").contains("Cairo"))
        assertEquals(2, res.count)
        assertEquals("a", res.results[0].id)
        assertEquals("Cairo", res.results[0].top?.label)
        assertEquals("b", res.results[1].id)
        assertNull(res.results[1].top)
        assertEquals("no result", res.results[1].error)
    }

    @Test fun batchRejectsEmptyAndOversize() = runTest {
        val client = GeocodingClient(
            baseUrl = "https://example.test", apiKey = "k", http = stub(Capture()),
        )
        assertFailsWith<IllegalArgumentException> { client.batch(emptyList()) }
        val oversize = (1..101).map { BatchItem(id = it.toString(), text = "x") }
        assertFailsWith<IllegalArgumentException> { client.batch(oversize) }
    }
}
