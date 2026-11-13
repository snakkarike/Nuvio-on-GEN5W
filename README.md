# Nuvio on GEN5W

**Porting the Nuvio streaming & media hub to Hyundai and Kia Gen5W head units via NEMESIS.**

Nuvio is a modern open-source media ecosystem (similar to Stremio) supporting debrid services (Real-Debrid, Torbox, AllDebrid), community streaming addons, Trakt/SIMKL watch history syncing, and automated subtitle management.

This project adapts Nuvio for the Hyundai Motor Group (HMG) **Gen5W** infotainment platform running Android 4.4 KitKat (API 19) on 32-bit ARM hardware, building upon the proven foundation of [Jellyfin-on-GEN5W](https://github.com/ntwm787/Jellyfin-on-GEN5W) and [Stremio-gen5-hmg-nemesis](https://github.com/guod5/Stremio-gen5-hmg-nemesis).

---

## Hardware & System Requirements

- **Target System:** Hyundai / Kia Gen5W AVNT Infotainment System (Software versions containing `S5W_M`).
- **OS / Architecture:** Android 4.4.2 KitKat (API level 19), 32-bit ARM (`armeabi-v7a`).
- **Package Manager:** [NEMESIS](https://github.com/xwtk/hmg_nemesis) root package manager.
- **Dependencies (Installed via NEMESIS):**
  - `media.codecs` (>= 2.1) - Gen5 video codec patch for hardware-accelerated playback.
  - `keyboard.hackers` (>= 2.0.1) - KeyBORD / Hacker's Keyboard for text input.
  - `keyboard.activate` (>= 2.0.1) - KeyBridge activation helper.
- **Network:** Vehicle Wi-Fi or Phone Hotspot (cellular modem traffic is blocked by `netguard`).

---

## Core Safety & Platform Interlocks

1. **Park Gate (`VehicleVideoGate`):** Queries `/system/framework/automotive.jar` (`com.hkmc.micom.ISystemService` and `com.hkmc.regulation.IRegulationManager`) to ensure video only plays when the vehicle is stationary and in Park.
2. **Modem Protection (`network-guard`):** Background iptables daemon (`netguard.sh`) isolates app traffic strictly to `wlan0` to prevent data consumption on the car's built-in BlueLink/UVO telematics modem.
3. **Hardware Playback Engine:** Utilizes an API 19 backported ExoPlayer instance rendering on a native `SurfaceView` overlay to overcome KitKat's outdated Chromium 33 WebView limitations.

---

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md): Comprehensive system architecture, technical constraints, and porting blueprint.
- [ROADMAP.md](ROADMAP.md): Step-by-step milestone plan for building the Nuvio port.
