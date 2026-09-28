# Movies for Android TV

A native Android TV browser for Vidbox movies and TV shows, with popular catalogs, search, seasons, episodes, and fullscreen playback. The UI reuses MediaStation's Android TV layouts, drawable selectors, typography, and dialog implementation. See [UI provenance](docs/mediastation-ui.md).

## Remote

- Use the **hamburger menu** to switch between supported **Movies** and **TV Shows** modes and other registered sites. Search stays within the selected site and mode; edits are debounced without dismissing the keyboard.
- Open **Search** and scan the QR code to search from your phone. Use the same network and keep Movies Stream open on the TV. The phone mini site sends your query to the selected site and its current **Movies** or **TV Shows** mode; results appear on the TV. The address beside the QR code also works in a browser.
- Keep navigating **Down** to load more titles automatically; there are no page buttons.
- **Continue watching** appears above the popular catalog in each mode, with separate movie and TV episode lists for each site. Select a card to resume directly; reopening a title from its details or episode list also restores its position.
- Select a movie poster, then **Watch**. For TV shows, select **Episodes**, choose a season, then an episode thumbnail.
- Press physical **Back** to close a picker, then hide playback controls, then leave the player.
- Press **Up** during playback to focus **Subtitles**. Move **Right** through **Quality** and **Source**. Focus is indicated by MediaStation's white underline.
- Open **Source** to switch servers. The app starts on Vidbox's **Max** default and remembers the selected available source separately for each site.
- Open **Subtitles** to choose an in-stream track or search **French and English** subtitles online. Select a release to download it, or choose **Off**. See [subtitle discovery](docs/subtitles.md).
- Playback starts at the highest supported bitrate. **Quality** lists the available resolutions and **Auto**; switching preserves playback position.
- Use the native Media3 controls to pause and seek.

## Architecture

- `:core` defines the `StreamingSite` and optional `SeriesCatalog` interfaces. Site adapters own catalogs, search, details, seasons/episodes and source policy. Vidbox is currently the only registered site. See [multi-site architecture](docs/multi-site-design.md) and [TV browsing](docs/tv-browsing.md).
- Separate Max, Vidpro and VidRock extractors resolve fresh streams with required request headers. Shared HTTP and French/English subtitle lookup stay independent of site parsing.
- `:app` renders native Android views and plays HLS/MP4 with Media3 ExoPlayer. Register additional sites in `AppServices.sites`; menus follow their capabilities.
- No browser component or provider JavaScript is included in the app.

## Build

Run `./gradlew check :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Playback progress stays on this device and survives app restarts and updates. It is saved every five seconds and on pause, seek, exit, or backgrounding. Like MediaStation, playback becomes resumable after 30 seconds and leaves Continue watching after 95% or completion. Source changes retain the same title's progress. See [resume playback](docs/resume-playback.md).
