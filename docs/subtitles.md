# Subtitle discovery

Inspected Max's current player on 2026-09-27, starting from Vidbox's default provider.
The behavior below belongs to Max; other Vidbox servers can implement subtitles differently.

## Website behavior

The player [subtitle script](https://cloudorchestranova.com/embed/iframe_player/assets/subtitles.js?v=1786492668)
uses the movie's IMDb ID from its configuration or metadata response. It checks
video-supplied `default_subs`, then searches OpenSubtitles using a language code:

```text
GET https://rest.opensubtitles.org/search/imdbid-{IMDb number}/sublanguageid-{language}
X-User-Agent: trailers.to-UA
```

The website supports many languages and remembers a language preference. It ranks
online matches against the video's release filename (title, year, source,
resolution, codec, release group), using download counts as another signal. It
can select a sufficiently good match automatically. Download links return gzip
subtitle files; the website sends them to its own subtitle cache for conversion
to WebVTT.

For The Odyssey (IMDb `tt33764258`), the live lookup returned three French results
and one English result. A French download successfully decoded as SRT.

## Native app behavior

- Online lookup is restricted to **French (`fre`) and English (`eng`)**, including
  filtering the returned rows. In-stream subtitle tracks remain available.
- Site adapters supply normalized IMDb/episode metadata to shared subtitle lookup.
  Max returns its already-fetched metadata; Vidbox owns the lazy metadata fallback
  for its other sources. A new site may supply its own metadata or leave online
  lookup unavailable. Site-local IDs are never treated as universal IMDb/TMDB IDs.
- Results are grouped French then English and ordered by download count within
  each language. Release filenames are displayed for manual synchronization
  choice. The app does not reproduce Max's automatic filename ranking. Automatic
  episode continuity uses the previous language and the existing download-count ordering.
- Manual selection downloads only the chosen file. Gzip and the declared text encoding are
  decoded locally, then UTF-8 SRT or WebVTT is attached to Media3. No provider
  upload, browser component, or JavaScript execution is involved.
- Changing subtitles preserves playback position, play/pause state, and track
  selection parameters. **Off** disables text tracks.
- Search/download requests have deadlines and response-size limits, including
  decompressed size. Download destinations and redirects are restricted to
  OpenSubtitles hosts. One failed language does not discard the other language's
  results; reopening the picker retries failed searches.
- Leaving playback cancels subtitle work; a dismissed download dialog cannot
  apply a late result to the player.

Verification: core HTTP boundary tests cover the two-language request contract,
partial failure, download-on-selection, gzip/charset decoding, and rejection of
invalid, oversized, or redirected downloads. On the visible Android TV emulator, verified the live three-French/one-English
picker, French caption rendering, Off removing the caption at the same paused
position, and switching to an English caption with the English row selected.
`./gradlew check :app:lintDebug :app:assembleDebug` passed (six core tests).

## Resume and episode continuity

- In-progress bookmarks store the selected online subtitle identity and download
  metadata, or the embedded track's language, ID, label and roles. Off is explicit.
  Old bookmarks remain readable without a subtitle choice. Selection changes while
  paused are saved even when position and duration have not changed.
- Resume restores the same online file from cache, or downloads it again. If its
  link fails, lookup refreshes that exact subtitle ID; it does not silently replace
  the chosen release. Embedded tracks are matched by identity, then language if
  the provider changed its track IDs.
- Auto-binging carries only the language to the next episode, including across
  seasons. It first selects an available in-stream track in that language, otherwise
  searches the next episode's identity for that language only (French or English).
  It tries up to three results in download-count order. The prior episode's file
  is never reused. Off carries forward as Off.
- Automatic work opens no dialogs and preserves play/pause and position when the
  subtitle is attached. An unavailable subtitle never stops the video. Download
  count is not a guarantee of synchronization; the manual picker remains available.
- Changing sources retains the current choice. Opening the subtitle picker,
  selecting another subtitle, leaving playback or changing episodes cancels stale
  automatic work. This is shared app behavior, independent of the site adapter.

Validation of continuity: core tests cover carrying language without the old file
and a language-only lookup scoped to the next season/episode. Android persistence
tests cover exact online/embedded identities, Off, changes at a paused position,
site/episode isolation, completion, and unknown subtitle records. On the emulator,
Reacher S2E3 restored French captions after a force-stop/relaunch; seeking to its
end advanced to S2E4 and automatically selected/rendered S2E4's French subtitle.
The original emulator bookmarks were restored after validation.

## Provider-hosted tracks

`ResolvedPlayback.subtitles` carries a stable ID, URL, language, label and
`SubtitleFormat` (WebVTT by default, or SRT). Media3 receives the corresponding
MIME type and playback headers. The shared track picker, exact-track resume and
next-episode language selection apply to both formats.

Cinejoy supplies French and English tracks from Wing's episode-scoped subtitle
endpoint. These use the same hosted-track path as 123Movies, with SRT declared
explicitly. The verified IMDb identity also enables the existing online search.
