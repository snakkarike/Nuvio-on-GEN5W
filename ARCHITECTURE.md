# Architecture Blueprint: Nuvio on GEN5W

## 1. Overview & Context

This project ports **Nuvio** to Hyundai & Kia **Gen5W** infotainment systems. It builds directly upon the architecture developed for:
- [Jellyfin-on-GEN5W](https://github.com/ntwm787/Jellyfin-on-GEN5W) (Backported Android 4.4 native ExoPlayer overlay, vehicle safety gate, network guard daemon).
- [Stremio-gen5-hmg-nemesis](https://github.com/guod5/Stremio-gen5-hmg-nemesis) (Stremio addon / debrid streaming adaptation on Gen5 head units).

Nuvio and Stremio share the exact same underlying streaming paradigm:
1. User configures addons (Stremio-compatible JSON manifests).
2. Addons resolve catalog queries and stream URLs (e.g. Real-Debrid, Torbox, HTTPS/HLS streams).
3. The client receives direct HTTP/HTTPS media URLs and passes them to a media player.

---

## 2. Gen5W Technical Constraints

| Property | Gen5W Infotainment Spec | Porting Implication |
|---|---|---|
| **OS** | Android 4.4.2 KitKat (API 19) | Modern Android libraries (AndroidX Media3, Jetpack Compose, Kotlin Multiplatform Android targets) do NOT run out-of-the-box. |
| **CPU** | 32-bit ARM (`armeabi-v7a`) | Native binaries and libraries must target 32-bit ARM. |
| **System WebView** | Chromium 30 / 33 | Modern ES6+, WebAssembly, and modern Web APIs are unsupported unless transpiled or bridged. |
| **RAM** | ~1 GB - 2 GB shared | Heavy JavaScript engines or full BitTorrent clients crash the OS; streams must be direct HTTP/debrid streams. |
| **Telematics Modem** | Embedded LTE Modem (`rmnet0`) | Must be guarded via iptables (`netguard.sh`) to prevent unauthorized cellular billing. |
| **Safety Regulation** | HMG Automotive regulations | Video must be blocked when vehicle is in gear or speed > 0. |

---

## 3. Why NuvioMobile Cannot Be Used Directly

Official Nuvio clients are:
- `NuvioMobile` & `NuvioTV`: Built with **Kotlin Multiplatform (KMP)** and **Compose Multiplatform (Jetpack Compose)**, targeting **API 24+**. Jetpack Compose fundamentally requires API 21+ and Dalvik on Android 4.4 cannot execute the modern Compose runtime.
- `NuvioWeb` / `NuvioTVSmart`: Built with HTML5/JavaScript for browsers and Smart TVs (webOS/Tizen).

### The Chosen Solution: Hybrid Web-to-Native Bridge Pattern
To achieve high fidelity and maintainability, the project implements:
1. **Transpiled Nuvio Frontend (`NuvioWeb`):** Transpiled with Babel targeting ES5 + core-js polyfills so it runs inside Android 4.4's WebView.
2. **Native Android 4.4 Shell & ExoPlayer Bridge:** Reused from `Jellyfin-on-GEN5W`, containing:
   - `NativePlaybackBridge.java`: Exposes `@JavascriptInterface` (`window.NativePlayer.load(...)`).
   - `NativePlaybackService.java`: Controls playback state in background.
   - `NativeVideoOverlay.java`: Renders video on top of the WebView using hardware-accelerated ExoPlayer.
   - `VehicleVideoGate.java`: Queries `/system/framework/automotive.jar` via reflection for Park/stationary status.
3. **NEMESIS Daemon & Installer:**
   - `network-guard/netguard.sh`: Binds traffic to `wlan0`.
   - `packaging/NEMESIS.data`: Registers with NEMESIS app store.

```
+-------------------------------------------------------------+
|                     NEMESIS Manager                         |
+-------------+-------------------------------+---------------+
              | Installs                      | Launches
              v                               v
+---------------------------+   +-----------------------------+
| network-guard (netguard)  |   |      Nuvio-on-GEN5W APK     |
|  - Blocks rmnet0 (Modem)  |   |   (Android 4.4 / API 19)    |
|  - Binds traffic to wlan0 |   +-------------+---------------+
+---------------------------+                 |
        +-------------------------------------+-------------------------------------+
        v                                                                           v
+-------------------------------------------+               +-------------------------------------------+
|       UI / Catalog / Addon Resolver       |               |          Native Playback Engine           |
|  - Transpiled NuvioWeb (Babel ES5)        |  JS Bridge    |  - Vendored ExoPlayer (API 19 backport)   |
|  - Stremio-compatible Addon queries       +-------------->|  - NativeVideoOverlay.java SurfaceView    |
|  - Real-Debrid / Stream URL extraction    | (load/command)|  - VehicleVideoGate.java (Park check)     |
+-------------------------------------------+               +-------------------------------------------+
```
