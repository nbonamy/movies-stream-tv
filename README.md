# Movies Stream

Native Android TV app for browsing and watching movies, TV shows and spectacles
from Vidbox, Kopoti and 123Movies.

## Features

- **Catalog browsing** — poster galleries, title details, ratings and infinite scrolling.
- **Universal search** — search all sites at once, with a horizontal results row for each site. Type on the TV or scan a QR code and search from a phone on the same network.
- **TV shows** — season selection and episode browsing with thumbnails where available.
- **Resume playback** — Continue Watching lists for movies and TV shows, with progress saved separately for each site.
- **Automatic next episode** — continues into the next season when available; the player closes at the end of a movie or the final listed episode.
- **Subtitles** — available stream tracks, hosted subtitles and online French/English search where supported. Remembers the selected track and carries its language into the next episode.
- **Quality and source selection** — starts at the highest supported bitrate and allows switching available resolutions or servers while retaining playback position.
- **Remote navigation** — D-pad focus, physical Back navigation and native fullscreen playback without a WebView or provider pop-ups.

## Screenshots

| Catalog | Title details |
| --- | --- |
| ![TV show catalog](docs/screenshots/catalog.png) | ![Reacher title details](docs/screenshots/title-details.png) |
| **Episodes** | **Player** |
| ![Reacher season two episodes](docs/screenshots/episodes.png) | ![Fullscreen player and playback controls](docs/screenshots/player.png) |

Screenshots are from the Android TV app. Artwork and metadata come from the selected sites.

## Supported sites

| Site | Sections | Playback support |
| --- | --- | --- |
| Vidbox | Movies, TV Shows | Multiple supported sources |
| Kopoti | À l'affiche, Spectacles | ShareCloudy |
| 123Movies | Movies, TV Shows | Server 1 |

Use the hamburger menu to switch sites and sections. Each site remembers its
selected source and keeps its own playback history. Catalogs, working sources,
quality and subtitle availability depend on the site and title. The app does
not host video content.

## Usage

Select a movie and choose **Watch**, or open **Episodes** for a TV show. Keep
navigating down to load more titles. Unfinished items appear in **Continue Watching**.

During playback, press **Up** to focus **Subtitles**, then **Right** for **Quality**
and **Source**. **Back** dismisses a picker, hides the controls, then exits the
player. Playback progress and subtitle choices survive app restarts and updates.

See the [remote and playback guide](docs/user-guide.md) for details.

## Build and install

Requires **Android TV 8.0 or later**. Building requires **JDK 17**, **Android SDK 36**
and **ADB**.

```sh
make build
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Enable debugging on the TV and authorize the computer, then replace `TV_HOST:PORT`
with its ADB endpoint:

```sh
make deploy ANDROID_TV_DEVICE=TV_HOST:PORT
make run ANDROID_TV_DEVICE=TV_HOST:PORT
```

`deploy` builds and installs without launching; `run` restarts the app.
For a running Android TV emulator, use `make deploy-emulator`.
See [build and deployment](docs/development.md) for SDK configuration and local overrides.

## Development

- `app/` — native Android TV screens, Media3 playback and local playback history.
- `core/` — site adapters, catalog/search models, stream extraction and subtitle discovery.
- `docs/` — usage, architecture and site integration notes.

```sh
make check  # Tests, Android lint and debug build
```

Read [AGENTS.md](AGENTS.md) before changing the app or adding a site.
The [multi-site architecture](docs/multi-site-design.md) describes the shared
interfaces; [Kopoti](docs/kopoti.md) and [123Movies](docs/123movies.md) document
individual integrations.

The bundled Lato font is distributed under the [SIL Open Font License](licenses/Lato-OFL.txt).
