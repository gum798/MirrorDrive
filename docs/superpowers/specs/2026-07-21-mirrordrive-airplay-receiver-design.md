# MirrorDrive — 설계 문서

**작성일:** 2026-07-21
**상태:** 승인됨 (구현 계획 단계로 진행)

## 한 줄 요약

안드로이드 앱. 와글미디어 CAST(CarPlay AI 박스) 위에서 돌며, 아이폰 Air의 화면을 표준 **AirPlay 미러링**으로 받아 차량 헤드유닛 화면(2023 벤츠 EQS 하이퍼스크린)에 띄운다. 화면 + 소리, 지연 500ms 이내, 보기 전용(터치백 없음).

## 배경 / 왜 이 접근인가

사전 조사 3회로 다음이 확정됨:

- **순수 iOS 미러링 앱은 불가.** DashMirror/TDS Video 방식(카플레이 내비 권한 + ReplayKit)은 애플이 iOS 18.5 이후 구멍을 막았고, 개발자는 법무 서한을 받았다. 제품 경로로 죽음.
- **AI 박스는 안드로이드다.** 와글미디어 CAST = Android 12, MediaTek MT6769T, 4GB/64GB, GMS 인증. **Play Store 있음 + "출처를 알 수 없는 앱" 사이드로드 가능** (사용자 실기 확인 완료). → APK 배포 경로 열림. MediaCodec 하드웨어 H.264 디코더 확보.
- **AirPlay 수신은 안드로이드에서 성숙한 오픈소스가 있다.** UxPlay/RPiPlay가 Apple의 레거시 AirPlay 미러링(AirPlay-1/FairPlay-v3) 프로토콜을 역공학 구현. 아이폰이 전체화면 미러에 실제로 쓰는 프로토콜.

핵심 판단: **프로토콜은 재발명하지 않는다.** UxPlay `lib/` C 코어를 NDK로 이식하고 JNI로 감싼다. 우리가 새로 쓰는 것은 렌더러 콜백 2개(비디오/오디오)뿐. 애플이 iOS마다 깨는 부분이 discovery/pairing이므로, 검증된 구현 재사용이 정공법이다.

## 범위

**1단계 (이 스펙):** 사용자의 와글미디어 CAST에서 아이폰 Air 미러링 작동.
- 화면 + 소리
- 지연 ≤ 500ms (손끝→화면)
- 보기 전용, 조작은 아이폰에서만 (터치백 없음)

**2단계 (나중, 별도 스펙):** 제품화 — Play Store/한국 마켓 배포, 결제, 기기 호환성, 상표/법률 정리.

**범위 밖 (v1):**
- 터치백 (AirPlay에 역방향 채널 없음 + iOS가 터치 주입 차단. BT HID 커서 방식은 별개 작업. 주행 중 차 화면 조작은 도로교통법 제49조 11호의2 위반이라 보기 전용이 법적으로도 방어적)
- DRM 앱(넷플릭스/디즈니+/티빙 등) — 미러 시 검은 화면. iOS가 보호 콘텐츠 캡처를 컴포지터에서 차단. 프로토콜 한계, 해결 불가
- HEVC/4K (H.264 먼저)
- 제품화 일체

## 대상 하드웨어

| 항목 | 값 |
|---|---|
| 소스 | 아이폰 Air (iOS 26.2+) |
| 수신 기기 | 와글미디어 CAST — Android 12, MediaTek MT6769T, 4GB/64GB |
| 차량 | 2023 벤츠 EQS (하이퍼스크린), 유선+무선 CarPlay 모두 지원 확인 |
| 배포 | APK 사이드로드 또는 Play Store (둘 다 가능 확인) |

## 아키텍처

```
[아이폰 Air] ──AirPlay(WiFi)──> [CAST 박스]
                                    │
   ┌────────────────────────────────┴─────────────────────────────┐
   │  MirrorDrive APK                                              │
   │  Kotlin 앱 계층                                               │
   │   - Foreground Service (수신기 생명주기)                       │
   │   - 전체화면 Activity + SurfaceView                           │
   │   - jmDNS 서비스 광고 (_airplay._tcp, _raop._tcp)             │
   │        └ JNI ─────────────────────────┐                      │
   │  네이티브 계층 (UxPlay lib/ 이식, C)     │                      │
   │   - RTSP/HTTP 제어 서버                  │                      │
   │   - pairing.c / fairplay_playfair.c    │                      │
   │   - raop_rtp_mirror.c (비디오 TCP, AES-CTR)                    │
   │   - raop_rtp.c (오디오 UDP, AES-CBC)                          │
   │   - raop_ntp.c (타이밍)                  │                      │
   │        └ 콜백 ──> video_renderer / audio_renderer            │
   │  렌더러 (신규, Kotlin/JNI 브리지)                              │
   │   - MediaCodec(video/avc) → Surface                          │
   │   - MediaCodec(mp4a-latm, AAC-ELD) → AudioTrack              │
   └──────────────────────────────────────────────────────────────┘
```

