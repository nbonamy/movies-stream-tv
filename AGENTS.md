# Working on Movies Stream

Movies Stream is a native Android TV app. Preserve its remote-first experience
and keep site-specific behavior behind the core interfaces.

## Ownership and references

Core paths below are relative to `core/src/main/kotlin/fr/bonamy/movies/core/`.

| Area | Owner |
| --- | --- |
| Site sections, catalog, search, details and source policy | `sites/<site>/` implementing `StreamingSite` |
| Seasons, episodes and ordering | The site's `SeriesCatalog`; shared navigation in `EpisodeNavigation.kt` |
| Playback-host requests and decoding | `extractors/` |
| Shared identities, cursors and playback results | `StreamingSite.kt` and `MediaModels.kt` |
| Bounded HTTP and destination checks | `HttpTransport.kt` |
| Native UI, Media3 and persisted playback | `app/src/main/java/fr/bonamy/movies/` |
| Site registration | `AppServices.kt` |

- Before adding a site or changing a shared contract, read
  [multi-site architecture](docs/multi-site-design.md) and the current interfaces.
- For subtitle work, read [subtitle behavior](docs/subtitles.md),
  `SubtitleSelection.kt` and the hosted-track contract in `MediaModels.kt`.
- For persistence or episode transitions, read [resume behavior](docs/resume-playback.md)
  and `EpisodeNavigation.kt`.
- For UI work, read [UI provenance](docs/mediastation-ui.md) and reuse existing
  resources. Full-screen playback, the Subtitles / Quality / Source order,
  underline focus and physical Back behavior are deliberate product decisions.

## Integrating a new site

1. **Establish the actual upstream contract.** Inspect its catalog, search,
   detail and selected-player requests. Record pagination, identifiers, source
   labels/defaults, headers and access constraints in a site document under
   `docs/`. For TV, establish season and episode identity/order separately.
   Finish this step with reproducible request evidence, not guessed selectors.
2. **Prove native extraction.** Resolve a representative movie and, for a TV
   site, an episode to HLS/MP4. Verify rendered video and advancing playback in
   Media3 on an emulator. A successful playlist request alone is insufficient.
   Parse known configuration as data; no WebView or provider JavaScript runtime.
3. **Implement the adapter.** Add `sites/<site>/` with a stable site ID and
   site-owned section IDs. Implement catalog **and search**, details, source
   discovery/default selection and selected-source resolution. Declare whether
   search is section-scoped or site-wide. Match returned items to their sections.
4. **Preserve identity and continuation.** Keep provider IDs opaque. Scope
   `PageToken` to site, section and query, follow real continuation, and return
   null only at the end. Never infer playback IDs from a URL suffix without
   evidence. Bookmarks must survive fresh stream URLs and domain changes.
5. **Implement TV through `SeriesCatalog`.** Return ordered seasons/episodes
   with stable references. Reuse shared `nextEpisode` unless the provider has a
   better authoritative mechanism. Ambiguous identity and lookup failures must
   surface as errors; null means the series has actually ended.
6. **Keep extraction separate.** Reuse an extractor only when the host protocol
   matches. Resolve a fresh stream for the chosen source. Make supported sources
   and the default deliberate; document partial support and differences from
   the website. Explicit source failures must not silently choose another server.
7. **Normalize optional capabilities.** Return headers for playlists, keys,
   segments and hosted subtitles. Use `ResolvedPlayback.subtitles` for hosted
   WebVTT and `SubtitleContext` only for verified IMDb/episode identity. Offer
   French and English subtitles. Site IDs are not IMDb IDs. Reuse shared resume
   and language carryover; add a shared contract only for a real new capability,
   never a site-name branch in the Activity.
8. **Register, validate and document.** Register in `AppServices.sites` once the
   supported playback path works. Add focused fixture tests at the adapter or
   extractor boundary, run the relevant checks below, and verify the real UI.
   Update the site document and README's supported-site table. Distinguish
   implemented, fixture-tested, emulator-verified and unsupported behavior.

All suspending site operations must be main-safe and propagate cancellation.
Use `HttpTransport` for bounded requests and validated destinations/redirects.
Keep transient URLs, cookies and tokens out of bookmarks, logs and fixtures.
The existing adapters illustrate different contracts: Kopoti's site-wide search,
Vidbox's external episode metadata, and 123Movies' opaque season slugs and sidecars.

## Validation and deployment

- Core logic: `./gradlew :core:test` (narrow with `--tests` while iterating).
- App changes: `make check` runs tests, lint and the debug build.
- Android persistence/UI: build `:app:assembleDebugAndroidTest` and run the
  instrumentation APK on the explicitly selected emulator.
- Site acceptance: browse and append a page, search, open details, inspect
  supported sources, play natively, and resume after reopening. For TV/subtitle
  changes, check episode navigation, subtitle restoration and language carryover.
  Preserve existing app data while testing and remove temporary probe artifacts.
- Documentation-only changes: check links, commands and rendered screenshots;
  an Android rebuild is unnecessary unless application code also changed.

Read [development commands](docs/development.md) before deploying. Use an emulator
for routine verification; deploy to a physical TV only when explicitly requested.
`make deploy` installs without launching; `make run` launches. Always name the
intended target. Report what was actually installed and tested.

## Documentation and publication

Keep README factual and product-oriented: a short description, features, real
screenshots, supported sites, usage and setup. Use ordinary project-documentation
headings and direct descriptions; omit slogans and promotional calls to action.
Put detailed implementation guidance in `docs/`.
Capture screenshots from the actual app; exclude personal history, addresses,
QR URLs, account details and debug overlays.

Keep personal device settings in ignored `Makefile.local` or environment
variables. Use generic placeholders and repository-relative paths in shared docs.
Keep credentials, session tokens and private captures out of commits. Preserve
unrelated changes and only commit or push when requested.
