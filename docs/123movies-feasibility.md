# 123Movies feasibility

This is the original investigation snapshot. The subsequent implementation is
documented in [123Movies integration](123movies.md).

Investigated 2026-09-28. **Native playback is feasible:** one movie and one TV
episode played in a temporary Media3 Android app on the TV emulator. No production
adapter has been added, and the physical TV was not accessed or deployed to.

## What was proved

| Capability | Observed result |
| --- | --- |
| Movie and TV browsing | HTML cards, artwork and page continuation |
| Search | JSON results with working limit/offset pagination |
| Details | Synopsis, artwork, year, rating and other metadata |
| Episode selection | Explicit episode buttons on individual season pages |
| Movie playback | Practical Magic (1998), Server 1: native rendered frames, advancing position and 1:44:10 duration |
| Episode playback | Reacher S2E3, Server 1: native rendered frames, advancing position and 47:28 duration |
| Hosted subtitles | Both items expose an English track; both files downloaded as valid WebVTT |

The probe used the same Media3 1.11.1 dependencies as Movies, with a separate
application ID. It did not modify Movies, its bookmarks or its UI. The request
protocol was reproduced with ordinary HTTP and standard cryptography; playback
used no WebView, browser cookies or provider JavaScript runtime.

## Catalog, pagination and search