경계 원칙: C 코어가 복호화까지 끝내고 **평문 NAL/AU만** 밖으로 낸다. 렌더러는 AirPlay를 전혀 모르는 순수 디코더 → 로컬 파일로 단독 테스트 가능.

## 컴포넌트 분해

| # | 유닛 | 책임 | 인터페이스 | 의존 |
|---|---|---|---|---|
| 1 | **DiscoveryService** (Kotlin) | jmDNS로 두 서비스 광고. TXT 정확히. 실제 바인딩 포트 광고 | `start(port)`, `stop()` | jmDNS |
| 2 | **NativeBridge** (JNI) | C 코어 ↔ Kotlin 경계. 네이티브 콜백을 Kotlin 렌더러로 전달 | `nativeStart()`, `onVideoNAL()`, `onAudioAU()`, `onResolutionChanged()` | UxPlay lib/ |
| 3 | **ProtocolCore** (C, UxPlay 이식) | RTSP/HTTP·페어링·FairPlay·복호화·NTP. 평문 NAL/AU를 콜백으로 출력 | UxPlay `video_renderer_t`/`audio_renderer_t` | OpenSSL, libplist (NDK) |
| 4 | **VideoRenderer** (Kotlin) | H.264 → MediaCodec(Surface). AVCC→Annex-B, SPS/PPS→csd, 저지연 | `configure(sps,pps)`, `decode(nal, ptsUs, isIDR)`, `flush()` | MediaCodec |
| 5 | **AudioRenderer** (Kotlin) | AAC-ELD AU → MediaCodec → AudioTrack. 공유 클럭 A/V 싱크 | `configure(asc)`, `play(au, ptsUs)`, `stop()` | MediaCodec, AudioTrack |
| 6 | **MirrorActivity + Service** (Kotlin) | 전체화면 SurfaceView, Foreground Service, 재연결 | 안드로이드 생명주기 | 1·2 |

## 빌드 순서 + 검증 게이트

각 단계는 검증 게이트를 통과해야 다음으로 간다.

| 단계 | 만드는 것 | 검증 게이트 |
|---|---|---|
| 0 | NDK 빌드 뼈대. OpenSSL+libplist 크로스컴파일, UxPlay `lib/` 컴파일, JNI 로드 | CAST에 빈 APK 설치 → `System.loadLibrary` 성공, 크래시 없음 |
| 1 | DiscoveryService + `GET /info` plist | **아이폰 제어센터 미러링 목록에 "MirrorDrive" 표시** (iOS 26 최난관) |
| 2 | 페어링(pair-setup/verify) + FairPlay(fp-setup, JNI playfair) | 아이폰 "연결 중" 통과, 미끊김. RTSP RECORD 도달 |
| 3 | RTSP SETUP(type110) + TCP 비디오 + AES-CTR + AVCC→AnnexB + MediaCodec/Surface | **첫 그림 표시** (소리 없어도 OK) |
| 4 | type96 UDP 오디오 + AES-CBC + AAC-ELD(fdk-aac) + AudioTrack | 소리 남 (립싱크 대충 OK) |
| 5 | NTP 타이밍 채널 + 공유 클럭 A/V 싱크 | 입 모양과 소리 일치 |
| 6 | 저지연 튜닝(KEY_LOW_LATENCY, 늦은 프레임 드롭, 타이트 루프) | **손끝→화면 지연 ≤500ms** (스톱워치 영상 측정) |

**단계 3이 핵심 마일스톤.** 1~2가 애플 프로토콜 벽이라 시간 집중.

독립 검증:
- 렌더러(4·5)는 로컬 AAC-ELD/H.264 파일로 프로토콜 완성 전 단독 테스트
- 단계 1은 UxPlay 데스크톱 빌드로 "아이폰 Air가 이 프로토콜에 뜨는지" 조기 검증 → 리스크 조기 제거

## 프로토콜 스펙 (구현 참조)

역공학 검증(41개 발견 중 34개 통과) 기반. 핵심만.

### Discovery (mDNS)

두 서비스를 같은 호스트에 광고. **jmDNS 사용** (NsdManager는 다중 TXT 불안정).

- `_airplay._tcp` — 미러링/비디오 제어. 로드베어링.
- `_raop._tcp` — 동반 오디오. 인스턴스명 `<deviceid-hex>@<name>`.

