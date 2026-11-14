# Acknowledgments & Credits

Nuvio Auto for Gen5W is built on the collective work, pioneering research, and open-source contributions of the automotive Android and Gen5W developer community. We gratefully acknowledge the following projects and authors:

---

## 1. Gen5 Codec Patch
- **Author / Project:** Sean Kramer ([KramerTech / gen5-codec-patch](https://github.com/KramerTech/gen5-codec-patch))
- **License:** MIT License
- **Contribution:** Identified and resolved the fundamental Android 4.4.2 KitKat media codec instantiation bugs on Telechips TCC803x hardware. Provided the foundation that allows hardware-accelerated H.264 (`OMX.TCC.avcdec`) and software Google decoders to function on Gen5W infotainment systems.

---

## 2. Jellyfin on GEN5W
- **Author / Project:** ntwm787 ([ntwm787 / Jellyfin-on-GEN5W](https://github.com/ntwm787/Jellyfin-on-GEN5W))
- **License:** GNU General Public License v2.0
- **Contribution:** Pioneered native ExoPlayer media playback on Gen5W head units, vehicle video gating (`VehicleVideoGate` via automotive framework interlocks), KitKat single-DEX optimization, and the initial NEMESIS application packaging structure.
- **KeyBORD & KeyBridge:** Special thanks to ntwm787 for [KeyBORD-Gen5W](https://github.com/ntwm787/KeyBORD-Gen5W), providing essential virtual keyboard activation for in-car text entry.

---

## 3. Stremio Auto (Gen5 HMG NEMESIS)
- **Author / Project:** guod5 ([guod5 / Stremio-gen5-hmg-nemesis](https://github.com/guod5/Stremio-gen5-hmg-nemesis))
- **Contribution:** Created the automated NEMESIS USB autorun installer script (`NEMESIS.sh`), clean re-installation routines, and demonstrated modern debrid/Stremio-style streaming workflows on Gen5W head units.

---

## 4. NEMESIS Package Manager
- **Author / Project:** xwtk and contributors ([xwtk / hmg_nemesis](https://github.com/xwtk/hmg_nemesis))
- **Contribution:** The foundational root package management platform and service runtime for Hyundai, Kia, and Genesis Gen5W head units, enabling sideloaded apps and root background daemons.

---

## 5. Network Guard (gen5-SmartTube)
- **Author / Project:** Adapted from KramerTech's [gen5-SmartTube](https://github.com/KramerTech/gen5-SmartTube) per-app UID firewall approach.
- **Contribution:** Protects vehicle owners from unauthorized BlueLink/UVO telematics cellular modem data consumption by isolating third-party app traffic strictly to Wi-Fi, Bluetooth PAN, and USB tethering.

---

## 6. Upstream Nuvio Project
- **Project:** Nuvio ([nuvio.tv](https://nuvio.tv))
- **License:** GNU General Public License v3.0
- **Contribution:** The open-source media ecosystem, addon architecture, and debrid streaming model that inspired this automotive client.
