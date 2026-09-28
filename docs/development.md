# Build and deploy

## Requirements

- JDK 17
- Android SDK with platform 36 and Android platform-tools (`adb`)
- Android TV 8.0 or later, or an Android TV emulator

Configure the SDK using the standard Android environment variables or ignored
`local.properties`. The Gradle wrapper is included.

```sh
make build     # Build the debug APK
make check     # Tests, Android lint and debug build
make devices   # List connected devices
make help      # All available targets
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a TV

Enable debugging on the TV and authorize the computer. Replace `TV_HOST:PORT`
with the device's ADB endpoint:

```sh
make deploy ANDROID_TV_DEVICE=TV_HOST:PORT
make run ANDROID_TV_DEVICE=TV_HOST:PORT
```

`deploy` builds and installs without launching the app. `run` restarts it.
Updates use `adb install -r` and preserve app data. These targets install a debug
build; release packaging is not configured here.

For repeated use, create a **local, ignored** `Makefile.local`:

```make
ANDROID_TV_DEVICE ?= TV_HOST:PORT
```

Keep actual device addresses in that file or environment variables. Command-line
variables override local defaults. The tracked Makefile has no default TV address;
TV commands fail with an explanation when no target is configured.

## Use an emulator

Start an Android TV emulator, then:

```sh
make deploy-emulator
```

This builds, installs and launches the app on the standard first-emulator target.
For another running emulator, use its serial from `make devices`:

```sh
make deploy-emulator ANDROID_EMULATOR_DEVICE=EMULATOR_SERIAL
```

`make install-emulator` installs without launching; `make run-emulator` restarts.

## Architecture and contributions

- [Agent/contributor workflow](../AGENTS.md)
- [Site interfaces and ownership](multi-site-design.md)
- [Kopoti](kopoti.md) · [123Movies](123movies.md)
- [TV browsing](tv-browsing.md) · [Resume](resume-playback.md) · [Subtitles](subtitles.md)
- [UI design reference](mediastation-ui.md)

Native Android views and Media3 live in `:app`. Site adapters, host extractors,
HTTP handling and subtitle discovery live in the JVM `:core` module. New sites
should plug into those interfaces while reusing the TV experience.
