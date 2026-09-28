<p align="center">
  <img src="branding/movies-banner.svg" alt="Movies" width="320">
</p>

# Movies Stream

**Movies, series, and spectacles. Made for your TV.**

Browse Vidbox and Kopoti from a native Android TV app. Find something to watch, settle in, and pick up where you left off—all with your TV remote.

## Your next watch, a few clicks away

- **Browse from the couch.** Explore posters, title details, seasons, and episodes with navigation built for a TV remote. Keep scrolling to discover more.
- **Search with your phone.** Scan the QR code on your TV and type on your phone. Results appear on the big screen.
- **Pick up where you left off.** Continue watching brings unfinished movies and episodes back to the top of each section, with your playback position saved on the device.
- **Keep the series going.** The next episode starts automatically, including across seasons.
- **Watch your way.** Choose a source, adjust quality, and select subtitles without leaving the player. Search online for French and English subtitles; the next episode follows your previous subtitle language.

## Two catalogs, one remote

| Site | What you can browse |
| --- | --- |
| **Vidbox** | Movies and TV shows, with seasons and episodes |
| **Kopoti** | Films in **À l’affiche** and **Spectacles**, plus search across its catalog |

Switch sites and sections from the menu. Each site remembers your selected source and keeps its own Continue watching history. Catalogs and playable sources depend on what each site currently provides.

## Start watching

Once installed, open **Movies Stream** on your TV:

1. Choose a site and section from the menu.
2. Browse the catalog or open **Search**. To type from your phone, scan the QR code while both devices are on the same network and keep the app open on the TV.
3. Select a movie and choose **Watch**, or open a show's **Episodes** and pick an episode.

During playback, press **Up** to reach **Subtitles**, then **Right** for **Quality** and **Source**. Use **Back** to dismiss a picker, hide the controls, or return to browsing.

[Read the remote and playback guide →](docs/user-guide.md)

## Install on your Android TV

Build and sideload the app using **JDK 17**, the **Android SDK with platform 36**, and **ADB**. The app requires **Android TV 8.0 or later** and an internet connection. Enable debugging on your TV and authorize your computer before installing.

```sh
# Build and install; replace the address with your TV's ADB address
make deploy ANDROID_TV_DEVICE=192.168.1.10:5555

# Launch the installed app
make run ANDROID_TV_DEVICE=192.168.1.10:5555
```

The current workflow installs a debug APK. Updates preserve app data, including playback progress. To build the APK without installing it, run `make build`; the output is `app/build/outputs/apk/debug/app-debug.apk`.

For a running Android TV emulator:

```sh
make deploy-emulator
```

This installs and launches on `emulator-5554`. Override `ANDROID_EMULATOR_DEVICE` for another emulator. Use `make devices` to list ADB devices and `make help` for all commands.

## For developers

Movies Stream uses native Android views and Media3 ExoPlayer for HLS/MP4 playback. The `:core` module owns site adapters, stream extraction, and subtitle discovery; `:app` owns the TV experience. The app includes no browser component and does not execute provider JavaScript.

Run `make check` for tests, lint, and a debug build.

- [Multi-site architecture](docs/multi-site-design.md) · [Kopoti integration](docs/kopoti.md)
- [TV browsing](docs/tv-browsing.md) · [Resume playback](docs/resume-playback.md) · [Subtitles](docs/subtitles.md)
- [MediaStation UI provenance](docs/mediastation-ui.md)
