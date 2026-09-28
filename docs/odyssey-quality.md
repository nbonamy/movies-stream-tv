# Odyssey quality verification — 2026-09-27

## Finding

The app is playing the highest rendition offered by **Max** for The Odyssey
(TMDB 1368337). The source itself is a poor theatrical recording; selecting its
1080p rendition cannot restore detail absent from that recording. This does not
establish that Max is the best source across every Vidbox provider.

## Three independent checks

1. **Live provider and playlists.** Ran the production `VidboxClient` resolver,
   then inspected all three stream URLs returned by Max's current API. Each
   exposes the same H.264/SDR ladder:

   | UI label | Actual dimensions | Advertised HLS bandwidth |
   | --- | --- | --- |
   | 360p | 640 × 334 | 788,238 bit/s |
   | 720p | 1280 × 666 | 2,730,374 bit/s |
   | 1080p | 1920 × 1000 | 4,469,059 bit/s |

   No higher rendition is present. Bandwidth is the manifest's advertised
   value, not a measured whole-movie average. The website also starts with its
   first stream URL and highest HLS level; its automatic mode can subsequently
   adapt. The app defaults to Media3's highest supported bitrate.

2. **Actual encoded media.** Downloaded the corresponding 5.005-second segment
   around 10:00 from each URL's highest rendition. All three files were
   788,472 bytes and byte-identical (SHA-256
   `fe5b4689ec433ec5934e82505400744961f8a526ba28e13b3843159775e8bf31`).
   FFprobe reports H.264, 1920 × 1000, 24000/1001 fps, with AAC audio.
   An independently decoded frame visibly contains burned-in Spanish and Dutch
   captions. These are part of the video, separate from the app's subtitle tracks.

3. **Physical Sony TV evidence.** Read existing diagnostics without launching,
   installing, changing settings, or sending remote input. The Movies package
   UID is 10098, its persisted built-in provider is Max, and its most recent
   completed video-decoder record (18:57:25 local time) reports
   `OMX.MTK.VIDEO.DECODER.AVC`, 1920 × 1000, nominal 24 fps, lifetime 56,560 ms.
   The corresponding process log reports a 1920 × 1008 allocated decoder surface
   (buffer alignment differs from the visible 1000-pixel picture). This is recent
   playback evidence, not a claim that the TV remained playing during inspection.

## Source quality

The live metadata filename explicitly contains:
`Not.a.Web-DL.TS.with.Spanish.and.Dutch.HC.Subs`.
That agrees with the burned-in captions in the downloaded frame. The 1080p label
correctly describes the encoded pixel dimensions, but does not imply a clean
1080p digital master. A visibly better picture would require a better original
copy from a provider, rather than a higher setting on this Max stream.

No playback code change was warranted by these checks. Other Vidbox providers
were not assessed in this verification.
