# Changelog

All notable changes to the Nuvio Auto (Nuvio on GEN5W) project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-11-14

Initial production release of **Nuvio Auto** for Hyundai, Kia, and Genesis Gen5W infotainment systems running Android 4.4.2 KitKat (API 19) via NEMESIS.

### 🛡️ Platform Core & Safety Interlocks (Milestone 1)
- **Single-DEX Dalvik Compliance**: Engineered strictly under the 64K method limit with zero multidex dependencies for pure Android 4.4 KitKat compatibility.
- **Modern TLS 1.2 / 1.3 Engine**: Integrated Google Conscrypt BoringSSL (`org.conscrypt:conscrypt-android:2.5.2`) to enable modern cipher suites and TLS 1.2/1.3 communication on Android 4.4.
- **Mozilla Root CA Trust Store**: Bundled Mozilla's root CA certificate bundle (`cacert.pem`) in app assets to eliminate SSL handshake failures on outdated system trust stores.
- **Hardware Park Gate (`VehicleVideoGate.java`)**: Integrated dynamic reflection against `/system/framework/automotive.jar` (`com.hkmc.micom.ISystemService` and `com.hkmc.regulation.IRegulationManager`) to enforce video display lockout when the vehicle is in gear.
- **Vehicle Motion Guard (`MotionGuard.java`)**: Added GPS-based vehicle speed monitoring with a 2 mph threshold lockout and automatic playback pause.
- **Telematics Modem Protection (`netguard.sh`)**: Background iptables daemon isolates Nuvio's UID traffic strictly to `wlan0`, protecting the vehicle's built-in BlueLink / UVO cellular modem from unauthorized data consumption.

### 🎨 Native Automotive UI & Frontend (Milestone 2)
- **Pure Native UI Architecture**: Eliminated Chromium 33 WebView memory overhead in favor of a responsive, native Android View hierarchy optimized for 1920x720 ultrawide displays.
- **Nuvio Dark Purple Theme**: Styled with official Nuvio purple branding (`#a855f7` accent, `#0d0f17` background, `#151922` card surfaces).
- **Navigation Bar**: Tabbed navigation with dedicated sections: *Movies*, *TV Series*, *Search*, *Add-ons*, *Debrid*, *Account*, *Diagnostics*, and *Settings*.
- **Multi-Profile Management**: Full support for Nuvio user profiles, active profile switching, and profile avatar rendering.
- **Catalog & Addon Ecosystem**: Support for Cinemeta, CyberFlix, and Torrentio addons, catalog row scrolling, and poster browsing.
- **Debrid Provider Integration**: Real-Debrid, Torbox, and AllDebrid account status monitoring and API key integration.
- **Pinned Detail & Episode Navigation**: Added dedicated navigation bar for stream selection (`← Back` button with title and media type badge).
- **TV Series Season & Episode Picker**: Allows selecting season and episode numbers before fetching stream links for accurate cloud watch syncing.
- **Stream Filtering & Badges**: Quality filter bar (`All`, `H.264 (HW)`, `1080p`, `720p`, `480p`) with seed count parsing, file size badges, and hardware compatibility tags (`✓ H.264 (HW PLAYABLE)`, `⭐ BEST FOR GEN5W`, `⭐ EASY PLAY`, `⚠️ HEVC`, `⚠️ 10-Bit Color`).
- **Clean Cold Launch**: Restructured `onCreate()` and `onSaveInstanceState()` so fresh app launches always start on the Home catalog ("Movies") and never get stuck on previously selected streams.

### 🎬 Playback Engine & Automotive Video (Milestone 3)
- **ExoPlayer 2.14.2 Integration**: Integrated Android 4.4-compatible ExoPlayer core, UI, and HLS modules directly to the native `SurfaceView` overlay.
- **Surface Lifecycle Synchronization**: Added `prepareWhenSurfaceReady` to defer codec configuration until `surfaceCreated` fires, eliminating the KitKat `Decoder init failed: OMX.google.h264.decoder` (`IllegalStateException`) race condition.
- **Telechips Hardware Acceleration**: Configured rendering pipeline to utilize the vehicle's hardware VPU (`OMX.TCC.avcdec`) for native 1080p60 H.264 hardware decoding.
- **Ultrawide Aspect Ratio Cycling**: On-screen control cycles dynamically between `Fit (16:9)`, `Fill (21:9)`, and `Stretch` across the 10.25" display.
- **Audio & Subtitle Selectors**: On-screen modal dialogs for switching audio soundtracks and toggling embedded/external subtitle tracks.
- **Cloud Playback Sync (`PlaybackSync.java`)**: Automatically syncs playback timestamps and watch history to your Nuvio account in the background (on interval, on pause, and on finish).
- **Offline Test Lab**: Bundled offline synthetic test clips (`asset:///test_avc.mp4` and `asset:///test_mpeg4.mp4`) in Settings for instant local playback verification without internet access or 403 errors.
- **Context-Aware Error Diagnostics**: Dynamic codec inspector decodes H.264 profiles (`avc1.42...`, `avc1.64...`), HEVC, and multi-channel Dolby audio with clear on-screen explanations distinguishing emulator limitations from real vehicle hardware capabilities.

### 💾 Storage & Caching Engine (Milestone 4)
- **Two-Tier Image Caching**: Combined an in-memory `LruCache` with a persistent local disk cache (`nuvio_posters/` and `nuvio_avatars/`) using MD5 URL hashing.
- **Zero-Flicker Catalog Browsing**: Posters and avatars load synchronously from local disk after their initial download, eliminating network re-downloads and saving mobile hotspot data.
- **Live Storage Breakdown**: Real-time cache measurement in Settings displaying exact megabytes used:
  `Cache Used: X.XX MB (Posters: X.XX MB • Avatars: X.XX MB)`.
- **One-Tap Cache Cleaner**: Clears both in-memory caches and persistent disk cache directories with instantaneous on-screen size counter refresh.

### 📦 Packaging, CI/CD & Legal Compliance (Milestone 5)
- **NEMESIS Integration**: Configured `packaging/NEMESIS.data` and installation scripts (`scripts/pre.sh`, `guard/netguard.sh`, `guard/preuninstall.sh`).
- **Automated Packaging (`tools/package.py`)**: Script validates manifest limits, verifies POSIX permissions, and outputs both store package (`nuvio.gen5w.zip`) and flash-drive installer (`nuvio.gen5w-usb-install.zip`).
- **GitHub Actions CI/CD (`.github/workflows/build.yml`)**: Automated pipeline to build debug APKs, package NEMESIS USB installers, and publish tagged GitHub releases.
- **Open-Source Compliance**: Fully licensed under **GNU General Public License v3.0 (GPL-3.0)**, aligned with upstream NuvioMedia, with complete Apache 2.0 attribution in `NOTICE`, `licenses/` directory, and an in-app legal notice viewer.
