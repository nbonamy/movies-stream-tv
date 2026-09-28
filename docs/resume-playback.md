# Resume playback

Each home mode has a Continue watching row above its popular catalog. Movies use
poster cards; TV entries use episode artwork, the show title, season/episode,
and episode name. The progress bar copies MediaStation's video-card resource.
Both sections scroll together, and the row is hidden during search or when empty.

Selecting a Continue watching card opens the saved movie or episode directly.
Physical Back returns to the row after hiding player controls. Opening the same
title through its regular details or episode list also resumes automatically,
matching MediaStation's player behavior.

`PlaybackProgress` stores metadata and playback position in local SharedPreferences,
keyed by stable site ID, media type, opaque title ID and—for TV—opaque episode ID.
Keys are versioned JSON arrays, so IDs containing slashes cannot collide. Existing
Vidbox bookmarks migrate automatically without losing their saved positions. Source URLs and tokens
are never saved; each playback resolves a fresh stream. Movie and TV home lists
are separate for each site and sorted by the most recently saved playback. Progress is local
to the device; uninstalling or clearing app data removes it.

Save every five seconds and on pause, seek, player release, and activity stop.
Backgrounding pauses playback. An unresolved or failed stream cannot overwrite
an existing bookmark. The MediaStation rules apply: at most 30 seconds is not
resumable, and more than 95% or a completed player removes the bookmark. Reopening
such a title starts at the beginning.

## End of playback

A completed movie closes the player. A completed episode is removed from Continue
watching and the next episode starts at the beginning, even if it has an older
bookmark. The selected source is retained when available for the next episode.
Navigation follows the site's episode and season order, skipping empty seasons.
After the final listed episode, the player closes. Back returns to the original
home/details/episode screen even after several automatic transitions. Leaving the
player cancels pending episode lookup; a lookup failure offers Retry.

## Verification

Android instrumentation tests exercise real SharedPreferences serialization,
independent site/movie/episode keys, legacy migration, opaque IDs, site preferences,
home-list filtering, incomplete stream
metadata, completion thresholds, and malformed records. Build the app and test
APK with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, install both
on the emulator, and run:

```sh
adb -s emulator-5554 shell am instrument -w fr.bonamy.movies.test/androidx.test.runner.AndroidJUnitRunner
```

Live emulator verification additionally covers playback checkpointing, returning
to Continue watching, app restart, direct resume, and navigation between the row
and the popular catalog.

Verified on the emulator with The Odyssey (41:41) and Reacher S2E3 (9:38):
checkpoint during playback, background and restart the app, find each title only
in its own home mode, and resume directly at its saved position. The episode
reopened at 9:44 after several seconds of playback before pausing for inspection.

End-of-stream checks passed on the emulator: The Odyssey closed to its details
screen; Reacher S2E3 advanced to S2E4 from 00:00 and played; S1E8 advanced to S2E1.
Back after automatic advancement returned to the original browsing screen.
Core contract tests cover the final listed episode, empty seasons, opaque IDs,
nonconsecutive numbers and failure/cancellation propagation; the real Vidbox
adapter is also exercised through its next-episode interface with HTTP fixtures.
