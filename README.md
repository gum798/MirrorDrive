# MirrorDrive

An Android app that receives an iPhone's screen over the local network using
Apple's legacy **AirPlay mirroring** protocol and displays it on the Android
device — either fullscreen with aspect-ratio letterboxing, or in a movable,
resizable floating window over other apps.

MirrorDrive is a **view-only receiver**: the iPhone mirrors its whole screen to
the app over Wi-Fi (or a shared hotspot), and the app decodes and renders it.
There is no touch-back channel — you control everything from the iPhone.

- **Runs on:** Android 10+ (`minSdk 29`); built and tested on a Samsung Galaxy Tab.
- **Intended for:** CarPlay-style Android "AI boxes" and tablets used as a
  secondary dashboard/entertainment screen.
- **ABIs:** `arm64-v8a` (primary) and `armeabi-v7a`.

## Status

Working:

- H.264 video mirroring over the local network.
- Aspect-ratio-correct letterboxing (no stretching).
- Floating overlay window (movable/resizable) in addition to fullscreen.
- Night-dashboard UI (waiting screen, signal ripple, mode controls).

Implemented, pending on-device verification:

- Audio (AAC-ELD) decode and playback.

Out of scope for now: touch-back control, DRM-protected app content (Netflix,
Disney+, etc. mirror as black frames — an iOS compositor limitation, not
fixable), HEVC/4K, and any form of product/store distribution.

## How it works

```
[ iPhone ] ──AirPlay over local Wi-Fi / hotspot──> [ MirrorDrive on Android ]

  Kotlin app layer
    - Foreground service (receiver lifecycle)
    - jmDNS advertises _airplay._tcp and _raop._tcp (mDNS/Bonjour discovery)
    - MediaCodec video decode -> SurfaceView (fullscreen or floating overlay)
    - MediaCodec/fdk-aac audio decode -> AudioTrack
        │  JNI
  Native core (ported UxPlay lib/, C)
    - RTSP/HTTP control server
    - Legacy pairing + FairPlay v3 handshake
    - AES decrypt of the H.264 video (TCP) and audio (UDP) streams
```

1. The iPhone discovers the app via **mDNS/Bonjour** — jmDNS (Kotlin) advertises
   `_airplay._tcp` and `_raop._tcp` on the local network.
2. The devices **pair** and complete Apple's legacy **FairPlay** handshake.
3. The iPhone streams **H.264** video (and AAC-ELD audio) over the local net.
4. The **native core** (a vendored port of UxPlay's `lib/`) handles the protocol,
   plist parsing, and stream decryption, then hands decoded/decrypted Annex-B
   H.264 access units and compressed AAC-ELD frames across the JNI boundary.
5. **Kotlin** `MediaCodec` renderers decode and display video/play audio. The C
   core does no rendering; the Kotlin layer does no protocol.

The design and implementation details live in
[`docs/superpowers/`](docs/superpowers/) (design spec + implementation plan).

## Build

### Prerequisites

- Android SDK (compileSdk 34, targetSdk 32, minSdk 29)
- Android **NDK `26.3.11579264`** (r26d) and CMake 3.22.1
- Host **autotools** toolchain (`autoconf`, `automake`, `libtool`, `pkg-config`)
  and `git` — used to cross-compile the native dependencies
- JDK 17

Set `JAVA_HOME` to a JDK 17 before running Gradle, e.g. on macOS/Homebrew:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_NDK_ROOT=/path/to/android-sdk/ndk/26.3.11579264
```

### Steps

The native protocol core (UxPlay) and its C dependencies are **not** checked in;
you fetch and cross-compile them first, then build the app.

1. **Vendor the UxPlay protocol core** (cloned at a pinned tag into the
   gitignored `app/src/main/cpp/uxplay/` and patched):

   ```sh
   ./scripts/vendor-uxplay.sh
   ```

2. **Cross-compile the native dependencies** into
   `app/src/main/cpp/prebuilt/<abi>/` (static libs, per ABI):

   ```sh
   ./scripts/build-openssl.sh     # OpenSSL 3.x  -> libcrypto.a
   ./scripts/build-libplist.sh    # libplist 2.x -> libplist-2.0.a
   ./scripts/build-fdk-aac.sh     # fdk-aac 2.x  -> libfdk-aac.a
   ```

3. **Build the app:**

   ```sh
   ./gradlew :app:assembleDebug
   ```

   The debug APK lands in `app/build/outputs/apk/debug/`.

## License

**MirrorDrive is licensed under the GNU General Public License v3.0 (GPLv3).**
See [`LICENSE`](LICENSE) for the full text.

The project links the GPLv3-licensed **UxPlay** protocol core, so the combined
work is GPLv3. This means anyone who distributes MirrorDrive binaries must make
the **complete corresponding source** available under the same license. Bundled
and linked third-party components and their licenses are listed in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## ⚠️ Legal notice — read before using or distributing

This is information, **not legal advice**.

MirrorDrive completes Apple's **FairPlay** handshake using reverse-engineered
logic (the UxPlay / playfair approach). There is **no MFi license available for
AirPlay *video / mirroring* reception** — MFi covers AirPlay audio, not screen
mirroring.

- **Personal, non-commercial, open-source use has the strongest footing.** That
  is the intended use of this project.
- **Commercial distribution — a paid app, ad-supported builds, or an app-store
  listing — materially raises legal exposure** (e.g. US DMCA §1201/§1204
  anti-circumvention provisions, and trademark). **It is out of scope for this
  project.**
- **Do not use the "AirPlay" name or logo** (or other Apple marks) to name,
  brand, or market this software.

If you plan to do anything beyond personal, non-commercial use, get your own
IP/legal counsel first.

## Credits

- **[UxPlay](https://github.com/FDH2/UxPlay)** (FDH2/UxPlay, GPL-3.0) — the
  AirPlay protocol core: RTSP/HTTP server, pairing, FairPlay v3, and stream
  decryption. MirrorDrive vendors its `lib/` and wraps it via JNI.
- **[jmDNS](https://github.com/jmdns/jmdns)** (Apache-2.0) — mDNS/Bonjour
  service advertisement.
- **[OpenSSL](https://www.openssl.org/)** 3.x (Apache-2.0) — crypto primitives.
- **[libplist](https://github.com/libimobiledevice/libplist)** (LGPL-2.1) —
  Apple property-list parsing.
- **[fdk-aac](https://github.com/mstorsjo/fdk-aac)** (Fraunhofer FDK AAC codec
  library) — AAC-ELD audio decoding.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for full license details.
