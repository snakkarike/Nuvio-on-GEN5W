# Changelog

All notable changes to the Nuvio Auto (Nuvio on GEN5W) project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.2] - 2026-11-15

### 🚀 Native NEMESIS USB Installer Architecture
- **Standard Native Package Spec (`type=install`)**: Replaced deprecated `type=execute` and fragile custom `NEMESIS.sh` script with native NEMESIS USB installer specification (`path=packages/nuvio.gen5w.zip`).
- **Vehicle Installation UI Fix**: Resolves blank package dialogs on the Gen5W infotainment screen by allowing the head unit's NEMESIS daemon to parse metadata, dependencies (`media.codecs`, `keyboard.hackers`, `keyboard.activate`), author, and description natively.
- **Path Resolution Reliability**: Eliminates working-directory relative path lookup failures (exit code 12: `Replacement Nuvio package missing`) during USB installation.
- **Component Launcher Target**: Corrected component entry in `packaging/NEMESIS.data` to `org.nuvio.gen5w/org.nuvio.gen5w.MainActivity` ensuring proper application launch after install.

### 🎬 External VLC Player Support & Diagnostics
- **External Player Setting**: Added "Enable External VLC" toggle under *Settings* allowing users to route streams directly to VLC for Android when installed on the head unit.
- **KitKat CA Certificate Diagnostics**: Added detection and guidance regarding Android 4.4's expired system CA certificate root store (September 2021) in external players, highlighting Nuvio's built-in ExoPlayer which utilizes an embedded Mozilla root store with BoringSSL modern TLS 1.2/1.3.

### 📦 Automated Packaging & CI/CD
- **Packaging Pipeline (`tools/package.py`)**: Updated USB packaging generator to cleanly create standard `dist/Nuvio-gen5-hmg-nemesis-v1.0.2-USB-Installer.zip` without legacy shell wrappers.
- **GitHub Actions Workflow (`build.yml`)**: Synchronized CI build pipeline with v1.0.2 release artifacts, automated source archive generation, and release publishing.

---

## [1.0.1] - 2026-11-15

### 🎨 Material Design 3 (M3) Native Theme Engine
- **Native M3 Tonal Palette System (`ThemeManager.java`)**: Complete zero-dependency implementation of Google's Material Design 3 design system for Android 4.4 KitKat single-DEX (< 64K methods).
- **6 Built-in Automotive Palettes**: One-tap live theme switching under *Settings*:
  - **Nuvio Classic** (Default — deep purple `#141218` surface with `#D0BCFF` primary accent)
  - **Ocean Blue** (Deep blue / cobalt theme)
  - **Ioniq Teal** (Matches Hyundai EV teal dashboard accents)
  - **Kia Crimson** (Matches Kia sporty red/black cockpits)
  - **Genesis Copper** (Matches Genesis luxury copper styling)
  - **Daylight** (High-contrast light theme for bright direct sunlight)
- **Canvas-based Custom M3 Switch (`M3Switch.java`)**:
  - Implemented mathematical Canvas rendering (`drawRoundRect` for 52dp × 32dp stadium track and `drawCircle` for 16dp/24dp morphing thumbs).
  - Resolved KitKat Dalvik bug where default Holo toggle switches compressed and stretched custom thumb drawables outside the track bounds.
  - Added high-contrast automotive rotary knob focus indicators for jog-dial navigation.
- **Daylight & Light Mode Architecture**:
  - Full daytime driving contrast adaptation across all views, search inputs, dialogs, cards, and modal sheets.
  - Generated dynamic vector-derived light-theme brand assets (`nuvio_logo_light.png`) with deep charcoal `#171D1E` (`onSurface`) typography.
  - Contrast-tuned text, icons, cards, and form inputs to guarantee readability under harsh glare and direct sunlight.
- **Player UI Material Theming (`ExoPlayerActivity.java`)**:
  - Replaced legacy hardcoded purple and Holo elements with active `ThemeManager` palette tokens.
  - **Center Transport Controls**: Primary circular Play/Pause button (`theme.primary` with `theme.onPrimary` glyph) flanked by translucent M3 surface circular rewind/forward buttons (-10s / +10s).
  - **Custom M3 SeekBar**: Constructed a layered KitKat-compatible slider using `ClipDrawable` and `LayerDrawable` (translucent background track, 45% alpha buffer bar, solid `theme.primary` active progress, and 18dp circular thumb).
  - **M3 Tool & Modal Dialogs**: Audio track and subtitle selection popups upgraded to widescreen M3 surface cards with rounded interactive track pills.
  - **Ambient Scrims**: Dynamic alpha gradient overlays tinted with `theme.surface` for improved legibility during playback.
