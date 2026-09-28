# Movies for Android TV

A native Android TV browser for Vidbox movies and TV shows, with popular catalogs, search, seasons, episodes, and fullscreen playback. The UI reuses MediaStation's Android TV layouts, drawable selectors, typography, and dialog implementation. See [UI provenance](docs/mediastation-ui.md).

## Remote

- Use the **hamburger menu** to switch between **Movies** and **TV Shows**. Search stays within that mode.
- Keep navigating **Down** to load more titles automatically; there are no page buttons.
- Select a movie poster, then **Watch**. For TV shows, select **Episodes**, choose a season, then an episode thumbnail.
- Press physical **Back** to close a picker, then hide playback controls, then leave the player.
- Press **Up** during playback to focus **Subtitles**. Move **Right** through **Quality** and **Source**. Focus is indicated by MediaStation's white underline.
- Open **Source** to switch servers. The app starts on Vidbox's **Max** default and remembers a selected built-in provider across movies.
- Open **Subtitles** to choose an in-stream track or search **French and English** subtitles online. Select a release to download it, or choose **Off**. See [subtitle discovery](docs/subtitles.md).
- Playback starts at the highest supported bitrate. **Quality** lists the available resolutions and **Auto**; switching preserves playback position.
- Use the native Media3 controls to pause and seek.

## Architecture

- `:core` calls Vidbox's popular catalog and search API and reads its season/episode metadata. See [TV browsing](docs/tv-browsing.md).
- `:core` resolves Vidbox's default Max source, Vidpro, and available VidRock alternatives into fresh HLS playlists with the required request headers.
- `:app` renders native Android views and plays HLS with Media3 ExoPlayer.
- No browser component or provider JavaScript is included in the app.

## Build

Run `./gradlew check :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
