# Cinejoy feasibility

Initial inspection on 2026-09-28. This document records the research before
integration; see [Cinejoy integration](cinejoy.md) for current support.

## Result

Catalog, search and episode metadata work with ordinary HTTPS requests. The
existing `StreamingSite` and `SeriesCatalog` interfaces fit those capabilities.
Subsequent [playback investigation](cinejoy-playback-protocol.md) reproduced the
encrypted protocol in Kotlin and proved Alien and Reacher S2E3 playing in Media3
through Nebula. A [standalone proof](../tools/cinejoy-proof/README.md) is available.
The subsequent main-app adapter is described in the integration document.

## Catalog and search

[Cinejoy](https://cinejoy.pk/) serves a Svelte application shell. Its
[API module](https://cinejoy.pk/_app/immutable/chunks/BJh85vmL.js) calls
`https://api.themoviedb.org/3` directly using a bundled client API key. The key
is deliberately omitted here; an integration needs an explicit configuration
decision rather than copying a captured credential into source.

| Capability | Request path | Observed result |
| --- | --- | --- |
| Movies | `/discover/movie` | Pages 1 and 2 returned 20 different items each |
| TV | `/discover/tv` | Page 1 returned series metadata |
| Search | `/search/multi?query=Reacher` | Movies, TV and people; filter people out |
| Movie details | `/movie/627` | Details returned successfully |
| TV details | `/tv/108978` | Reacher with numbered seasons |
| Episodes | `/tv/108978/season/2` | Eight ordered episodes with names and stills |

These requests returned HTTP 200 without Cinejoy cookies or a challenge. Tested
discovery queries used `language=en-US`, `sort_by=popularity.desc`, `page` and
`include_adult=false`. Results expose `page`, `total_pages` and `total_results`.
The returned total can exceed usable API pagination limits; only pages 1 and 2
were verified. Establish the actual boundary before implementing continuation.

Search is site-wide. The website helper requests page 1; pagination beyond that
was not tested. Its media results exclude people, adult items and missing posters.
Use Movies and TV Shows as the initial app sections.

Movie and TV identities are real TMDB IDs, scoped by media type. TV details
provide season IDs/numbers; season requests provide episode IDs/numbers. The
website sorts seasons ascending and excludes season 0. Reacher S2E3 is episode
`4901211`, “Picture Says a Thousand Words”. Future seasons can appear in metadata;
their presence does not establish that they are playable.

## Playback

The first-party [playback module](https://cinejoy.pk/_app/immutable/chunks/DHJgaGwg.js)
uses `https://api.wing.st`:

- [`GET /servers`](https://api.wing.st/servers) returned Nebula, Lisbon, Solara
  and Athens, all with status `ok`. Lisbon advertises `4k: true`; Lisbon's
  resolution, bitrate and playback quality were not verified. Nebula's native
  playback was verified later as described in the protocol document.
- Provider ordering respects a saved `serverOrder`; without an order it retains
  the API list order. A requested preferred provider is moved first, then the
  resolver tries providers until one yields a stream. The Movies app must retain
  its own deliberate default and explicit-source failure behavior.
- Logical movie requests carry `tmdb`, optional `imdb`, `year` and `title`.
  Series requests additionally carry `season` and `episode`.
- These logical requests go through `POST /g`, not a plain JSON endpoint. The
  client loads [`/crush.wasm`](https://api.wing.st/crush.wasm), calls its
  `seal_request` export, and decrypts the response with AES-GCM. The WASM download
  succeeded (67,222 bytes with a valid WASM signature). Later offline reference
  execution and independent Kotlin reproduction are recorded in the protocol doc;
  the Android proof does not execute WASM.
- The response parser supports HLS playlists, MP4 quality maps, captions and
  additional embed resolution. These are code observations, not resolved streams.

This is a new extractor protocol. The standalone proof implements it without a
WebView or provider JavaScript runtime and verifies a movie and episode rendering
and advancing in Media3. Existing registered extractors were not changed.

## Subtitles

The [subtitle module](https://cinejoy.pk/_app/immutable/chunks/CVt4Av4A.js) queries
`https://subs.wing.st/subtitles` with `type` and `tmdb`, plus `season` and
`episode` for TV.

[Reacher S2E3 lookup](https://subs.wing.st/subtitles?type=tv&tmdb=108978&season=2&episode=3)
returned 148 track entries, including `fr` and `en`. Entries provide `id`,
`language`, `url`, `type`, `display` and `source`. All entries in this response
declared SRT. Track downloads, synchronization and rendering were not tested.
No language filter was observed in the request; filter results locally to French
and English unless a supported request filter is established.

The current hosted-track contract is WebVTT-only. Integration would need SRT
normalization or a small format-aware shared subtitle capability. Resume and
language carryover should continue using the existing shared implementation.

## Remaining acceptance work

1. Establish deliberate supported-source/default policy beyond the proven Nebula path.
2. Harden the verified native protocol and establish a key/module rotation strategy.
3. Verify media headers, quality variants and French/English subtitle rendering.
4. Validate pagination boundaries and unavailable/future TV episodes.
5. Only then implement/register an adapter and perform normal site acceptance.

The original catalog investigation was read-only. The later playback proof used
an isolated Android emulator app. No main-app changes, physical TV deployments,
commits or pushes were performed.
