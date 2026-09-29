# Cinejoy integration

Cinejoy is registered as `cinejoy` with **Movies** and **TV Shows**. The adapter
uses the shared catalog, universal search, details, episode browsing, player,
Continue Watching and subtitle selection. Site code lives in `sites/cinejoy`;
Wing playback lives in `extractors/WingExtractor.kt` and `WingCrypto.kt`.

Inspected and integrated on 2026-09-29. Earlier evidence is retained in
[catalog research](cinejoy-feasibility.md) and [playback protocol](cinejoy-playback-protocol.md).

## Catalog and identity

The website's client reads TMDB directly. The adapter reads the public client
configuration from the reviewed Cinejoy bundle as data, checks its hash and
keeps the decoded client key in memory. It does not embed a captured API key or
execute provider JavaScript. A changed or removed bundle requires an adapter
update; it fails with a configuration error.

| Capability | TMDB path | Behavior |
| --- | --- | --- |
| Movies / TV | `/discover/movie`, `/discover/tv` | Popularity descending, English metadata, adult items excluded |
| Search | `/search/multi` | Site-wide; movies and TV share one universal-search row; people excluded |
| Details | `/movie/{id}`, `/tv/{id}` | Artwork, title, year, rating and synopsis |
| Seasons | `/tv/{id}` | Ascending season number; specials and unaired/undated seasons excluded |
| Episodes | `/tv/{id}/season/{number}` | Ascending episode number; unaired/undated episodes excluded |

Items without posters are excluded, matching the inspected site. Continuation
uses the returned page and total, capped at TMDB's verified 500-page boundary.
A filtered empty page can still have a continuation. Page 500 returned results;
page 501 returned HTTP 400. Cursors are scoped to site, section and query.

TMDB title, season and episode IDs are preserved and scoped to Cinejoy. Season
responses must match their requested identity. Playback rechecks the episode ID
and season/episode numbers. Shared `nextEpisode` follows those ordered lists,
including season transitions. Unavailable streams remain playback errors; they
do not cause episodes to be skipped silently.

## Sources and native playback

`GET https://api.wing.st/servers` supplies the source list. The adapter exposes
Nebula, Lisbon, Solara and Athens when offered. **Nebula is the default**; the
shared site preference remembers explicit changes. Resolving a selected source
never silently tries another. Athens returned no stream for the sampled Alien
request; a server's presence does not guarantee a particular title is available.

The extractor sends the selected movie/series request through Wing's binary
`POST /g` protocol. Platform JCA provides P-256 key generation/ECDH, HKDF-SHA-256
and AES-GCM. The current `crush.wasm` is downloaded as data, hash-checked and
parsed only for its public key. There is no WebView, JavaScript or WASM runtime.
The protocol document records the reviewed wire format and module hash.

Protection metadata is cached for one hour. HTTP 404 reloads it and retries once,
as observed in the website. Unknown module versions require an app update; the
adapter does not guess a rotated protocol. Encrypted POST redirects are rejected.
All adapter requests use the shared bounded, cancellable HTTP transport.

Supported responses contain a direct HTTPS HLS or MP4 `playlist` field. HLS is
validated before handoff. MP4 quality maps and further embed chains are not
implemented. Media3 receives the site's Origin, root Referer and browser User-Agent
for playlists, keys, segments and hosted subtitles. Existing highest-supported-
bitrate selection and the Quality menu apply. A source's advertised 4K capability
is not a guarantee that a title has a 4K stream.

## Subtitles and resume

Wing's `subs.wing.st/subtitles` endpoint receives the TMDB title ID and, for TV,
the season and episode numbers. Results are filtered to French and English.
Hosted SRT and WebVTT tracks declare their format to Media3. Track IDs derive
from title/episode identity, language, source and release label, without storing
transient media URLs or tokens in bookmarks.

The existing player remembers the selected track and restores it on resume.
Auto-binging carries its language to a fresh track for the next episode. Verified
IMDb metadata also enables the shared online subtitle search. Optional subtitle
lookup failures do not stop video; availability and synchronization vary by release.

## Verification

- Core fixtures cover mixed search results, filtering, cursor ownership,
  continuation through an empty page, ordered season/episode IDs and mismatched
  season rejection.
- The crypto test uses an independent server key to authenticate/decrypt the
  client's envelope, encrypt its response and reject a modified authentication tag.
- HTTP tests cover binary POST bytes, bounds and redirect rejection.
- Live adapter checks returned two pages for both catalogs, 18 movie/TV results
  for “Alien”, four sources, eight Reacher season-two episodes and a transition
  from season two's last episode to season three's first.
- Native platform-generated keys were accepted for Alien and Reacher S2E3;
  hosted French SRT downloaded successfully.
- `make check` passed. See the emulator acceptance results below for the app flow.

### Main-app emulator acceptance

Android TV API 31 checks verified Cinejoy menu selection, a catalog append,
universal-search results opening the correct details, Alien native playback
and reopening at the saved position. Reacher season two displayed ordered
episode cards; S2E3 played at 2160 × 1080 with rendered French SRT captions.
Reopening restored its position and selected track. The Next episode action
started S2E4 and selected French automatically. The shared subtitle picker also
received a cancellation guard after a live lookup race reopened an error dialog
following hosted-track selection; the rerun passed.

Nebula is the source verified in Media3. Lisbon and Solara returned authenticated
HLS responses in live protocol checks, but their Media3 playback has not been
verified. Athens returned an explicit no-stream result for the sampled movie.
A repeated Alien backward-seek check hit an Android emulator AAC decoder error
(`c2.android.aac.decoder`); initial playback and movie resume passed separately.
That decoder failure is not claimed fixed.

Temporary live probes are removed after verification. Original emulator
preferences and bookmarks are restored. No physical-TV testing was performed.
