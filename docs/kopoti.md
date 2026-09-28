# Kopoti

Registered alongside Vidbox, with two site-owned sections:

| ID | Display title | Category | Playback type |
| --- | --- | --- | --- |
| `films` | À l'affiche | 29 | Standalone movie |
| `spectacles` | Spectacles | 3 | Standalone movie |

`KopotiSite` owns the current home URL, category API, opaque title IDs and offset
pagination. Search scans category pages and filters titles with case- and
accent-insensitive matching. It stops at a matching page or the end, preserving
continuation and cancellation. This is intentionally section-scoped; it does
not query the site's unrelated categories or Vidbox. Large searches may need
several requests.

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

- MockWebServer tests exercise category selection, continuation, accent-insensitive
  search across nonmatching pages, cross-section cursor rejection, details,
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
  The full suite passes with 24 core tests, lint and APK builds.
- The cookie fix was installed only on the emulator; the physical TV was not
  updated. Physical TV playback and episode auto-binging were not exercised in
  this pass.

Cloudflare may challenge clients differently over time. Validation used the
app's native HTTP user agent without imported browser cookies or a browser runtime.