[Movies](https://ww8.123moviesfree.net/movies/) and
[TV](https://ww8.123moviesfree.net/tv-series/) each returned 40 HTML cards per
inspected page. Artwork uses lazy image attributes. TV cards represent **seasons**,
not shows. Follow the actual Next link: current page-two URLs are
[/movies//2/](https://ww8.123moviesfree.net/movies//2/) and
[/tv-series//2/](https://ww8.123moviesfree.net/tv-series//2/), both verified.
Wrap those URLs in the existing site-scoped `PageToken` for infinite navigation.

The [search endpoint](https://ww8.123moviesfree.net/searching?q=reacher&limit=5&offset=0)
returns `data` and `meta`:

- `t`: title; `s`: full slug; `d`: movie (`m`) or season (`s`).
- `e`: episode count; `n`: season number; `q`: quality label; `y`: year.
- Metadata contains offset, total items, total pages and page number.

Offset 5 returned the next five results; limit 50 returned all 20 current matches
for Reacher. Search is broad and includes Preacher and Jack Reacher. The inspected
site script does not supply a type filter. `SearchScope.SITE` fits this endpoint;
if section filtering is chosen instead, pagination must advance through raw
results even when a page has no matches for the selected section.

Ordinary HTTPS requests succeeded. A separate web-reader client received 403;
access may differ by client or change over time.

## TV identity is the main catalog complication

| Page | Slug | Actual `#mid[data-mid]` |
| --- | --- | --- |
| [Reacher S1](https://ww8.123moviesfree.net/season/reacher-season-1-1498/) | `reacher-season-1-1498` | 1498 |
| [Reacher S2](https://ww8.123moviesfree.net/season/reacher-season-2-1630858563/) | `reacher-season-2-1630858563` | 1630858564 |
| [Reacher S3](https://ww8.123moviesfree.net/season/reacher-season-3-1630858563/) | `reacher-season-3-1630858563` | 1630858565 |

**Never derive playback identity from the numeric slug suffix.** Persist the full
season reference plus episode number, and parse `data-mid` from that season page.
The inspected pages have `ep-1` through `ep-8` buttons.

No authoritative show-level ID was found in catalog/search/detail payloads.
Search can find other seasons, but exact normalized base-title matching is only
a candidate discovery method: remakes, duplicate seasons and renamed shows need
collision handling. Related items mix seasons with unrelated recommendations.
Do not use them as a complete season list or silently auto-binge into an ambiguous
match. Labels such as the listed Reacher S4/2026 do not prove playback availability.

The playback response for Server 2 provides a stronger identity opportunity:
Practical Magic mapped to movie ID 6435, and Reacher to series ID 108978 plus
season/episode. These appear to be TMDB identifiers from the embed routing;
the catalog does not expose or identify them as such.
Using playback mapping to validate candidate seasons would add network work;
that strategy has not been implemented or validated across the catalog.

## Playback chain

Primary sources are the site's
[detail page](https://ww8.123moviesfree.net/movie/practical-magic-4743/), its
[player script](https://ww8.123moviesfree.net/js/app.min.ddb672bf594a19d1d9a30e7532cfd01a36ed642812be845064c82e4e06c1c4fc.js),
and the inline configuration in [Ployan](https://ployan.me/watch/?v21).

1. Read `data-mid`, the selected episode, and the selected server ID.
2. The browser wrapper constructs a Ployan iframe. The inner player requests
   `/get/{token}` using `mid+episode+server+epochSeconds` as its payload.
3. Its token format is hex salt, hex IV and hex ciphertext/tag, separated by
   hyphens. The inspected protocol uses PBKDF2-HMAC-SHA256 (1000 iterations,
   8-byte salt, 32-byte key) and AES-256-GCM (12-byte IV), with the public player
   protocol string `player` as the password. This is request encoding, not a
   user credential. A native implementation can reproduce it directly.
4. A `direct` response supplies an opaque `info` value for
   `https://ployan.me/hls/{info}/master.m3u8`.
5. An `embed` response contains another encoded destination. Server 2 led to
   [Embos](https://embos.top/movie/?mid=6435); the third visible server led to
   another embed host. Those complete extraction chains remain unproved.

Only isolated constant-table decoding was used to inspect obfuscated script
data during research. The full provider player/ad script was never executed by
the protocol probe. A production extractor should parse known shapes, fail on
unknown responses, and retain `HttpTransport` limits and destination validation.

### Servers and defaults

Visible labels Server 1, Server 2 and Server 3 map to protocol IDs **1, 2 and 5**.
The static header says Server 1, but the inspected initialization sets `srv=2`.
Thus the successful Server 1 probe does **not** prove support for the website's
initial default. Ployan also has a request-error fallback sequence 2 → 4 → 1 → 3
→ 5; this is not a quality ranking or proof that every source works.

Keep source discovery separate from selected-source resolution. Do not silently
substitute Server 1 for a selected Server 2. A first integration can explicitly
declare partial server support, but full source parity needs further extraction
work. Do not label an unsupported source as playable.

### Native verification and quality limits

Both successful probes used protocol server ID 1, with a full desktop browser
User-Agent, `Origin: https://ployan.me` and `Referer: https://ployan.me/` applied
to Media3 requests. This header combination works; the minimum required set was
not isolated. Segment URLs used HTTPS on `*.voxzer.org`.

- Practical Magic: H.264/AAC, 1280×534 actual video (720-class, cropped),
  duration 6,250,368 ms, rendered first frame and position advancing past 22 s.
- Reacher S2E3: 1920×960 actual video (1080-class, cropped), duration
  2,848,300 ms, rendered first frame and position advancing past 101 s.
- Reacher S1E1 returned a direct response, but its playlist request returned 502.
  This is a real upstream failure case the adapter must surface.

The successful URLs returned media playlists, not adaptive masters with multiple
variants, despite the `master.m3u8` name. There is no evidence that these are the
best streams available across other servers. The app must expose actual available
qualities and avoid inventing a 1080p option from catalog badges.

This is a short native playback proof, not full-film, seek, resume, subtitle
rendering or cross-season auto-binge validation.

## Subtitles and the existing abstraction

Ployan exposes `/sub/{encoded-item}/index.json`, with `file`, `label`, `lang` and
`default` fields. Its item encoding is the hex result of XORing each character
of `mid-episode` with the character codes of the public string `player`.
Both tested items returned English only; their VTT downloads contained cues.
No French availability was demonstrated.

The current `ResolvedPlayback` only carries an optional IMDb-based
`SubtitleContext`; it has no field for provider-hosted sidecar tracks. Supporting
these tracks cleanly needs a provider-independent sidecar subtitle model with
stable identity, language, URI, format and required headers. The shared player
should attach them through Media3 and keep existing subtitle selection/resume
semantics. Filter offered languages to French and English.

No IMDb identity appeared in catalog metadata. Server 2's apparent TMDB mapping
may allow the existing normalized IMDb lookup, but that route remains untested.
Do not pass site-local `data-mid` values to OpenSubtitles or infer audio language
from a title's country.

## Recommended implementation boundary

- `sites/123movies`: sections, HTML/JSON catalog and search, full opaque page
  identities, candidate season discovery and source labels/defaults.
- `extractors/Ployan`: fresh selected-server request, known response decoding,
  HLS headers and optional hosted subtitle metadata.
- `SeriesCatalog`: ordered verified seasons/episodes; shared next-episode logic
  remains responsible for auto-binging. Ambiguous discovery must fail explicitly.
- Shared contracts/player: only the sidecar subtitle capability, if included.
  Site-specific URL construction and parsing stay out of the Activity.
- Existing site-scoped bookmarks, search debounce, infinite navigation and menu
  composition can be reused without a provider-specific UI.

Next implementation gates are focused parser/protocol fixtures, a reliable
season identity strategy, a deliberate supported-source/default policy, and live
validation inside Movies for catalog/search, playback, subtitles and auto-binging.

## Local evidence

Temporary research captures, protocol probe, native probe project, build output,
screenshots and Media3 logs are under `/tmp/movies-123-inspect/`. Temporary
stream tokens and provider script dumps are deliberately absent from this repo.
The standalone probe APK built successfully and was installed only on
`emulator-5554`, then removed after verification. This document is the only
repository change from the investigation.
