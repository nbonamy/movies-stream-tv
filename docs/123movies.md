# 123Movies

`Movies123Site` registers Movies and TV Shows alongside Vidbox and Kopoti.
It uses the website's `/movies/` and `/tv-series/` listings and follows their
Next links for infinite navigation. Search uses `/searching` with opaque offset
continuation and is site-wide, returning movies and shows with their own section.

## TV shows and identity

The upstream catalog lists seasons. The adapter groups cards by their exact
season-slug stem, so `reacher-season-2-1630858563` becomes the site-local show
`reacher`. Full season slugs remain season IDs, and episode IDs combine the full
season slug and episode number. Playback reads `data-mid` from the selected page;
the numeric slug suffix is not the content ID.

Season discovery searches the show stem and accepts only exact matching stems.
It pages through results, orders seasons numerically, excludes substring matches
such as Preacher, and rejects duplicate season numbers with different references.
Ambiguous or incomplete lookups fail explicitly rather than ending the series.
This is a provider-derived grouping, not an authoritative external show identity;
renamed or inconsistent upstream slugs can still prevent season discovery.

Episodes come from the actual season page buttons. The existing `SeriesCatalog`
next-episode implementation handles ordered navigation and season transitions.
Resume, Continue Watching, subtitle language carryover and end-of-movie behavior
use the shared application paths and site-scoped persistence.

## Playback support

The Source menu retains the implemented servers listed on the selected page,
without probing individual streams to hide currently failing choices:

| Source | Support |
| --- | --- |
| Server 1 | Default; direct Ployan HLS for movies and episodes |
| Server 2 • VidSrc | Movies; Ployan's authenticated TMDB mapping, then the VidSrc/Max native request chain |
| Server 3 | Movies; Ployan's authenticated Vidara player descriptor and native stream API |

The website initially selects Server 2. The app currently supports its VidSrc
backend; Emboser's other embedded backends are not exposed. Server 3 has protocol
ID `5`, matching the page button. The resolver never silently switches servers.
A failure for one title does not remove a server from another title's menu.

`PloyanExtractor` reproduces the known public request encoding with native
cryptography, requests a fresh HLS URL for the selected title/episode, and supplies
the player origin, referer and user agent to Media3. It does not execute provider
JavaScript or use a WebView. Unknown response shapes and unrecognized embedded
players fail explicitly. The timestamped protocol requires a correctly set device clock.

For Server 2, the TMDB movie identity comes from the authenticated player response,
never from a title's slug. The Max extractor forwards the freshly supplied API
token to its stream API and stream-token service.

Server 3 decrypts the selected Vidara descriptor, removes the wrapper's trailing
timestamp and posts its opaque file code to `/api/stream`. The JSON request uses
the Android device profile and baseline codecs. It follows the returned HLS URL
with the player's Origin, Referer and user agent. POST bodies and responses are
bounded; POST redirects are rejected. No player scripts are executed.

Quality choices reflect the actual stream's variants. Some responses are a single
media playlist, so a title's HD badge is not a guarantee of 1080p or the best copy
available on other servers.

## Subtitles

The extractor reads the episode-specific hosted subtitle index and exposes only
French and English WebVTT tracks from the expected player host and item directory.
A missing subtitle index does not prevent video playback.

The optional `ResolvedPlayback.subtitles` field is provider-independent. Media3
loads these sidecar tracks using the playback headers, and the existing subtitle
menu, persisted track selection and next-episode language restoration handle them
like other player text tracks. Switching to an online subtitle retains the hosted
tracks in the menu. No 123Movies-specific logic is added to the Android Activity.

The site does not currently provide normalized IMDb metadata to the online
subtitle search. Hosted language availability depends on the selected item;
the investigated movie and episode offered English only.

Server 3 also exposes French/English WebVTT or SRT tracks from the selected media
CDN. Tracks keep stable provider IDs and use the shared playback headers,
selection and persistence behavior. Other languages and unrelated destinations
are filtered out.

## Evidence

On 2026-11-09, You Can See Everything's Server 1 playlist returned HTTP 502.
Both additional sources played natively in the actual app on the Android TV
API 31 emulator after seeking to one minute:

| Source | Rendered frames | Advancing samples | Video width |
| --- | --- | --- | --- |
| Server 2 • VidSrc | 151 | 15 | 640 |
| Server 3 | 147 | 15 | 1920 |

Server 3 supplied an English WebVTT track. These checks do not establish full-film
availability or playback for other titles. Emulator preferences and bookmarks
were restored. Temporary live probes were removed; their captures and media URLs
are not fixtures. Fixture tests cover all listed movie-server choices, the actual
page playback ID, authenticated mappings, explicit selection without fallback,
the JSON stream request, media headers and hosted subtitle ownership/languages.

See [the feasibility investigation](123movies-feasibility.md) for the observed
request chain, catalog identity traps and initial native playback proof.
Fixture tests cover continuation, unified search, season ordering and ambiguity,
cross-season navigation, actual page IDs in encrypted playback requests,
source rejection, subtitle language filtering and subtitle destination ownership.

Native emulator checks passed inside Movies for Practical Magic playback and
Continue Watching after force-stop, including restored English subtitles visibly
rendering. A temporary live instrumentation probe also verified Reacher S2E3
playback, persisted position and English restoration, natural end after seeking
near the end advancing to S2E4 with English automatically selected, and a movie's
end closing the player. The probe source stays outside the repository; it is not
a network-dependent CI test. Cross-season ordering is covered at the adapter
boundary with fixture responses, not claimed as live playback proof.

The emulator's clock initially lagged by over seven hours. Matching that offset
in a desktop request reproduced HTTP 404; setting the emulator to the correct
time restored native requests. No physical TV was connected or deployed to.

Final validation: 29 core tests, six existing Android instrumentation tests,
Android lint and debug/test APK builds pass. Live UI checks covered both sections,
phone search returning mixed movies/shows, TV infinite navigation into page two,
Reacher's season picker and all eight season-two episode cards. Original playback
history was restored after the checks. Only the emulator received the new build.
