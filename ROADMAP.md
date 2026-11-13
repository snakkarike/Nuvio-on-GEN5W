# Implementation Roadmap: Nuvio on GEN5W

## Milestone 1: Platform & Safety Core (Complete Scaffolding)
- [x] Establish repository structure matching NEMESIS specification.
- [x] Adapt `network-guard` for `org.nuvio.gen5w` package UID binding.
- [x] Configure `NEMESIS.data` and `USB-NEMESIS.data` with package dependencies (`media.codecs`, `keyboard.hackers`, `keyboard.activate`).
- [x] Port Android 4.4 / API 19 Gradle project skeleton with vendored ExoPlayer 2.x and `VehicleVideoGate.java`.

## Milestone 2: Native Android Shell & Player Bridge
- [x] Integrate `NativePlaybackBridge.java`, `NativePlaybackService.java`, and `NativeVideoOverlay.java`.
- [x] Configure `MainActivity.kt` with a KitKat-optimized `WebView` (caching, DOM storage, hardware acceleration).
- [x] Test local HTTP stream playback over the native ExoPlayer overlay with vehicle park checking.

## Milestone 3: Nuvio Frontend Integration
- [ ] Pull `NuvioMedia/NuvioTVSmart` / `NuvioWeb` core interface.
- [ ] Create Babel/Webpack build pipeline targeting ES5 for Android 4.4 Chromium 33.
- [ ] Polyfill Fetch, Promises, Crypto, and URLSearchParams for KitKat.
- [ ] Replace HTML5 `<video>` element with `window.NativePlayer.load(streamData)` bridge call.
- [ ] Test Stremio addon manifest resolution (Cinemeta, Torrentio, CyberFlix, Debrid providers).

## Milestone 4: Packaging & Vehicle Testing
- [ ] Implement `tools/build.py` and `tools/package.py` for automated USB & Store ZIP generation.
- [ ] Validate on Gen5W head unit test bench / vehicle running NEMESIS.
