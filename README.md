# MirrorDrive

An Android app that receives an iPhone's screen over the local network using
Apple's legacy **AirPlay mirroring** protocol and displays it on the Android
device — either fullscreen with aspect-ratio letterboxing, or in a movable,
resizable floating window over other apps.

MirrorDrive is a **view-only receiver**: the iPhone mirrors its whole screen to
the app over Wi-Fi (or a shared hotspot), and the app decodes and renders it.
There is no touch-back channel — you control everything from the iPhone.

## 📥 다운로드 / Download

**→ [최신 APK 다운로드 (Latest Release)](https://github.com/gum798/MirrorDrive/releases/latest)**

안드로이드 기기에서 위 링크를 열어 APK를 받고 사이드로드로 설치하세요 (설치 방법은 릴리스 페이지에 있습니다).
Open the link on your Android device, download the APK, and sideload it — install steps are on the release page.

---

## 한국어 안내 (For Korean users)

개발자가 아니어도 괜찮아요. 아래만 따라 하면 됩니다.

### 이게 뭐예요?

**MirrorDrive**는 **아이폰 화면을 이 안드로이드 기기에 무선으로 띄워주는 앱**이에요.
태블릿, 차량용 화면(카플레이형 AI 박스) 등에서 아이폰 화면을 그대로 볼 수 있어요.
화면은 **전체화면**으로 크게 보거나, **떠다니는 작은 창**으로 볼 수 있어요.
같은 Wi-Fi(또는 아이폰 핫스팟)에 연결만 하면 케이블 없이 무선으로 연결됩니다.

> 참고: 화면을 **보기만** 하는 앱이에요. 안드로이드 화면을 터치해도 아이폰이 조작되지는 않아요. 조작은 아이폰에서 하세요.

### 필요한 것

- **안드로이드 10 이상**의 기기 (태블릿, 차량용 화면 등)
- 아이폰과 **같은 Wi-Fi 네트워크** (또는 아이폰의 핫스팟에 안드로이드 기기를 연결)

### 설치 방법

APK 파일을 직접 받아서 설치하는 방식이에요 (플레이스토어 아님). 처음이라면 아래 순서대로 하세요.

1. 안드로이드 기기에서 위 **[최신 APK 다운로드](https://github.com/gum798/MirrorDrive/releases/latest)** 링크를 엽니다.
2. APK 파일을 **다운로드**합니다.
3. 받은 파일을 **엽니다**(탭합니다).
4. "이 출처의 앱은 설치할 수 없습니다" 같은 안내가 나오면, **"설정" 또는 "허용"**을 눌러 **"출처를 알 수 없는 앱"(이 브라우저/파일 앱에서의 설치)**을 허용해 주세요.
5. 다시 파일을 열어 **"설치"**를 누르면 끝입니다.

> 이 안내는 안드로이드가 스토어 밖에서 받은 앱을 처음 설치할 때 항상 물어보는 정상적인 과정이에요.

### 쓰는 법

1. 아이폰과 안드로이드 기기를 **같은 Wi-Fi**(또는 아이폰 핫스팟)에 연결합니다.
2. 안드로이드 기기에서 **MirrorDrive 앱을 실행**합니다.
3. 아이폰에서 **제어센터**를 엽니다(화면 오른쪽 위에서 아래로 쓸어내리기).
4. **"화면 미러링"**을 누릅니다.
5. 목록에서 **MirrorDrive**를 선택합니다.
6. 잠시 후 아이폰 화면이 안드로이드 기기에 나타납니다. 🎉

### 알아두기

- **넷플릭스·디즈니+·티빙 등 저작권 보호(DRM) 영상은 검은 화면으로 나와요.** 앱 문제가 아니라 아이폰(iOS)이 보호 영상은 미러링을 막기 때문이에요. 이건 해결할 수 없어요.
- 이 앱은 **개인적·비상업적 사용**을 위한 **오픈소스 프로젝트**예요 (라이선스: **GPL-3.0**). 판매하거나 광고를 붙이는 상업적 배포는 이 프로젝트의 범위가 아니에요.
- **피드백 환영!** 잘 되거나 안 되는 점이 있으면 편하게 알려주세요.

아래는 개발자를 위한 영어 설명입니다. / The rest of this README (in English) is for developers.

---

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
