# Movie and TV browsing

The catalog now matches Vidbox's `/search?type=movie` and `/search?type=tv`
defaults: `sort=popularity`, `now_playing=false`, `trending=false`, and unrestricted
filters (`all`). The former now-playing feed is no longer used.

## Native navigation

- MediaStation's hamburger button opens its existing native single-choice dialog
  for **Movies** and **TV Shows**. Search uses the selected mode.
- Results append as D-pad focus approaches the bottom of the grid. Visible cards,
  focus, and scroll position survive each append. RecyclerView reuses off-screen
  cards in both catalogs and episode grids. Results are deduplicated by ID;
  only one load runs at a time. The backend's total page count (capped at the
  website's 500 pages) determines when loading ends. There are no page controls.
- A TV show's details action is **Episodes**. Choose a season, then choose a
  landscape thumbnail with its actual episode number and name. Select the season
  heading to change seasons. Physical Back returns through episodes, details,
  and the original catalog card.
- Hidden screens do not participate in D-pad focus navigation.

## Provider data and playback

Vidbox serializes show details, including the season list, into Next.js data
chunks. `SeriesCatalog` parses those JSON payloads as data. For episode details,
the site calls TMDB's season endpoint with its public client configuration. The
app reads that configuration from the current same-site common bundle at runtime;
it does not execute the bundle or embed a captured API key in source.

Only the chosen season is fetched, including specials when the site lists them.
Nonconsecutive season/episode numbers remain intact. Empty seasons are omitted.

`PlaybackTarget` carries media type, show ID, season, and episode through Max,
Vidpro, VidRock alternatives, and French/English subtitle lookup. TV targets
require a season and episode, preventing accidental movie or episode-1 fallback.
Max TV uses `streamBase` plus the selected season, episode, and `stream_urls`;
movie playback retains its direct `api` URL. Provider and stream availability
still depend on the selected server.

## Verification

HTTP boundary tests cover catalog defaults, TV field names, pagination/search,
season parsing with large serialized payloads, nonconsecutive episode numbers,
selected-season-only fetching, Max's distinct movie/TV configurations, and
French/English searches scoped to the selected episode. Live checks confirmed
Reacher's season list, season 2's eight named episodes, and S2E3 stream resolution
on both Max and Vidpro. Native Max playback of S2E3 was verified on the visible
Android TV emulator. D-pad navigation reached the second movie catalog page,
kept the focused card at the same bounds across loading, and restored the same
second-page card after visiting details. The TV subtitle request matched the
website, but OpenSubtitles returned HTTP 503 for both Reacher S2E3 languages
during the live check; the picker reported the failure with a retry path.
