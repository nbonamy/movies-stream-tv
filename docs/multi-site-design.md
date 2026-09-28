# Multi-site architecture

Vidbox and Kopoti are registered. Sites expose an ordered list of sections with
stable IDs, site-chosen display titles and media types. Multiple sections may use
the same media type; section identity is independent of playback identity.

## Ownership

| Module | Responsibility |
| --- | --- |
| `StreamingSite` | Site sections, catalogs, search, title details, source discovery/defaults, resolution, optional subtitle metadata |
| `SeriesCatalog` | Seasons and episodes; absent on movie-only sites |
| `CatalogBrowser` | Current site/query, opaque continuation, deduplication, stale-result rejection |
| `sites/vidbox` | Vidbox requests, parsing, TMDB identity mapping and numbered pagination |
| `sites/kopoti` | Category pagination, section-scoped search, HTML details and ShareCloudy source discovery |
| `extractors` | Max, Vidpro, VidRock and ShareCloudy request/configuration chains |
| `HttpTransport` | Bounded responses, per-request timeouts, cancellation checks and validated redirects/destinations |
| `SubtitleClient` | French/English lookup and download from normalized IMDb/episode metadata |
| `:app` | Native views, Media3 playback, captured request sessions and site-scoped persistence |

`AppServices.sites` is the composition point. The activity calls `StreamingSite`;
it does not construct a Vidbox client or know its numeric IDs, URL paths, page
limits, headers or default source. Extractors are internal to the core module.
They parse known configuration as data. No WebView or provider JavaScript runtime
is used.

## Site contract

```kotlin
interface StreamingSite {
    val descriptor: SiteDescriptor
    val series: SeriesCatalog?
    suspend fun browse(request: CatalogRequest, after: PageToken?): CatalogPage
    suspend fun details(title: Title): Title
    suspend fun sources(item: PlayableRef): PlaybackOptions
    suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback
    suspend fun subtitleContext(item: PlayableRef): SubtitleContext?
}
```

All suspending operations must be safe to call from the main thread and propagate
cancellation. Vidbox runs blocking HTTP on the IO dispatcher. The optional
subtitle method defaults to no online lookup; in-stream tracks remain available.

- `SiteSection(id, title, mediaType)` defines navigation. `SiteDescriptor.sections`
  may contain any positive number of sections with unique IDs; labels need not be
  unique. A section rename does not change its ID.
- `Title.sectionId` records the section it was browsed from.
- `TitleRef` contains a stable site ID, media type and opaque site-local title ID.
- `PlayableRef` adds an opaque episode ID for TV. Season/episode numbers are
  optional display metadata; they are not universal identity fields.
- `SeasonRef` has an opaque season ID. A site has a TV section exactly when it supplies
  a `SeriesCatalog`. Its lists are in playback order. The default `nextEpisode`
  follows that order across seasons using opaque episode identity; sites may
  override it to use their own next-episode endpoint. Null means the final listed
  episode, while lookup failures propagate for retry.
- `PlaybackOptions` supplies available sources and the default. A remembered
  selection is used only if still available. Explicit resolution failures do not
  silently switch to another source.
- `ResolvedPlayback` supplies a fresh URI, media format, request headers and
  optional normalized subtitle metadata. Supported native formats are HLS and MP4.
  The headers apply to Media3's playlists, keys and segments.

## Search and infinite navigation

`CatalogRequest(sectionId, query)` is shared by home catalogs and search. A null query
means the site's home catalog. Every site implements its own search requests and
parsing behind `browse`; the UI never falls back to searching Vidbox.

`CatalogPage.next` is an opaque token scoped to the site, section and query.
Null means the end. The UI appends as remote focus approaches the bottom, keeping
focus and existing cards. It knows nothing about page numbers. Vidbox owns its
numbered pages and 500-page limit.

Search edits debounce for 600 ms. Editing immediately invalidates older work;
automatic result refreshes preserve keyboard focus. Explicit submission hides
the keyboard. Site/section changes cancel pending work and clear search and pages.
Captured site references and request generations prevent late responses from
replacing a newer screen or playback session.

## Hamburger menu

| Active site | Top section | Below the divider |
| --- | --- | --- |
| Vidbox | Movies; TV Shows | Kopoti |
| Kopoti | À l'affiche; Spectacles | Vidbox |

The current site is omitted from the switch list and shown in the browser header.
Only section rows receive selected highlighting. The divider cannot receive focus.
Rows reuse MediaStation's dialog resources and remote behavior. With one site,
the divider and switch section are absent.

Switching restores the new site's last supported section, otherwise its first section,
then loads its catalog and Continue watching list.

## Persistence and migration

Playback keys use a versioned JSON array of site, media type, title and episode
identity, avoiding delimiter collisions for slugs or paths. Continue watching is
filtered by both site and section. Selected source and section are also scoped
to the stable site ID. A domain or display-name change must not change that ID.

`LegacyVidboxMigration` upgrades old movie/episode bookmarks and the old global
server preference to Vidbox once. It preserves positions and metadata and does
not overwrite an already migrated entry. Playback saves use the captured target,
not the currently selected browsing site. Stream URLs and tokens are not persisted.

## Adding a site

1. Implement `StreamingSite` under `core/.../sites/<site>` using a stable ID.
   Declare ordered `SiteSection` entries with stable IDs and site-owned titles.
2. Return site-qualified opaque identities and the requested `Title.sectionId`. Implement home and search, details,
   source discovery/default selection, and only the chosen source's resolution.
3. Supply `SeriesCatalog` only if TV is supported. Return optional subtitle
   metadata using IMDb identity; do not pass site-local IDs to online lookup.
4. Reuse a host extractor if its protocol actually matches, otherwise add a
   provider-specific extractor. Keep network/parser code out of the Android UI.
5. Register the site in `AppServices.sites` and add request/response fixture tests.
   Verify real catalog/search, remote navigation and native playback on emulator.

Kopoti live JVM validation is recorded in [Kopoti integration](kopoti.md).

## Verification

`./gradlew check :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
passes. Core tests cover the existing request chains plus site/query isolation,
opaque cursors/IDs, stale responses, capability menus and bounded HTTP redirects.
Four emulator instrumentation tests cover playback persistence/migration,
site collisions/preferences and remote focus across the menu divider.

Live emulator checks after the refactor confirmed Vidbox movie/TV catalogs,
search edits and backspace with the keyboard remaining open, explicit submission,
the migrated Reacher S2E3 bookmark resuming native playback at 9:47, and
French/English subtitle results for that episode. Season navigation also loaded
all eight named episodes of Reacher season 2.
No TV deployment is part of this refactor.

## Section migration and playback semantics

The old Vidbox mode preference (`MOVIE` / `TV`) maps to section IDs `movie` / `tv`
until a section preference is saved. Existing bookmarks without `sectionId` use
their old media type's ID. Playback keys are unchanged, preserving resume positions.
If a title appears in more than one section its playback identity and position
remain shared; Continue watching uses the section of its saved title.

Sections control browsing, labels, search and continuation. `Title.type`,
`PlayableRef` and `SeriesCatalog` still control standalone versus episode
playback. Episode auto-binging uses those contracts, never section IDs or
display titles.