`_airplay._tcp` TXT 핵심:
- `features = 0x5A7FFEE6,0x0` (split form, 모던 iOS). bit2 videoFairPlay, bit7 mirror(0x80), bit9 audio, bit27 legacy pairing. `FEATURES_2 = 0x0`.
- `flags = 0x4`, `model = AppleTV3,2`, `srcvers = 220.68`
- `pk = <신규 Ed25519 pubkey 64hex>` — **데모 키 절대 재사용 금지**
- ⚠️ **bit48 (AirPlay-2 transient pairing) 광고 금지** — 구현 안 했는데 광고하면 모던 sender가 암호화 페어링 시도 후 fallback 없이 실패.
- ⚠️ 포트 7000/7100 하드코딩 금지. `ServerSocket`에서 실제 바인딩 포트 읽어 광고. 비디오 데이터 포트는 mDNS 아닌 RTSP SETUP 응답(`dataPort`)으로 인밴드 협상.

### 페어링 / FairPlay

단일 raw-TCP 서버가 HTTP/1.1 + RTSP/1.0 동시 처리. 모든 응답에 `CSeq` echo.

순서: `GET /info`(binary plist) → `POST /pair-setup` → `POST /pair-verify` → `POST /fp-setup` → RTSP `OPTIONS` → `SETUP` → `RECORD` → `SET_PARAMETER`/`feedback`(~3s). **ANNOUNCE 없음** (그건 RAOP 오디오 전용).

- pair-verify: X25519 ECDH + Ed25519 서명(두 공개키에 대한 서명, 공유비밀 아님). 세션 = AES-128-CTR, 키/IV = SHA-512(공유비밀, salt `"Pair-Verify-AES-Key"`/`"Pair-Verify-AES-IV"`).
- fp-setup(FairPlay v3): Stage A 16B 입력 → 142B 응답(mode>3 bounds check 추가). Stage B 164B 입력 저장 → 32B 응답.
- ⚠️ FairPlay 16B 출력은 **미디어 키가 아님** — 스트림별 파생 필요(아래).
- 이식: pairing은 RPiPlay `lib/pairing.c`, FairPlay는 `lib/fairplay_playfair.c` **+ `lib/playfair/` 전체**(난독화 C, JNI로, Kotlin 재구현 금지).

### 비디오 파이프라인

- 전용 **TCP** (SETUP type110의 `dataPort`). 128바이트 헤더 + payload.
  - `[0:3]` payload_size(int32 LE), `[4]` type(&0xFF), `[5]` IDR flag(0x10=IDR), `[8:15]` timestamp
  - type `0x00` 암호화 비디오(이것만 복호화), `0x01` 비암호 SPS/PPS, `0x02` heartbeat, `0x05` perf report(timestamp 없음)
- ⚠️ **timestamp는 raw ns 아님** — NTP 32.32 고정소수점. 변환:
  ```
  long seconds  = (raw >>> 32) & 0xFFFFFFFFL;
  long fraction = raw & 0xFFFFFFFFL;
  long nanos    = seconds*1_000_000_000L + ((fraction*1_000_000_000L) >>> 32);
  long ptsUs    = nanos / 1000;
  ```
  직접 ns 취급 시 ~4.29배 빨라져 A/V 싱크 파괴.
- 복호화: **AES-128-CTR, 연속 키스트림** (패킷마다 리셋 금지). type-0 payload만 하나의 `Cipher("AES/CTR/NoPadding")`에 순차 `update()`. `doFinal`/re-init 금지.
  ```
  base = fairplay_decrypt(ekey[72])                     // 16B
  base = SHA512(base[16] || ecdh_secret[32])[0:16]      // 페어링 세션 존재 시
  videoKey = SHA512("AirPlayStreamKey" + streamConnectionID(decimal) || base)[0:16]
  videoIV  = SHA512("AirPlayStreamIV"  + streamConnectionID(decimal) || base)[0:16]
  ```
- NAL 재포맷: 복호화된 type-0는 **AVCC**(4바이트 big-endian 길이 프리픽스). 각 길이를 Annex-B start code `00 00 00 01`로 덮어쓰고 access unit 하나를 MediaCodec 입력 버퍼 하나로.
- SPS/PPS(type-0x01): `csd-0 = 00000001+SPS`, `csd-1 = 00000001+PPS`. 해상도는 SPS에서 유도.
- 저지연: Surface로 디코딩(ByteBuffer readback 금지), API30+ `KEY_LOW_LATENCY=1`, async `setCallback` timeout 0, 배칭 없음, 즉시 render, PTS ~100-200ms 지각 프레임 드롭.

### 오디오 파이프라인

