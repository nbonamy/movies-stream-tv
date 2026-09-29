# Multi-site architecture

Vidbox, Kopoti, 123Movies and Cinejoy are registered. Sites expose an ordered list of sections with
stable IDs, site-chosen display titles and media types. Multiple sections may use
the same media type; section identity is independent of playback identity.

## Ownership

| Module | Responsibility |
| --- | --- |
| `StreamingSite` | Site sections, catalogs, search, title details, source discovery/defaults, resolution, optional subtitle metadata |
| `SeriesCatalog` | Seasons and episodes; absent on movie-only sites |
| `CatalogBrowser` | One site/section/query, opaque continuation, deduplication, stale-result rejection |
| `UniversalSearch` | Concurrent cross-site search, section aggregation, independent row pagination and retries |
| `sites/vidbox` | Vidbox requests, parsing, TMDB identity mapping and numbered pagination |
| `sites/kopoti` | Category pagination, unified search, HTML details and ShareCloudy source discovery |
| `sites/movies123` | Movie/season catalogs, unified search, season grouping and opaque page/episode identities |
| `sites/cinejoy` | TMDB catalogs and unified search, season/episode identity and Wing source policy |
| `extractors` | Max, Vidpro, VidRock, ShareCloudy, Ployan and Wing request/configuration chains |
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
  optional normalized subtitle metadata and hosted WebVTT/SRT tracks. Supported native
  formats are HLS and MP4. The headers apply to Media3's playlists, keys, segments
  and hosted subtitles. Hosted track identities, languages and labels let the
  shared menu and persisted subtitle selection work without site-specific UI logic.

## Search and infinite navigation

`CatalogRequest(sectionId, query)` is shared by home catalogs and search. A null query
means the site's home catalog. Every site implements its own search requests and
parsing behind `browse`; the UI never falls back to searching Vidbox.
`SiteDescriptor.searchScope` declares section-scoped search (Vidbox, the default)
or site-wide search (Kopoti, 123Movies and Cinejoy). Site-wide search may return titles from any declared
section, with their actual `sectionId` and matching playback type. Home browsing
still requires every result to belong to the requested section. Universal search uses the site-wide scope to avoid duplicate requests.

`CatalogPage.next` is an opaque token scoped to the site, section and query.
Null means the end. The UI appends as remote focus approaches the bottom, keeping
focus and existing cards. It knows nothing about page numbers. Vidbox owns its
numbered pages and 500-page limit.

### Universal search

`UniversalSearch` coordinates a query across every registered site. It searches
site-wide adapters once and section-scoped adapters once per declared section.
Each request uses its own `CatalogBrowser` and opaque continuation. Results are
deduplicated by site-qualified title identity within each site; copies hosted by
different sites stay separate. New adapters participate through their existing
search scope and sections, without Activity changes.

The native search screen uses Leanback vertical/horizontal grids: one row per
site, movies and TV together. Site order stays fixed while results arrive
independently. Navigating right appends that site's next pages. Failures remain
local to the row; retrying a partially failed site requests only failed sections.
Each row retains focus and horizontal position. Opening a result uses its owning
site for details and playback, without changing the home browsing site.

Search edits debounce for 600 ms. Editing immediately invalidates older work;
automatic result refreshes preserve keyboard focus. Explicit submission hides
the keyboard. Phone and voice search use the same universal query. A blank query
shows a prompt. Back from details restores the result card; Back from search
returns to the current home catalog. Site/section changes cancel pending work
and clear search and pages. Captured request generations prevent late responses
from replacing a newer screen or playback session.

## Hamburger menu

| Active site | Top section | Below the divider |
| --- | --- | --- |
| Vidbox | Movies; TV Shows | Kopoti; 123Movies; Cinejoy |
| Kopoti | À l'affiche; Spectacles | Vidbox; 123Movies; Cinejoy |
| 123Movies | Movies; TV Shows | Vidbox; Kopoti; Cinejoy |
| Cinejoy | Movies; TV Shows | Vidbox; Kopoti; 123Movies |

The current site is omitted from the switch list and shown in the browser header.
Only section rows receive selected highlighting. The divider cannot receive focus.
Rows reuse MediaStation's dialog resources and remote behavior. With one site,
the divider and switch section are absent.

Selecting a site refreshes the menu with that site's sections and its name in the
dialog title. Its remembered section receives initial focus, otherwise its first
section does. The active site, saved selection and catalog change only when the
user chooses a section. Back dismisses the menu without changing the catalog.
Choosing a section loads its catalog and retains the universal Continue watching row.

## Persistence and migration

Playback keys use a versioned JSON array of site, media type, title and episode
identity, avoiding delimiter collisions for slugs or paths. Continue watching
combines all registered sites and media types in recency order, while bookmarks
retain their original site identity. Selected source and section are also scoped
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
remain shared; Continue watching combines titles across sections and sites.

Sections control browsing, labels, search and continuation. `Title.type`,
`PlayableRef` and `SeriesCatalog` still control standalone versus episode
playback. Episode auto-binging uses those contracts, never section IDs or
display titles.

## Universal search verification

Core tests cover site-wide versus section-scoped queries, independent slow/failing
sites, retrying only failed sections, per-section cursors, cancellation on edits,
and duplicate titles appearing in multiple sections. The emulator D-pad test
covers loading-to-results focus, horizontal scrolling, appending without losing
focus, returning to a row, and selecting a title with its owning site identity.

Live emulator checks with “Alien” returned multiple results in Vidbox, Kopoti and
123Movies. Opening a 123Movies result while browsing Vidbox loaded its details;
Back restored the selected search card. The TV was not deployed or operated.
