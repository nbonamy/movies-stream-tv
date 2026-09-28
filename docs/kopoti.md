# Kopoti

Registered alongside Vidbox, with two site-owned sections:

| ID | Display title | Category | Playback type |
| --- | --- | --- | --- |
| `films` | À l'affiche | 29 | Standalone movie |
| `spectacles` | Spectacles | 3 | Standalone movie |

`KopotiSite` owns the current home URL, category API, opaque title IDs and offset
pagination. Search uses the website's `api_search.php` across all categories,
regardless of the section that was open. Results retain their own section:
Spectacle results belong to `spectacles`, and other standalone results to `films`.
Search continuation uses the server's offsets and `hasMore`; no local title
matching or category filtering is applied.

The adapter keeps an in-memory, provider-local cookie session. Some title pages
set a cookie and redirect to themselves; retaining that cookie avoids a redirect
loop. Normal cookie domain, path and expiry rules apply, and nothing is persisted.

Details parse the site's synopsis. Source discovery recognizes the ShareCloudy
iframe; resolution reloads the detail and player pages for a fresh stream.
`ShareCloudyExtractor` reads static JW Player source data without executing
scripts, returning HLS or MP4 plus the player referer, origin and user agent.
Unknown hosts and missing streams fail explicitly. No online subtitle identity
is claimed for Kopoti IDs; embedded subtitles still work.

## Validation

- MockWebServer tests exercise category selection, continuation, unified search across categories, cross-section cursor rejection, details,
  fresh source resolution, playback headers and unsupported-host rejection.
- Section contract tests cover three standalone sections with custom labels and
  reject results assigned to the wrong section.
- `check`, `lintDebug`, debug APK and instrumentation APK builds pass.
- A temporary live JVM smoke check passed for both sections: 20 initial titles,
  continuation, synopsis, source discovery/resolution, HLS playlist and its first
  referenced resource. A search for `ines` found Inès in Spectacles.
- Android TV emulator validation passed: both section catalogs and details,
  native playback of a film (00:24 of 1:36:40) and a spectacle (00:29 of 1:15:56),
  and phone search for `ines` returning Inès Reg within Spectacles.
- Force-stopping and reopening the app restored Kopoti / Spectacles. Existing
  film playback also appeared in Continue watching after reopening.
- All five Android instrumentation tests pass. The menu test waits for actual
  dialog window focus and the expected focused row before sending the next key;
  main-loop idle alone can precede window focus or input dispatch.
- A cookie-redirect regression failed before the session fix and passed after it.
  Toy Story 5 then played natively on the emulator (00:14 of 1:43:27). Nine other
  titles in a first-page scan exhibited the same self-redirect cookie gate.
  The cookie-fix suite passed with 24 core tests, lint and APK builds.
- The cookie fix was installed only on the emulator; the physical TV was not
  updated. Physical TV playback and episode auto-binging were not exercised in
  this pass.

Cloudflare may challenge clients differently over time. Validation used the
app's native HTTP user agent without imported browser cookies or a browser runtime.

Unified search regression coverage includes `tout le bleu` finding the older
Drama title Tout le bleu du ciel and spectacle results returned from film search.
`SearchScope.SITE` is advertised by Kopoti; Vidbox retains section-scoped search.

Live emulator checks from À l'affiche confirmed `tout le bleu` returns
Tout le bleu du ciel and `foresti` returns Florence Foresti : Boys Boys Boys.
The unified-search build passes 25 core tests, Android lint and APK builds.