- 비디오 TCP 아님 — 별도 **RAOP RTP/UDP type-96** 스트림(AAC-ELD).
- 소켓 3개: UDP 오디오 데이터/컨트롤/NTP타이밍. 포트는 SETUP 응답 plist에.
- 코덱은 SETUP plist `ct`로 분기(하드코딩 금지): `ct=8` AAC-ELD(미러링 대부분, spf 480), `ct=2` ALAC. 샘플레이트 고정 44100 스테레오.
- 복호화: **AES-128-CBC, 패킷마다 IV(eiv) 리셋**. 비디오와 다른 모드/컨텍스트. 키는 FairPlay base(+ECDH SHA-512), 스트림별 파생 아님. byte 12부터 whole-block만, sub-16 tail은 평문.
- AAC-ELD → MediaCodec: MIME `audio/mp4a-latm`, `KEY_AAC_PROFILE=39(ELD)`, **`csd-0 = {0xF8,0xE8,0x50,0x00}`** (44100/스테레오 ELD ASC). raw AU(ADTS 아님). PTS 480/44100≈10.884ms/AU.
- ⚠️ 하드웨어 AAC-ELD 지원 불확실 → **fdk-aac를 NDK 번들**(`aacDecoder_Open(TT_MP4_RAW,1)`, AOT 39). 플랫폼 코덱 불신.
- A/V 싱크: 공유 NTP 클럭. 비디오는 패킷 헤더 NTP, 오디오는 sync 패킷 앵커. NTP 요청 32B(opcode `0x80 0xd2 0x00 0x07`), ⚠️ **transmit t0을 offset 24에 기록**(offset 8 아님). 8샘플 중 **최소 delay** 샘플의 offset 채택. 3s 폴링.

### iOS 26/27 주의

- 취약면은 **discovery+pairing+PIN**, AES 미디어 암호 아님.
- iOS 26.0–26.1 서드파티 수신기 regression(vendor 확인) → **26.2에서 복구. 26.2+ 타깃.**
- `_airplay._tcp` TXT와 첫 `GET /info` plist를 정확히 — 틀리면 iOS picker가 조용히 드롭. 여기가 iOS마다 디버깅 지점.
- iOS 27 `combinedGetInfoWithControlSetup`(UxPlay #535): **가설, 미확정 blocker.** 특수 구현 금지. SETUP 핸들러를 **관대하게**(미지 키 무시, type110에 `dataPort` 계속 응답), 방화벽/IPv6부터 배제. SETUP 계층을 feature-flag 가능하게 설계.

### 법적 플래그

- **FairPlay/fp-setup 네이티브 lib이 법적 위험 집중부.** AES 복호화·MediaCodec·mDNS는 무해. MFi에 AirPlay 비디오/미러링 수신 tier 없음 — 모든 미러링 수신기는 비인가 역공학.
- 1단계(개인/비상업/오픈소스): §1201(f) 상호운용 항변 최강, §1204 형사(상업이득 요건) 대체로 회피. 잔여 리스크 = GitHub takedown/C&D, 소송 확률 낮음. 스토어 미등재, "AirPlay" 워드마크/로고 미사용 권장.
- 2단계(상업): 위험 상승 — 비상업 방패 상실, §1204 형사 노출, 상표 문제. **유료 출시 전: FairPlay 네이티브 lib 격리, "AirPlay" 워드마크 제거, 상호운용 자세, IP 변호사.** 수익화가 트리거.
- (법률 정보이지 자문 아님. US 기준, 관할 상이.)

### 참조 코드베이스

**UxPlay(`github.com/FDH2/UxPlay`)에서 이식.** 2026년까지 활발(v1.73.6). `lib/`는 clean C(dnssd, raop, pairing, fairplay_playfair, raop_rtp, raop_rtp_mirror, mirror_buffer, crypto) — NDK 컴파일, JNI로 Kotlin 구동. GStreamer 싱크(`renderers/`, `lib/` 아님)를 MediaCodec+Surface/AudioTrack 콜백으로 교체.

이식 수반: **OpenSSL**(crypto.c, pairing.c)+**libplist**(RTSP/SETUP plist)를 NDK ABI로 크로스컴파일, `lib/` 컴파일, 렌더러 콜백 구현.

보조: RPiPlay(`github.com/FD-/RPiPlay`)를 FairPlay/RTSP 참조로(단 2022 동결, iOS26 거동 참조 금지). AirPlay-2 transient pairing 필요 시 `github.com/openairplay/airplay2-receiver`(레거시 미러링엔 불필요).

## 핵심 리스크 요약

1. **iOS 26 프로토콜 벽 (최고 위험)** — 미러 목록에 안 뜨면 전부 무의미. 단계 1에서 UxPlay 데스크톱으로 조기 검증.
2. **FairPlay 이식 (최고 난이도)** — 난독화 C 통째 JNI 이식, 재구현 금지.
3. **CAST AAC-ELD 미지수** — fdk-aac NDK 번들로 하드웨어 불신.
