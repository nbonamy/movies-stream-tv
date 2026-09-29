# Cinejoy playback protocol investigation

Inspected on 2026-09-28. **Native playback is proven for Nebula:** a standalone
Kotlin Android app independently resolved and played a movie and a TV episode in
Media3. Cinejoy is now registered; see [Cinejoy integration](cinejoy.md) for
current support and acceptance results.

## Native playback evidence

The runnable implementation is in [tools/cinejoy-proof](../tools/cinejoy-proof/README.md).
It requests fresh streams inside Android using Kotlin/JCA. It does not use a
browser session, WebView, JavaScript runtime or WASM runtime. Provider WASM is
downloaded only as data: its inspected hash is checked before extracting the
public encryption key from its data section. Unknown module versions fail closed.

Android TV API 31 / Media3 1.11.1 verification:

| Sample | Authenticated API response | Decoded resolution | Playback evidence after starting at 60 seconds |
| --- | --- | --- | --- |
| Alien (1979), TMDB 348 | 200 | 2592 × 1080 | Position 70,783 ms; 260 rendered buffers; 11 advancing samples |
| Reacher S2E3, TMDB 108978 | 200 | 2160 × 1080 | Position 70,235 ms; 235 rendered buffers; 11 advancing samples |

Both tests received rendered-first-frame callbacks and remained actively playing
at the success threshold. PixelCopy captures from the actual video SurfaceView
were inspected: Alien's opening credits and Reacher's airport scene were visible.
Initial tests from position zero also passed for both titles. Master playlists
advertised 1080p and 720p; actual decoded widths differed from the master's stated
1920 width. The proof selects the highest supported bitrate. It does not prove 4K.

The API path is `/Nebula/movie` or `/Nebula/series` inside the encrypted JSON.
Movie payload fields were `tmdb`, `imdb`, `year` and `title`; TV additionally used
`season` and `episode`. Every value was a string. Requests used the existing
browser User-Agent, `Origin: https://cinejoy.pk` and root Referer. The same media
headers were applied to the Media3 data-source factory. This proves those headers
work; it does not establish that each one is required.

**Conclusion:** Cinejoy's current request protection does not require browser
execution for this playback path. Native implementation is demonstrated. Support
for other sources and resilience to provider protocol/key rotation remain separate
integration work.

## Primary artifacts

