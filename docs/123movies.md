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

**Server 1 is currently the supported/default source.** The website initially
selects Server 2; its embed chain and Server 3's chain are not implemented.
Unsupported sources are not advertised as playable, and the resolver never
silently switches servers. Some Server 1 titles may be unavailable upstream.

`PloyanExtractor` reproduces the known public request encoding with native
cryptography, requests a fresh HLS URL for the selected title/episode, and supplies
the player origin, referer and user agent to Media3. It does not execute provider
JavaScript or use a WebView. Unknown response shapes and embedded-player responses
fail explicitly. The timestamped protocol requires a correctly set device clock.

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

## Evidence

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
