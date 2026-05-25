# Changelog

All notable changes to the Scoova Geocoding Android SDK are documented
here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.1.1 — 2026-05-25
- Default `baseUrl` switched from the retired `https://geocoding.scoo-va.info` subdomain to the central gateway at `https://api.scoo-va.info/api/v1/geocoding`. Callers who explicitly set `baseUrl` are unaffected. The old subdomain returns `ENDPOINT_RETIRED`.

## [1.1.0] — 2026-05-25

### Added
- `apiKey` constructor argument — sent as `X-API-Key` on every request.
  Falls back to the `SCOOVA_API_KEY` env var, then to the public `demo`
  key. The demo key is rate-limited; ship a real one for production.
- `locale` constructor argument (replaces the old `defaultLang`). Sent as
  both `?locale=` and `Accept-Language` on every request. A per-call
  `lang` argument still overrides it.
- `androidPackage` / `iosBundleId` constructor arguments — identity
  headers for gateway key-restriction enforcement.
- `batch(items)` — synchronous POST to `/v1/batch`, up to 100 mixed
  forward (`text`) or reverse (`lat`+`lon`) queries per request. Each
  optional `id` is echoed back unchanged.
- `BatchItem`, `BatchResponse`, `BatchResultRow` data classes.
- Monitor-style publishing block in `build.gradle.kts`: GitHub Packages
  works immediately; Maven Central is staged for when the Sonatype
  account is provisioned. Targets `Scoova/scoova-geocoding-android`.
- Apache-2.0 LICENSE, CHANGELOG.md, .gitignore.

### Changed
- `GeocodingHttp` typealias signature: `(url) -> Pair` →
  `(url, method, headers, body) -> Pair`. Tests need an update; the
  default `HttpURLConnection` transport is unchanged for callers.
- Group/artifact coordinates remain `info.scoo-va:scoova-geocoding`.

### Compatibility
- Existing per-call signatures (`search`, `autocomplete`, `reverse`,
  `place`, `searchStructured`) are unchanged. Apps that just call the
  client keep working. Only callers that supplied a custom
  `GeocodingHttp` test stub need to widen its signature.

## [1.0.0] — 2026-05-04

### Added
- Initial release. `search`, `autocomplete`, `reverse`, `place`,
  `searchStructured` against `https://geocoding.scoo-va.info`.
- `GeocodingException.Http` / `Decode` / `Transport`.