- **Unified Media Cards (Movies & Shows)**:
  - Replaced transparent container and detached bottom pill buttons with unified Material 3 interactive cards (`ThemeManager.styleInteractiveCard`).
  - Elevated 12dp rounded surface cards with 1dp `outlineVariant` border and tactile touch & rotary focus feedback.
  - Fixed-height two-line title block and subtitle metadata chips ("Movie • 2024" / "TV Series") for consistent horizontal alignment across rows.
  - Built integrated M3 progress bar (`theme.surfaceContainerHighest` track with `theme.primary` fill) for Continue Watching cards.
- **M3 Profile Management Cards ("Switch Nuvio")**:
  - **Active Profile Hero Card**: Upgraded with a 56dp circular avatar (`theme.primaryContainer` with 2dp `theme.primary` border) and an M3 `✓ ACTIVE` badge.
  - **Interactive Profile Switcher Carousel**: Transformed profile cards into 136dp × 172dp interactive surface cards with 14dp rounded corners, full-card click targets, and active check indicators.
  - **"New Profile" Add Card**: Converted to an M3 interactive card with a 52dp `＋` circle and `+ Create` pill button.
- **Episode Synopsis & Detail Layout Fixes**:
  - Eliminated the rigid 66dp height restriction that clipped multi-line episode descriptions in series detail views.
  - Configured dynamic row height matching (`setBaselineAligned(false)` with `card.setMinimumHeight(dp(84))`) so sibling episode cards maintain uniform height without truncation.
  - Added support for up to 3 unclipped lines of episode synopsis with custom line spacing and clean release date metadata.
  - Styled episode numbers with 10dp rounded M3 badges ("E01") and added circular primary play badges.
- **Cleaned Legacy Assets**: Removed unused early prototype web assets (`assets/www/`) to trim APK size.

## [1.0.0] - 2026-11-14

Initial production release of **Nuvio Auto** for Hyundai, Kia, and Genesis Gen5W infotainment systems running Android 4.4.2 KitKat (API 19) via NEMESIS.

### 🛡️ Platform Core & Safety Interlocks (Milestone 1)
- **Single-DEX Dalvik Compliance**: Engineered strictly under the 64K method limit with zero multidex dependencies for pure Android 4.4 KitKat compatibility.
- **Modern TLS 1.2 / 1.3 Engine**: Integrated Google Conscrypt BoringSSL (`org.conscrypt:conscrypt-android:2.5.2`) to enable modern cipher suites and TLS 1.2/1.3 communication on Android 4.4.
- **Mozilla Root CA Trust Store**: Bundled Mozilla's root CA certificate bundle (`cacert.pem`) in app assets to eliminate SSL handshake failures on outdated system trust stores.
- **Hardware Park Gate (`VehicleVideoGate.java`)**: Integrated dynamic reflection against `/system/framework/automotive.jar` (`com.hkmc.micom.ISystemService` and `com.hkmc.regulation.IRegulationManager`) to enforce video display lockout when the vehicle is in gear.
- **Vehicle Motion Guard (`MotionGuard.java`)**: Added GPS-based vehicle speed monitoring with a 2 mph threshold lockout and automatic playback pause.
- **Telematics Modem Protection (`netguard.sh`)**: Background iptables daemon isolates Nuvio's UID traffic strictly to `wlan0`, protecting the vehicle's built-in BlueLink / UVO cellular modem from unauthorized data consumption.

### 🎨 Native Automotive UI & Material Design 3 (Milestone 2)
- **Pure Native UI Architecture**: Eliminated Chromium 33 WebView memory overhead in favor of a responsive, native Android View hierarchy optimized for 1920x720 ultrawide displays.
- **Material Design 3 (M3) Native Theme Engine (`ThemeManager.java`)**: Implemented zero-dependency M3 tonal color hierarchy and shape tokens for Android 4.4 KitKat without exceeding the single-DEX method limit.
- **6 Built-in Automotive Palettes**: Added one-tap selectable M3 themes in *Settings*: **Nuvio Classic** (Default), **Ocean Blue**, **Ioniq Teal** (Hyundai EV), **Kia Crimson**, **Genesis Copper**, and **Daylight** (High-contrast direct sunlight theme).
- **M3 Navigation Rail & Pills**: Upgraded top navigation to full stadium pill buttons (`999dp`) with active indicator selection states.
- **M3 Media Cards & Tonal Elevation**: Transformed catalog posters, Continue Watching items, and Nocturne stream rows into 12dp/14dp rounded M3 cards with `outlineVariant` borders.
- **M3 Filter Chips**: Converted stream quality filters (All, 4K, 1080p, 720p, Torbox) into authentic 8dp M3 filter chips with checked states.
- **M3 Tonal Press Feedback**: Dynamic 14% ARGB touch state blending (`blendArgb`) for responsive, native touch feedback on vehicle touchscreens.
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
