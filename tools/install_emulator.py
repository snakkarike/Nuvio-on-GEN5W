#!/usr/bin/env python3
"""
Nuvio on GEN5W - KitKat API 19 Emulator Installer Helper

Bypasses modern ADB/Android Studio's 'Error: Unknown option: --user' by
pushing the APK to /data/local/tmp and invoking Android 4.4's native 'pm install -r'.
"""

import os
import sys
import shutil
import subprocess

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def find_best_apk():
    # 1. Check for real compiled APK from Android Studio / Gradle
    candidates = [
        os.path.join(ROOT_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
        os.path.join(ROOT_DIR, "app", "build", "outputs", "apk", "release", "app-release.apk"),
        os.path.join(ROOT_DIR, "app", "build", "outputs", "apk", "release", "app-release-unsigned.apk"),
        os.path.join(ROOT_DIR, "dist", "app", "Nuvio-on-GEN5W.apk"),
    ]
    for c in candidates:
        if os.path.isfile(c) and os.path.getsize(c) > 50000:  # Real compiled APK is > 1MB
            return c
    # Fallback to any existing candidate
    for c in candidates:
        if os.path.isfile(c):
            return c
    return os.path.join(ROOT_DIR, "dist", "app", "Nuvio-on-GEN5W.apk")


def find_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    
    # Try reading sdk.dir from local.properties
    local_props = os.path.join(ROOT_DIR, "local.properties")
    if os.path.isfile(local_props):
        try:
            with open(local_props, "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("sdk.dir="):
                        raw_dir = line.split("=", 1)[1].strip()
                        sdk_dir = raw_dir.replace("\\:", ":").replace("\\\\", os.sep)
                        adb_path = os.path.join(sdk_dir, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
                        if os.path.isfile(adb_path):
                            return adb_path
        except Exception:
            pass

    # Common Android SDK paths on Windows
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    candidates = [
        os.path.join(local_app_data, "Android", "Sdk", "platform-tools", "adb.exe"),
        r"C:\Users\nakka\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        r"C:\Android\Sdk\platform-tools\adb.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def install_on_emulator(apk_path):
    adb = find_adb()
    if not adb:
        print("[!] Error: 'adb' executable not found in PATH or standard Android SDK locations.", file=sys.stderr)
        print("    Please ensure Android SDK platform-tools is installed and adb is in your PATH.", file=sys.stderr)
        sys.exit(1)

    if not os.path.isfile(apk_path):
        print(f"[!] Error: Target APK not found: {apk_path}", file=sys.stderr)
        sys.exit(1)

    print(f"[*] Found ADB: {adb}")
    print(f"[*] Target APK: {apk_path}")

    # Check connected devices
    print("[*] Checking connected emulator/device...")
    res = subprocess.run([adb, "devices"], capture_output=True, text=True)
    print(res.stdout)
    if "device\n" not in res.stdout and "\tdevice" not in res.stdout:
        print("[!] No emulator or device found in 'device' state. Please start your AVD first.", file=sys.stderr)
        sys.exit(1)

    # Check if APK contains stale bundle.js
    try:
        import zipfile
        with zipfile.ZipFile(apk_path, "r") as z:
            if "assets/www/bundle.js" in z.namelist():
                content = z.read("assets/www/bundle.js").decode("utf-8")
                if "function PromisePolyfill(" in content:
                    print("[!] WARNING: The APK file still contains an old cached version of bundle.js!", file=sys.stderr)
                    print("    In Android Studio, click: 'Build' -> 'Clean Project', then 'Build' -> 'Build APK(s)'.", file=sys.stderr)
                    sys.exit(1)
    except Exception as e:
        pass

    # 1. Push APK to /data/local/tmp/
    remote_path = "/data/local/tmp/nuvio.apk"
    print(f"[*] Pushing APK to {remote_path}...")
    res = subprocess.run([adb, "push", apk_path, remote_path])
    if res.returncode != 0:
        print("[!] ADB push failed.", file=sys.stderr)
        sys.exit(1)

    # 2. Run KitKat native pm install -r without modern --user flag
    print("[*] Installing on Android 4.4 (bypassing '--user' flag)...")
    res = subprocess.run([adb, "shell", "pm", "install", "-r", remote_path], capture_output=True, text=True)
    print(res.stdout)
    if res.stderr:
        print(res.stderr, file=sys.stderr)

    if "Success" not in res.stdout:
        print("\n[!] Installation failed: APK was rejected by Android package manager.", file=sys.stderr)
        if "INSTALL_FAILED_INVALID_APK" in res.stdout:
            print("    Reason: The APK file is not a fully compiled & signed Android APK (classes.dex / resources missing).", file=sys.stderr)
            print("    --> In Android Studio: Click 'Build' -> 'Build Bundle(s) / APK(s)' -> 'Build APK(s)'.", file=sys.stderr)
            print("    Then re-run: python tools/install_emulator.py", file=sys.stderr)
        sys.exit(1)

    print("\n[✓] Successfully installed Nuvio on GEN5W on your emulator!")
    
    # 3. Launch the activity
    print("[*] Launching Nuvio on GEN5W...")
    subprocess.run([adb, "shell", "am", "start", "-n", "org.nuvio.gen5w/org.nuvio.gen5w.MainActivity"])


def show_logs():
    adb = find_adb()
    if not adb:
        print("[!] ADB not found.")
        return
    print("=" * 60)
    print(" Nuvio on GEN5W - Emulator Logcat (NuvioWebJS / Chromium)")
    print("=" * 60)
    res = subprocess.run([adb, "logcat", "-d", "-s", "NuvioWebJS", "NuvioMainActivity", "Chromium", "AndroidRuntime"], capture_output=True, text=True)
    lines = res.stdout.strip().splitlines()
    if not lines:
        print("[*] No logs captured yet.")
    else:
        for line in lines[-50:]:
            print(line)
    print("=" * 60)


def install_vlc():
    adb = find_adb()
    if not adb:
        print("[!] ADB executable not found. Make sure Android SDK platform-tools is installed or emulator is running.", file=sys.stderr)
        sys.exit(1)

    print("[*] Detecting device CPU architecture...")
    res = subprocess.run([adb, "shell", "getprop", "ro.product.cpu.abi"], capture_output=True, text=True)
    abi = res.stdout.strip()
    if not abi:
        abi = "x86"
    print(f"[*] Detected device ABI: {abi}")

    if "arm" in abi:
        flavor = "armeabi-v7a"
    else:
        flavor = "x86"

    apk_name = f"VLC-Android-3.3.4-{flavor}.apk"
    vlc_url = f"https://get.videolan.org/vlc-android/3.3.4/{apk_name}"
    cache_dir = os.path.join(ROOT_DIR, "tools", "cache")
    os.makedirs(cache_dir, exist_ok=True)
    vlc_apk = os.path.join(cache_dir, apk_name)

    if not os.path.isfile(vlc_apk) or os.path.getsize(vlc_apk) < 10000000:
        print(f"[*] Downloading KitKat-compatible VLC for Android ({flavor})...")
        print(f"    From: {vlc_url}")
        import urllib.request
        def progress(count, block_size, total_size):
            pct = min(100, int(count * block_size * 100 / max(1, total_size)))
            sys.stdout.write(f"\r    Downloading: {pct}% ({count * block_size // (1024*1024)}MB / {total_size // (1024*1024)}MB)")
            sys.stdout.flush()
        try:
            urllib.request.urlretrieve(vlc_url, vlc_apk, reporthook=progress)
            print("\n[✓] Download complete!")
        except Exception as e:
            print(f"\n[!] Failed to download VLC APK: {e}", file=sys.stderr)
            print(f"    You can manually download: {vlc_url}", file=sys.stderr)
            print(f"    And save it to: {vlc_apk}", file=sys.stderr)
            sys.exit(1)

    remote_path = "/data/local/tmp/vlc.apk"
    print(f"[*] Pushing VLC APK to {remote_path}...")
    subprocess.run([adb, "push", vlc_apk, remote_path], check=True)

    print("[*] Installing VLC on device (KitKat pm install)...")
    res = subprocess.run([adb, "shell", "pm", "install", "-r", remote_path], capture_output=True, text=True)
    print(res.stdout)
    if "Success" in res.stdout:
        print("[✓] VLC for Android successfully installed!")
    else:
        print(f"[!] Installation response: {res.stdout} {res.stderr}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ("--log", "-l", "log"):
        show_logs()
    elif len(sys.argv) > 1 and sys.argv[1] in ("vlc", "--vlc", "install-vlc"):
        install_vlc()
    else:
        target = sys.argv[1] if len(sys.argv) > 1 else find_best_apk()
        install_on_emulator(target)

