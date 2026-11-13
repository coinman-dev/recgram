[English](README.md) | [Русский](README.ru_RU.md)

<p align="center">
  <img src="images/logo.png" alt="RecGram" width="160">
</p>

<h1 align="center">RecGram</h1>
<p align="center">An unofficial Telegram client for Android that records calls and lifts copy restrictions.</p>

[![Release](https://img.shields.io/github/v/release/coinman-dev/recgram?include_prereleases&label=release)](https://github.com/coinman-dev/recgram/releases)
[![Build](https://github.com/coinman-dev/recgram/actions/workflows/release.yml/badge.svg)](https://github.com/coinman-dev/recgram/actions/workflows/release.yml)
[![Android](https://img.shields.io/badge/Android-6.0%2B%20ARM-green)](#download)
[![Telegram](https://img.shields.io/badge/based%20on-Telegram%2013.2.2-blue)](https://github.com/DrKLO/Telegram)
[![License](https://img.shields.io/badge/license-GPL--2.0-blue)](LICENSE)

**RecGram** is a fork of the official [Telegram for Android](https://github.com/DrKLO/Telegram)
app. It records voice and video calls, lets you choose the recording rules
of the country you are in, warns the other party about the recording, and
can unlock screenshots, copying and saving in protected chats. Everything
else works as in Telegram.

RecGram is not affiliated with or endorsed by Telegram FZ-LLC. "Telegram" is a
trademark of its owners.

## Download

| File | Usage |
|---|---|
| `RecGram-vX.Y.Z.apk` on the [Releases](https://github.com/coinman-dev/recgram/releases) page | Open it on the phone and allow installing from that source. Updates install over the old version: every release is signed with the same key. |

Android 6.0 or newer, ARM processor (arm64-v8a or armeabi-v7a — nearly every
phone). Installs next to the official Telegram: its own package `app.recgram`
and its own account type. Antivirus apps and Samsung "App protection" may warn
about an app that is not from Google Play — that is their usual reaction to any
APK installed by hand. `SHA256SUMS.txt` in each release lets you check the file.

## Quick start

1. **Get your own API key.** RecGram has no shared key: every user works with
   their own. Open [my.telegram.org](https://my.telegram.org), sign in with your
   phone number (the code comes in Telegram), open **API development tools**,
   fill in any title, a short Latin name and platform **Android**, and copy
   **App api_id** and **App api_hash**. If the form says "ERROR", try without a
   VPN, in another browser or later.
2. **Start RecGram**, enter the two values on the first screen and sign in to
   Telegram as usual. The key stays on your phone only.
3. **Choose your country** under **Settings → RecGram → Call recording**, then
   pick when to record: manually with a button in the call, or all calls.
4. Make a call. The call screen shows a **REC** mark; when the call ends the
   recording is saved and a notification opens the list of recordings.

The interface follows the Telegram language; RecGram's own screens are in
English and Russian.

## Features

**Call recording**
- Private voice and video calls; group calls (audio).
- Audio: Opus, mono, 32/48/64 kbit/s (48 by default). Video: MP4, H.265
  (2.0 Mbit/s by default) or H.264.
- File name `Caller→Callee_YYYY-MM-DD_HH-MM-SS`; call data (who, to whom,
  when, duration) is written into the audio file tags and into a JSON file.
- Voice levels are evened out so that a quiet side is still audible.
- The call screen always shows the recording mark. RecGram has no hidden
  recording.

**Recording rules by country**

Before the first recording you choose the country you are in:

| Colour | Meaning | Notices to the other party |
|---|---|---|
| Green | Recording is allowed | off by default, can be turned on |
| Yellow | The law is unclear — you decide after a warning | on, can be turned off at your own risk |
| Red | Only with the other party informed | on and cannot be turned off |
| Grey | Recording is prohibited | recording is off |

The rules are a reference, not legal advice.

**Notices to the other party**
- A chat message before the call is answered: "🔴 This call will be recorded".
- A voice notice at the start: tone → "Attention. This call is being recorded.
  Please speak after the tone." → tone. Both sides hear it and it is in the
  recording.

**Protected content** (everything off by default)
- Screenshots and screen recording everywhere.
- Copying text and saving photos, videos and files from chats where this is
  forbidden.
- Resending a message as a new one instead of forwarding.

**Chat export**

Chat menu **⋮ → Export chat** saves a chat, group or channel the way Telegram
Desktop does: `messages.html` pages, `result.json` and folders with photos,
videos, voice and video messages, stickers and files. You choose the media types,
a file size limit and the formats.

**Bypassing blocks**

**Settings → RecGram → Bypass blocking**. It applies to RecGram only: no system
VPN is needed and other apps use the network as usual.
- **Telegram proxies** (MTProto, SOCKS5): import many `tg://proxy`,
  `t.me/proxy`, `tg://socks` and `t.me/socks` links at once from the clipboard,
  a subscription (a link to a list, Base64 too), and "Check and use the best".
- **DPI protection**: RecGram splits the first packet of a connection so that
  provider equipment cannot read the site name in it while the server still
  gets the whole packet. Modes: "Split packet", "Split + disorder"
  (recommended), "Split + OOB byte" and automatic selection. It does not help
  when Telegram server addresses are blocked — that needs a proxy, VLESS or
  AmneziaWG.
- **VLESS server (Xray)** is the most reliable bypass when Telegram is blocked
  by address. RecGram runs [Xray-core](https://github.com/XTLS/Xray-core) inside:
  VLESS with Reality or TLS over xhttp, ws, httpupgrade, gRPC or raw. Add a
  server with a `vless://` link from the clipboard ("Share" in v2RayTun and
  v2rayNG), a QR code (**Scan QR**), a file — an Xray JSON config or
  `shared_preferences.json` from v2RayTun — or a subscription. **Check servers**
  shows the ping to Telegram through each server; tap a server to turn it on.
  Xray runs as a separate process together with RecGram and listens only on
  `127.0.0.1` with a random login and password; server settings are never
  written to disk, and the log (**Tunnel log**) contains no uuids and no
  addresses Telegram connects to.
- **AmneziaWG and WireGuard** live in the same server list. The
  [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) tunnel runs inside
  the RecGram process without a system VPN and supports the AmneziaWG 2.0
  obfuscation parameters (Jc, Jmin, Jmax, S1–S4, H1–H4 with ranges, I1–I5).
  Add a server with a `.conf` file ("Export" in AmneziaWG), its text from the
  clipboard, a QR code or a `vpn://` link from the Amnezia app.
- **System VPN.** If a VPN (v2RayTun, AmneziaWG and so on) is already on on the
  phone and RecGram goes through it, the section says so and the built-in
  tunnel does not start: two VPNs in a row only slow things down. When the VPN
  is turned off, the tunnel starts by itself.
- The first start has a **Connecting to Telegram** step with the same
  settings. In countries that block Telegram it does not continue without a
  connection.

**Other changes**
- Maps: OpenStreetMap, satellite imagery by Esri.
- Notifications through a background connection: Telegram sends push
  notifications only to official apps.
- Own icon and start screen, no Telegram logos.

## Current boundaries

- Calls are recorded on your phone only: your microphone and what you hear.
  Group calls are recorded without video.
- Secret chats cannot be exported.
- Telegram calls do not go through proxies, VLESS or AmneziaWG — this is how Telegram
  itself works; messages, media and sign-in go through them fully.
- Xray adds about 12 MB to the APK per ABI, AmneziaWG about 3 MB.
- Notifications depend on the background connection; allow RecGram to run
  without battery restrictions, otherwise Android may close it.
- Telegram Premium cannot be bought in unofficial apps, but works if bought
  elsewhere.
- Recording phone conversations is regulated by the law of the country you are
  in, and sometimes of the other party's country. You are responsible for
  recording.

## Settings and data

| What | Where |
|---|---|
| Audio recordings | `Recordings/RecGram` (Android 12+) or `Music/RecGram` |
| Video recordings | `Movies/RecGram` |
| Call data, JSON | `Documents/RecGram/json` |
| Chat exports | `Documents/RecGram/Export/ChatExport_<chat>_<date>` |
| API key, settings, Telegram data | private app storage; removed with the app |

The **Settings → RecGram → Call recordings** list plays, shares and deletes
recordings.

## Building

You need JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1, Go and the
submodules:

```
git submodule update --init --recursive
recgram/tools/build_xray.sh arm64-v8a,armeabi-v7a
recgram/tools/build_awg.sh arm64-v8a,armeabi-v7a
./gradlew :TMessagesProj_App:assembleAfatRelease -PrecgramAbis=arm64-v8a,armeabi-v7a
```

The signing key and its passwords are read from `local.properties` or
environment variables (`RECGRAM_KEYSTORE`, `RECGRAM_KEYSTORE_PASSWORD`,
`RECGRAM_KEY_ALIAS`, `RECGRAM_KEY_PASSWORD`); see
[secrets/README.md](secrets/README.md). Without them the APK is signed with
Telegram's public test key and cannot update a release build. Without
`-PrecgramAbis` all four ABIs are built.

`build_xray.sh` downloads the Xray-core sources of the pinned version (the tag
at the top of the script, currently `v26.11.9`) and builds an executable
`libxray.so` for each ABI into `TMessagesProj/src/main/jniLibs/<abi>/` (not
kept in git). Android extracts it with the other native libraries and RecGram
runs it as a separate process. If you skip this step the app still builds, and
the VLESS section says that Xray is not part of the build.

`build_awg.sh` likewise builds `libawg.so` from `recgram/awg`, a small Go
program: an amneziawg-go tunnel in the gVisor userspace stack plus a SOCKS5
server on `127.0.0.1` for RecGram. The amneziawg-go version is pinned in
`recgram/awg/go.mod` and `go.sum`.

From Windows, one command does all of this (updating the sources in WSL first):
`python -I recgram/tools/build_apk.py` (details at the top of the script).

New code lives in the `org.recgram` package
(`TMessagesProj/src/main/java/org/recgram`). Telegram sources contain only small
hooks, each marked with a `// RecGram` comment; the scripts that apply them are
in `recgram/tools/patches`. The full list of changes is the git history after
commit `7be672ead` (Telegram 13.2.1); later Telegram releases are merged in
(now 13.2.2). PNG icons for Android 6–7 are drawn by
`recgram/tools/make_icons.py` (needs Pillow).

## Automated releases

Pushing a tag such as `v1.0.0` or `v1.0.0-beta.1` starts the
[release workflow](.github/workflows/release.yml). GitHub builds a signed APK
for arm64-v8a and armeabi-v7a (with Xray-core and AmneziaWG built from source), checks that it is signed with the RecGram
certificate, and publishes `RecGram-<tag>.apk` and
`SHA256SUMS.txt`. A tag with a suffix is marked as a prerelease. Run by hand,
the workflow builds the APK as an artifact kept for three days and publishes
nothing.

The RecGram version is set in `gradle.properties` (`RECGRAM_VERSION`); the tag
must match it or the build stops. The app shows it together with the Telegram
version it is based on: "1.0.1 (13.2.2)". Telegram servers only receive the
Telegram version (`APP_VERSION_NAME`).

## License

RecGram is a fork of Telegram for Android (Nikolai Kudashov and the Telegram
team) and is distributed under the same **[GNU GPL v2](LICENSE)**. The original
Telegram description is kept in [TELEGRAM_README.md](TELEGRAM_README.md).

Third-party parts:
- [Xray-core](https://github.com/XTLS/Xray-core) (Project X) — Mozilla Public
  License 2.0; shipped in the APK as a separate executable built unmodified
  from the source tag set in `recgram/tools/build_xray.sh`;
- [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) — MIT,
  [gVisor](https://github.com/google/gvisor) — Apache License 2.0; built into
  `libawg.so` (`recgram/awg`);
- the OpenStreetMap map provider is adapted from
  [Nullgram](https://github.com/qwq233/Nullgram) /
  [Nnngram](https://github.com/NextAlone/Nnngram) (GPL-2.0-or-later);
- [osmdroid](https://github.com/osmdroid/osmdroid) — Apache License 2.0;
- map data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
  contributors (ODbL); satellite imagery © Esri, Maxar, Earthstar Geographics
  and the GIS User Community.
