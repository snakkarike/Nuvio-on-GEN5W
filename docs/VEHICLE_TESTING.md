# Vehicle & Bench Testing Guide: Nuvio on GEN5W

This guide provides step-by-step procedures to validate Nuvio on Hyundai and Kia Gen5W head units (or automotive test bench hardware) running the NEMESIS package manager.

---

## 1. Prerequisites & Dependencies

Before installing Nuvio, ensure the following core packages are installed via NEMESIS:
1. **`media.codecs` (>= 2.1):** Patches the Gen5W video decoding pipeline to enable hardware-accelerated video rendering.
2. **`keyboard.hackers` (>= 2.0.1):** Installs Hacker's Keyboard for full on-screen alphanumeric input.
3. **`keyboard.activate` (>= 2.0.1):** Enables and selects the keyboard input method.
4. **Network:** Vehicle Wi-Fi connected to a mobile hotspot or home network.

---

## 2. USB Installation Procedure

1. Format a USB flash drive (16 GB or smaller recommended) as **FAT32** (or exFAT).
2. Extract `dist/nuvio.gen5w-usb-install.zip` directly onto the root of the USB drive:
   ```
   E:\ (USB Root)
   ├── NEMESIS.data
   └── Packages\
       └── nuvio.gen5w.zip
   ```
3. Insert the USB drive into the vehicle's primary multimedia USB port (usually located below the climate controls or inside the center console).
4. Launch the **NEMESIS App Store / Package Manager** on the Gen5W display.
5. Select **Install from USB** or wait for the automatic autorun notification.
6. Verify that NEMESIS executes `scripts/pre.sh` and completes the package installation.
7. Tap the **Nuvio on GEN5W** icon to launch the application.

---

## 3. Platform & Safety Interlock Verification

### Test 3.1: Parked Video Display
* **Action:** With the vehicle parked (transmission in **P**, parking brake engaged), start playback on any movie or TV series.
* **Expected Result:** The native `SurfaceView` overlay displays full-motion, hardware-accelerated video frames without stutter.

### Test 3.2: Shift Out of Park (Safety Gate)
* **Action:** While media is playing, depress the footbrake and shift the gear selector into **Reverse (R)**, **Neutral (N)**, or **Drive (D)**.
* **Expected Result:**
  - `VehicleVideoGate.java` intercepts the regulation state from `com.hkmc.regulation.IRegulationManager`.
  - The video surface is immediately obscured by the safety warning screen (*"Video Playback Disabled while vehicle is in motion"*).
  - Audio continues seamlessly in the background (or pauses according to OEM policy).

### Test 3.3: Return to Park
* **Action:** Shift transmission back into **Park (P)**.
* **Expected Result:** The safety warning screen dismisses automatically, and the video surface is restored.

---

## 4. Network Guard (`netguard`) Verification

To confirm that the car's BlueLink/UVO telematics cellular modem (`rmnet0`) is fully shielded from app data consumption:

1. Open an ADB shell or NEMESIS terminal:
   ```sh
   adb shell
   su
   ```
2. Check the daemon status:
   ```sh
   cat /data/local/nuvio-gen5w-network/state
   ```
   **Expected output:**
   ```ini
   version=2
   boot_id=...
   uid=10xxx
   interface=wlan0
   ready=1
   ...
   ```
3. Inspect active iptables rules:
   ```sh
   iptables -S OUTPUT | grep NV_G5W
   iptables -S NV_G5W_OUT
   ```
   **Expected output:** Traffic from the Nuvio UID is routed through `NV_G5W_OUT`, allowing egress only on `wlan0` and rejecting any attempt to route through `rmnet0`.

---

## 5. Streaming & Addon Verification

1. Navigate to the **Settings** tab and paste your **Real-Debrid API token**. Tap **Save Preferences**.
2. Switch to the **Movies** tab and tap a popular title (e.g. *Big Buck Bunny* or any popular movie).
3. In the detail modal, wait for streams to populate from installed addons (Torrentio, CyberFlix).
4. Select a stream tagged with `[RD+ Cached]`.
5. Verify that:
   - `DebridService` unrestricts the link into an instant HTTPS URL.
   - `NativePlaybackBridge` sends the URL to `NativePlaybackService`.
   - ExoPlayer starts playback within 1-2 seconds with zero buffer underruns.

---

## 6. Diagnostic Logging

To inspect real-time logs during bench testing:
```sh
adb logcat -v time -s NuvioMainActivity:V NativePlaybackBridge:V NativePlaybackService:V VehicleVideoGate:V NuvioWebJS:V
```

