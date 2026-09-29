# Cinejoy native playback proof

A separate Android research app that resolves and plays Cinejoy's Nebula source
using Kotlin/JCA and Media3. The main app now has a separate
[Cinejoy integration](../../docs/cinejoy.md); this project preserves the original
standalone playback proof.

The app has its own package and does not touch Movies Stream's settings or
bookmarks. It never launches a browser, executes provider JavaScript/WASM, or
imports browser cookies. The downloaded WASM is read only as a data container for
its public encryption key after checking the exact inspected SHA-256.

## Run

Use the repository's normal JDK/Android SDK setup and a running Android TV
emulator. Pass its exact serial; the script rejects physical-device targets.

```sh
./tools/cinejoy-proof/run.sh emulator-5554 movie   # Alien (1979)
./tools/cinejoy-proof/run.sh emulator-5554 series  # Reacher S2E3
```

Each run builds and installs only `fr.bonamy.movies.cinejoyproof`, requests a fresh
stream, starts at one minute and waits for at least ten seconds of playback.
PASS requires a rendered-first-frame callback, at least 100 rendered video
buffers, eight advancing position samples and active playback. The app then
captures its actual video SurfaceView and pauses. Sanitized results and images
are copied into ignored `build/evidence/`.

The script expects `ANDROID_HOME` or `adb` on PATH; `ADB` can name another adb
executable. Keep the emulator clock current: stale time can invalidate TLS
responses. No TLS checks are disabled.

## Scope and limits

- This proves the current protocol and Nebula movie/episode playback. Other
  sources, subtitles and production catalog/search integration are not covered.
- The inspected module hash, public-key data offset and key ID are pinned. An
  upstream module change fails closed and needs review. This is not an automatic
  key-rotation implementation.
- Ephemeral public-point derivation uses straightforward affine P-256 arithmetic
  for the research proof. The main-app integration uses platform JCA key
  generation instead. Encryption, authentication, key agreement and
  HKDF use JCA primitives.
- `HttpTransport.kt` was copied byte-for-byte from core, then extended locally
  with bounded POST bodies and rejection of POST redirects. The main app's
  transport was unchanged at the time of the proof; the integration now adds its
  own bounded POST method. This copy preserves the isolated experiment.
- No provider binaries, captured responses, session keys or media URLs are
  checked in. All stream resolution happens fresh inside Android.

See [protocol and evidence](../../docs/cinejoy-playback-protocol.md).
