# Resume playback

Every home section shows the same Continue watching row above its catalog, mixing
movies and TV episodes from all registered sites, most recently watched first.
Landscape cards show movie backdrops or episode artwork, a site label, and a
progress bar copied from MediaStation. TV titles include the season/episode and
episode name. The row scrolls with the catalog and is hidden during search or when empty.

Long-press OK/Select on a card to open its menu. Remove clears only that item's
bookmark, including its saved position and subtitle selection. It stays absent
until watched again past the resume threshold. Back dismisses the menu without
removing anything. Focus moves to a neighboring card after removal, or to the
catalog when the row becomes empty.

Selecting a Continue watching card opens the saved movie or episode directly.
Physical Back returns to the row after hiding player controls. Opening the same
title through its regular details or episode list also resumes automatically,
matching MediaStation's player behavior.

`PlaybackProgress` stores metadata and playback position in local SharedPreferences,
keyed by stable site ID, media type, opaque title ID and—for TV—opaque episode ID.
Keys are versioned JSON arrays, so IDs containing slashes cannot collide. Existing
Vidbox bookmarks migrate automatically without losing their saved positions.
Source URLs and tokens are never saved; each playback resolves a fresh stream.
The universal row keeps bookmarks distinct by site; matching titles from different
sites are not merged. Selecting a card uses its original site without changing
the current browsing site or section. Progress is local
to the device; uninstalling or clearing app data removes it.

Save every five seconds and on pause, seek, player release, and activity stop.
Backgrounding pauses playback. An unresolved or failed stream cannot overwrite
an existing bookmark. Ordinary watched progress becomes resumable after 30 seconds.
More than 95% or actual completion removes a movie's bookmark. For TV, it queues
the next episode at **0:00**, including across seasons. A final listed episode
removes its bookmark without queuing another.

The selected subtitle's language carries into the queued next episode. The current
episode's file is never reused. Periodic saves in the previous episode's credits
cannot overwrite the successor. Seeking back into that episode cancels its
unstarted automatic successor and restores the current position.

If episode lookup has not finished or fails, a durable **Next episode** entry
survives player exit and app restart. Home retries the lookup; selecting the entry
also retries it. Lookup failure does not mean end of series. Late results cannot
recreate a removed entry or undo a newer playback position.

## Save for later

Long-press a catalog or search-result card and choose **Save for later**. Movies
are added at 0:00. A series uses its first listed episode in playback order,
skipping empty seasons; existing progress for that series is preserved. Episode
cards offer the same action for that specific episode. Existing saved positions
are never reset by this action.

Explicitly saved and automatically queued entries remain in Continue watching
until removed, completed or replaced by watched progress. Starting one and stopping
within the first 30 seconds keeps its 0:00 entry. After 30 seconds the usual position
and subtitle checkpoints take over. These entries do not require a media duration
or a resolved stream URL to be saved.

## End of playback

During the final 30 seconds of a TV episode, a compact **Next episode** button
appears at the bottom right and receives focus. OK starts the next episode
immediately. Back dismisses the prompt while playback continues; automatic
continuation still happens at the actual end. Seeking back outside the final
30 seconds allows the prompt to appear again when that point is reached.

The prompt waits while player controls or a picker are open and never steals
focus repeatedly. It appears only after a next episode has been found, including
across seasons. Movies, final episodes, unknown durations and failed lookups do
not show it. One prefetched metadata lookup is shared with automatic continuation;
leaving or replacing the player cancels it. Stream URLs are still resolved fresh
only when advancing. An early advance completes the previous bookmark and carries
the same source preference and subtitle language as end-of-stream continuation.

A completed movie closes the player. A completed episode is replaced in Continue
watching by its successor at 0:00, and that episode starts at the beginning, even
if it has an older bookmark. The selected source is retained when available for the next episode.
Navigation follows the site's episode and season order, skipping empty seasons.
After the final listed episode, the player closes. Back returns to the original
home/details/episode screen even after several automatic transitions. Leaving the
player cancels its playback lookup; a lookup failure offers Retry. The separate
bookmark lookup keeps the continuation durable when playback stops.

## Verification

Android instrumentation tests exercise real SharedPreferences serialization,
independent site/movie/episode keys, legacy migration, opaque IDs, site preferences,
universal ordering, targeted removal, incomplete stream
metadata, completion thresholds, zero-position entries, pending transitions,
subtitle-language carryover, stale lookup results and malformed records. Build the app and test
APK with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, install both
on the emulator, and run:

```sh
adb -s emulator-5554 shell am instrument -w fr.bonamy.movies.test/androidx.test.runner.AndroidJUnitRunner
```

Live emulator verification additionally covers playback checkpointing, returning
to Continue watching, app restart, direct resume, and navigation between the row
and the popular catalog.

Verified on the emulator with The Odyssey (41:41) and Reacher S2E3 (9:38):
checkpoint during playback, background and restart the app, find each title
in its home mode (before the universal row was introduced), and resume directly
at its saved position. The episode reopened at 9:44 after several seconds of playback before pausing for inspection.

End-of-stream checks passed on the emulator: The Odyssey closed to its details
screen; Reacher S2E3 advanced to S2E4 from 00:00 and played; S1E8 advanced to S2E1.
Back after automatic advancement returned to the original browsing screen.
Core contract tests cover the final listed episode, empty seasons, opaque IDs,
nonconsecutive numbers and failure/cancellation propagation; the real Vidbox
adapter is also exercised through its next-episode interface with HTTP fixtures.

Universal resume was verified on the emulator with a Kopoti movie and a Vidbox
TV episode in the same row, including on Kopoti's movie-only home. Reacher S2E3
resumed through Vidbox from 9:46, advanced to 10:32, and returned to the Kopoti
home with its card focused. Long-press opened Remove without starting playback;
Back restored card focus. Removal focused the neighboring card, then the catalog
after the final removal, and remained removed after restart. Original bookmarks
were restored after these checks.

The next-episode prompt was verified with native Reacher playback on the emulator:
S2E3 displayed the focused button within its final 30 seconds; remote OK started
S2E4 from the beginning. Dismissing the S2E4 prompt with Back still allowed
end-of-stream continuation into S2E5. Instrumentation tests cover the 30-second
boundary, focus, dismissal and seeking back, cancelled lookups, final episodes,
lookup reuse and retry after failure.

Save for later and near-completion continuation were verified on the emulator
through the real catalog, search and episode-card actions. A movie and Reacher's
first episode were saved at 0:00; saving Reacher again preserved existing progress.
Cinejoy's Reacher S2E3 resumed with rendered video from three minutes after reopening.
Stopping it at 96% queued S2E4 at 0:00; after reopening, that card played S2E4 from
the beginning and survived stopping within 30 seconds. Original bookmarks were
restored after verification. Persistence tests additionally cover cross-season
queues, final episodes, failed/unprepared playback, removal and seek-back races.
