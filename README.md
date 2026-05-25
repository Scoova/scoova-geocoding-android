# Scoova Geocoding — Android / JVM

Geocoding geocoding client for `api.scoo-va.info/api/v1/geocoding` — forward
search, autocomplete, reverse, place lookup, structured search, and a
synchronous batch endpoint (up to 100 mixed forward/reverse queries per
request).

Pure Kotlin/JVM, coroutine-based, zero Android dependencies. Drops into
any JVM project (Android app, server, CLI).

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.pkg.github.com/Scoova/scoova-geocoding-android")
        maven("https://jitpack.io")
    }
}

// app build.gradle.kts
dependencies {
    implementation("info.scoo-va:scoova-geocoding:1.1.0")
}
```

## Usage

```kotlin
import com.scoova.geocoding.*
import kotlinx.coroutines.runBlocking

val client = GeocodingClient(
    apiKey = System.getenv("SCOOVA_API_KEY"),  // falls back to "demo"
    locale = "fr",                              // default Accept-Language + ?locale=
)

runBlocking {
    val hit = client.search("Tour Eiffel")
    val rev = client.reverse(48.8584, 2.2945, size = 1)
    val ac  = client.autocomplete("Tour Eif")
    val pl  = client.place(listOf("place data:locality:101751119"))
    val st  = client.searchStructured(mapOf("locality" to "Cairo", "country" to "EG"))

    val batch = client.batch(listOf(
        BatchItem(id = "a", text = "Times Square"),
        BatchItem(id = "b", lat = 40.7484, lon = -73.9857),
    ))
    for (row in batch.results) println("${row.id} -> ${row.top?.label ?: row.error}")
}
```

## Constructor options

| arg              | type     | default                              | notes                                  |
| ---------------- | -------- | ------------------------------------ | -------------------------------------- |
| `baseUrl`        | `String` | `https://api.scoo-va.info/api/v1/geocoding` |                                |
| `apiKey`         | `String?`| `SCOOVA_API_KEY` env, then `demo`    | sent as `X-API-Key`                    |
| `locale`         | `String` | `"en"`                               | `?locale=` + `Accept-Language`         |
| `androidPackage` | `String?`| —                                    | `X-Android-Package` (key restriction)  |
| `iosBundleId`    | `String?`| —                                    | `X-Ios-Bundle-Identifier`              |
| `timeoutMs`      | `Int`    | 30 000                               | per-request connect + read timeout     |
| `http`           | `GeocodingHttp?` | `HttpURLConnection` on IO    | swap in OkHttp / mock for tests        |

## Methods

- `search(text, …)` → `/v1/search`
- `autocomplete(text, …)` → `/v1/autocomplete`
- `reverse(lat, lon, …)` → `/v1/reverse`
- `place(ids)` → `/v1/place`
- `searchStructured(query, …)` → `/v1/search/structured`
- `batch(items)` → `/v1/batch` (POST, max 100 items)

## Tests

```
./gradlew test
```

## License

Apache-2.0 — see [LICENSE](./LICENSE).
