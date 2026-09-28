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
  choice. The app does not reproduce Max's automatic filename ranking or enable
  an online subtitle without a selection.
- Only the selected file is downloaded. Gzip and the declared text encoding are
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
