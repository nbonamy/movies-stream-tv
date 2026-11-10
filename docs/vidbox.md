# Vidbox

Vidbox provides Movies and TV Shows, section-scoped search, numbered catalog
pages and TMDB title identities. The app uses `vidbox.vc` for its catalog; that
origin currently redirects to `vidbox.wtf`. The separate `vidbox.cx` website
offers additional playback hosts. Its movie embeds use the same TMDB identities;
its film-page slugs are not app playback IDs.

## Sources

Max is the default. Vidpro and discovered VidRock HLS mirrors remain available.
For movies, **Nebula** adds the mirrors from `play.xpass.top`, also listed as
Nebula on `vidbox.cx`. Each named mirror appears in the player's **Source** menu.
The app remembers an available selected source for each site.

All mirrors in Nebula's authenticated list remain selectable, including TOK 1,
VIP 1, LUL 1, ARA 1, MOL 1/2, VXR 1 and VRK 1. A failed stream for one title does
not hide that mirror for other titles. An explicit source failure does not select
a different mirror. Source discovery can omit an unavailable Nebula configuration
while retaining the existing sources. Nebula TV playback is not advertised.

Max reads the fresh `apiToken` from the selected player's `window.CONFIG` and
forwards it as `api_token` to both the stream API and the stream host's
`generate.php`. The server binds these requests to the viewer's network range;
the stream API token is single-use. Tokens remain in memory and are never saved
in bookmarks or fixtures.

## Nebula movie protocol

The inspected requests for You Can See Everything (TMDB `1765072`) are:

1. `GET https://play.xpass.top/e/movie/1765072` returns a signed `dataUrl` and
   the versioned `/static/mainmini.js` loader.
2. Known string literals in that loader identify its `/static/` player script.
   Known string literals in the player script supply a public `spv3-build-…`
   context. The app reads these values as data; it executes no provider script.
3. `GET /data/movie/1765072?…` returns URL-safe Base64 containing a 12-byte nonce
   followed by AES-GCM ciphertext and its authentication tag. The 256-bit key
   is SHA-256 of `spv3-data-response|<context>|<data-path>|<signed-token>`.
4. The authenticated JSON lists named mirrors with transient endpoints. The
   app follows only the selected mirror's endpoint and reads the HLS
   URL from `playlist[0].sources`. Only same-origin configuration and mirror
   endpoints are accepted; media destinations pass the shared HTTP checks.
5. Playback requests carry the embed-page Referer, player Origin and app user
   agent for playlists, keys and segments. Resolution fetches fresh signed
   endpoints each time; bookmarks and source preferences contain no tokens or
   media URLs. Source preferences use stable mirror names rather than the
   provider's rotating mirror IDs; `xpass:vip` identifies VIP 1.

The loader revision scopes the cached public context. Missing or ambiguous
configuration, authentication failures and invalid HLS responses fail explicitly.

## Validation

On 2026-11-09, the website's film page and the app catalog both identified
You Can See Everything as TMDB `1765072`. Native Max resolution failed with
HTTP 403 from `data.vidsrc.sh`; Vidpro returned HTTP 404 from `vixsrc.to`.
Max's missing API-token forwarding was corrected; it subsequently resolved and
played the film. Nebula's other mirrors were checked individually:

| Mirror | Result for this movie |
| --- | --- |
| TOK 1 | Native playback, 139 rendered frames, 15 advancing samples, 1920-pixel width |
| VIP 1 | Native playback, 152 rendered frames, 15 advancing samples, 640-pixel width |
| LUL 1 | Native playback, 148 rendered frames, 15 advancing samples, 1280-pixel width |
| ARA 1 | HLS resolved; native media requests failed with an HTTP error |
| MOL 1 / MOL 2 | Selected mirror requests timed out |
| VXR 1 / VRK 1 | Selected mirror requests returned HTTP 404 |

These results describe this film at the time of the check, not availability for
other titles. Every listed mirror stays selectable. The emulator clock was
synchronized before the final TOK check; no TLS checks were disabled.

Fixture tests cover the native request chain, every listed mirror (including new
names), explicit source routing, fresh signed URLs, stable source identity,
response authentication, missing selected mirrors, rejected off-origin
configuration/redirects and movie-only support. Max fixtures check API-token
forwarding for both movie and episode requests.

All native checks used the actual app and its normal source-discovery/resolution
path on the Android TV API 31 emulator, seeking to one minute. The repaired Max
path recorded 144 rendered frames, 15 advancing samples and 640-pixel video width.
Emulator preferences and bookmarks were restored; temporary live probes were removed.