- [Cinejoy playback module](https://cinejoy.pk/_app/immutable/chunks/DHJgaGwg.js):
  94,139 bytes fetched during this inspection.
- [Wing request module](https://api.wing.st/crush.wasm): 67,222 bytes;
  SHA-256 `40c923580779e2a850fc5ab3f0046be565ed717883cf4547ed3a87de2450bdcf`.

Provider filenames and module contents can change. Findings below refer to these
artifacts. The JS string table was decoded as data using a small local Python
script. WABT disassembled the WASM; neither downloaded provider JS nor downloaded
provider WASM was executed locally for that analysis. The ordinary browser checks
below ran the website normally. Downloaded artifacts stayed outside the repo.
No keys, session tokens, resolved media URLs or binary captures are included here.

## What the client actually requires

The [playback module](https://cinejoy.pk/_app/immutable/chunks/DHJgaGwg.js) performs:

1. Download `/crush.wasm` and instantiate it with an empty import object.
2. Encode JSON containing `path` and `payload` as UTF-8.
3. Generate 44 random bytes with `crypto.getRandomValues`.
4. Allocate input, randomness and output buffers in WASM memory. Output capacity
   is the encoded JSON length plus 512 bytes.
5. Call `seal_request(inputPointer, inputLength, randomPointer, randomLength,
   outputPointer, outputCapacity)`.
6. Retain the returned response key, key identifier and ephemeral public key;
   send only its remaining request body to `POST /g` as
   `text/plain;charset=UTF-8`.
7. Decrypt the binary response with Web Crypto AES-GCM and parse the plaintext
   JSON envelope, which must have numeric `status` and a `data` property.

The output layout read by the JS is a 32-byte response key, one-byte key ID,
65-byte ephemeral public key, then the HTTP request body. The response consists
of a 12-byte nonce followed by ciphertext with a 16-byte authentication tag.
Response authenticated additional data is the UTF-8 protocol marker
`lumen-gate-v2`, bytes `[0, 2, keyId]`, then the ephemeral public key.

An HTTP 404 causes one retry with the WASM module reloaded; other errors propagate.
This is evidence that module/key rotation must be considered. It is not proof of
why the server chooses 404.

## What static WASM inspection establishes

The [WASM module](https://api.wing.st/crush.wasm) has **no imports and no start
section**. Its callable exports are:

| Export | Signature |
| --- | --- |
| `alloc` | `(i32 size) -> i32 pointer` |
| `dealloc` | `(i32 pointer, i32 size) -> void` |
| `seal_request` | `(i32, i32, i32, i32, i32, i32) -> i32` |

It also exports memory and data/heap boundary globals. Therefore it cannot obtain
DOM state, cookies, clocks, network responses or browser fingerprints through
import callbacks. Its observable inputs are the supplied JSON/randomness and its
bundled data. This is a bounded cryptographic transformation, as opposed to
WASM that directly participates in browser challenge callbacks.

Concrete cryptographic evidence in that module:

- Rust crate source-path strings identify `elliptic-curve`, `sec1`, `sha2`,
  `hkdf`, `hmac`, `aes-gcm`, `aes`, `ctr` and `polyval`.
- Bundled public mathematical constants match the P-256 field and group order.
- The exported sealing function hashes a protocol marker, `|ephemeral|`, the
  first 32 randomness bytes and a counter before constructing an ephemeral
  private key. It produces a 65-byte public point.
- Two calls into the same derivation helper use the literal direction labels
  `|c2s` and `|s2c`, consistent with separate request/response keys.
- Current request-body assembly appends two header bytes, the 65-byte ephemeral
  public key, a 12-byte nonce and encrypted payload. The current header bytes are
  version 2 and key ID 2; the JS preserves the returned key ID rather than
  selecting it independently.

A subsequent independent Python implementation using the `cryptography` library
reproduced the **entire 235-byte WASM output byte for byte** for a 42-byte JSON
input and fixed 44-byte randomness. The reference came from an isolated offline
WASM harness with zero imports; the reproduction executes no provider JS/WASM.
A standalone Kotlin/JCA implementation subsequently compiled on JDK 17 and
matched all 235 reference bytes as well. It uses standard EC key agreement,
HMAC and AES-GCM APIs; the research prototype calculates the ephemeral public
point with simple affine P-256 arithmetic. That public-point derivation is not
constant time. The main-app extractor replaces it with platform JCA
`KeyPairGenerator`, preserving the wire protocol; live Wing requests accepted
the generated keys.

This verifies the following algorithm for the inspected module:

1. Derive a candidate scalar with SHA-256 over `lumen-gate-v2`, `|ephemeral|`,
   the first 32 randomness bytes and a four-byte big-endian counter starting at
   zero. Retry with incremented counter if the scalar is zero or outside the
   P-256 group order.
2. Calculate its P-256 public key in SEC1 uncompressed 65-byte form. Calculate
   the ECDH shared secret using the bundled server public point. In this exact
   module its 65 bytes occupy linear-memory offset 1,052,336.
3. Derive separate 32-byte keys with HKDF-SHA-256: input key material is the
   32-byte ECDH shared secret, salt is the 65-byte ephemeral public key, and info
   is the marker followed by `|c2s` or `|s2c`.
4. Encrypt the supplied plaintext using the client-to-server key and AES-256-GCM.
   The nonce is randomness bytes 32 through 43. Additional authenticated data is
   the marker, bytes `[0, 1, keyId]`, and the ephemeral public key.
5. Assemble the request body as `[2, keyId]`, public point, nonce, and ciphertext
   including its 16-byte tag. Return the server-to-client key, key ID, public
   point and body to match the exported function's output layout.

The comparison covered response key, key ID, public point, request header, nonce,
ciphertext and authentication tag. Live HTTP acceptance and media availability
were verified separately as recorded above. Error branches and future module/key
versions still require separate treatment.

## Original proof scope

The independent reproduction demonstrates that the current sealing protocol can
be implemented with ordinary native crypto without a WebView or provider JS
runtime. It is still a standalone research prototype, not an app integration.

The original proof identified these integration tasks:

1. Expand the reference comparison to multiple plaintext lengths/randomness
   inputs and verify the same result in the intended Kotlin implementation.
2. Establish a maintainable public-key/protocol rotation strategy; the proof
   deliberately accepts only the inspected module version.
3. Implement the remaining chosen sources, subtitle normalization and normal
   adapter acceptance. Probe other environments if claiming wider availability.
4. Replace the proof's affine EC arithmetic with reviewed constant-time code and
   move the verified protocol behind the core extractor boundary. Preserve bounded
   HTTP, destination checks, cancellation and explicit-source failure behavior.

The isolated proof established native playback feasibility. The subsequent
[integration](cinejoy.md) uses platform key generation, bounded shared HTTP and
the normal site interfaces. Provider stability remains an upstream dependency.

## Browser observations

Normal Safari navigation, without a login or a CAPTCHA, reached both watch pages:

| Title | Watch route | Observed result |
| --- | --- | --- |
| Alien (1979) | [/watch/movie/348](https://cinejoy.pk/watch/movie/348) | Duration 1:56:36; position advanced from zero to 9.88 seconds; rendered studio intro visible. |
| Reacher S2E3 | [/watch/tv/108978/2/3](https://cinejoy.pk/watch/tv/108978/2/3) | Correct episode title, duration 47:28; position advanced from zero to 17.61 seconds. |

Catalog and Play clicks repeatedly opened advertising tabs. Direct watch routes
were established from the site's route manifest; the player's Space shortcut
started playback. Advertising tabs and the investigation tab were closed afterward.

Opening Safari Web Inspector paused execution at a `debugger` statement in
`_app/immutable/nodes/0.ZV0KQenn.js`. This is a separate page script from the
import-free request WASM. The inspection did not establish the actual selected
server, media URL, stream headers or quality. Browser progress therefore supports
upstream availability, not native extraction or best-quality playback.

The public [server endpoint](https://api.wing.st/servers) returned Nebula, Lisbon,
Solara and Athens, each reporting `ok`; only Lisbon advertised `4k: true`. This is
server metadata, not a verified stream resolution. A bare curl request returned
200, as did requests adding only a browser User-Agent, Origin or Referer. Python
urllib's default request returned 403. These results do not isolate whether the
difference is the User-Agent, TLS client or another request characteristic; they
do not establish what the encrypted `/g` endpoint requires.

The browser investigation made no app changes. The subsequent native proof used
a separate emulator-only package; no physical TV was deployed or operated.
