# Third-Party Notices

MirrorDrive bundles or links the third-party components listed below. Each is the
property of its respective authors and is used under the license shown.

MirrorDrive itself is licensed under **GPL-3.0** (see [`LICENSE`](LICENSE)).
Because it links the GPLv3 UxPlay core, the combined/distributed work is governed
by the GPLv3: **anyone distributing MirrorDrive binaries must provide the complete
corresponding source of the whole work under GPL-3.0.** The GPL-compatible
licenses below (Apache-2.0, LGPL-2.1) may be combined with GPLv3; their own notice
and attribution requirements still apply and are preserved here.

The native protocol core and its C dependencies are **not** checked into this
repository — they are fetched and cross-compiled at build time by the scripts in
[`scripts/`](scripts/) (which pin specific upstream tags). This file documents
what those scripts pull in.

---

## UxPlay

- **License:** GPL-3.0-or-later
- **Upstream:** https://github.com/FDH2/UxPlay
- **Used for:** The AirPlay protocol core — RTSP/HTTP control server, legacy
  pairing, FairPlay v3 handshake, and AES stream decryption.
- **How it's included:** Vendored under `app/src/main/cpp/uxplay/` (gitignored;
  cloned at a pinned tag and patched by `scripts/vendor-uxplay.sh`). Its `lib/` is
  compiled into MirrorDrive's native library. UxPlay bundles a `llhttp` HTTP
  parser and a `playfair` FairPlay implementation, both compiled as part of `lib/`.
- **Note:** UxPlay is the reason the entire MirrorDrive project is GPL-3.0.

## jmDNS

- **License:** Apache-2.0
- **Upstream:** https://github.com/jmdns/jmdns
- **Used for:** mDNS/Bonjour service advertisement (`_airplay._tcp`,
  `_raop._tcp`) so the iPhone can discover the receiver.
- **How it's included:** Maven dependency `org.jmdns:jmdns` (resolved at build).

## OpenSSL

- **License:** Apache-2.0 (OpenSSL 3.x)
- **Upstream:** https://www.openssl.org/  ·  https://github.com/openssl/openssl
- **Used for:** Cryptographic primitives required by the pairing/FairPlay and
  AES stream-decryption code (`libcrypto`).
- **How it's included:** Cross-compiled to a static `libcrypto.a` per ABI by
  `scripts/build-openssl.sh` and statically linked.

## libplist

- **License:** LGPL-2.1-or-later
- **Upstream:** https://github.com/libimobiledevice/libplist
- **Used for:** Parsing Apple property lists (plist) exchanged during the AirPlay
  handshake.
- **How it's included:** Cross-compiled to a static `libplist-2.0.a` per ABI by
  `scripts/build-libplist.sh` and statically linked.

## fdk-aac

- **License:** "Software License for The Fraunhofer FDK AAC Codec Library for
  Android"
- **Upstream:** https://github.com/mstorsjo/fdk-aac
- **Used for:** AAC-ELD audio decoding.
- **How it's included:** Cross-compiled to a static `libfdk-aac.a` per ABI by
  `scripts/build-fdk-aac.sh` and statically linked.
- **⚠️ Redistributor note:** This is a **permissive-but-non-OSI** license (it is
  **not** an OSI-approved open-source license and is not the standard AAC/patent
  grant). It carries its own copyright, patent, and attribution terms. Anyone
  redistributing binaries that include fdk-aac should **read the full fdk-aac
  license text and independently review its patent terms** — do not assume it is
  interchangeable with the OSI licenses above.

---

Full license texts for each component are available at the upstream URLs listed
above (and, for the vendored UxPlay, in `app/src/main/cpp/uxplay/LICENSE` after
`scripts/vendor-uxplay.sh` runs).
