#!/usr/bin/env python3
"""
Nuvio on GEN5W - Automated NEMESIS Packaging Script

Generates:
1. dist/Packages/nuvio.gen5w.zip (Store package)
2. dist/usb/ (USB flash drive root structure)
3. dist/nuvio.gen5w-usb-install.zip (All-in-one extractable USB zip)
"""

import os
import sys
import shutil
import zipfile
import hashlib
import subprocess

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BUILD_SCRIPT = os.path.join(ROOT_DIR, "tools", "build.py")
DIST_DIR = os.path.join(ROOT_DIR, "dist")
PACKAGES_DIR = os.path.join(DIST_DIR, "Packages")
USB_DIR = os.path.join(DIST_DIR, "usb")

APK_PATH = os.path.join(DIST_DIR, "app", "Nuvio-on-GEN5W.apk")
STORE_ZIP_PATH = os.path.join(PACKAGES_DIR, "nuvio.gen5w.zip")
USB_ZIP_PATH = os.path.join(DIST_DIR, "nuvio.gen5w-usb-install.zip")

FILES_TO_PACK = [
    ("packaging/NEMESIS.data", "NEMESIS.data", 0o644),
    ("packaging/scripts/pre.sh", "scripts/pre.sh", 0o755),
    ("network-guard/netguard.sh", "guard/netguard.sh", 0o755),
    ("network-guard/preflight.sh", "guard/preflight.sh", 0o755),
    ("network-guard/preuninstall.sh", "guard/preuninstall.sh", 0o755),
]


def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def ensure_apk_exists():
    if not os.path.isfile(APK_PATH):
        print(f"[*] APK not found at {APK_PATH}. Invoking build.py...")
        res = subprocess.run([sys.executable, BUILD_SCRIPT])
        if res.returncode != 0:
            print("[!] Build failed.", file=sys.stderr)
            sys.exit(1)


def add_file_with_permissions(zip_handle, src_path, arc_name, mode):
    with open(src_path, "rb") as f:
        data = f.read()

    zinfo = zipfile.ZipInfo(arc_name)
    # Set unix file attributes: standard regular file (0o100000) + permission mode
    zinfo.external_attr = (0o100000 | mode) << 16
    zinfo.compress_type = zipfile.ZIP_DEFLATED
    zip_handle.writestr(zinfo, data)


def package_store_zip():
    print(f"[*] Creating NEMESIS Store Package: {STORE_ZIP_PATH}...")
    os.makedirs(PACKAGES_DIR, exist_ok=True)

    with zipfile.ZipFile(STORE_ZIP_PATH, "w") as z:
        # Add metadata and scripts with proper Linux permissions
        for rel_src, arc_name, mode in FILES_TO_PACK:
            full_src = os.path.join(ROOT_DIR, rel_src)
            if not os.path.isfile(full_src):
                print(f"[!] Error: Missing required component: {full_src}", file=sys.stderr)
                sys.exit(1)
            add_file_with_permissions(z, full_src, arc_name, mode)
            print(f"    + Added {arc_name} (mode: {oct(mode)})")

        # Add APK
        add_file_with_permissions(z, APK_PATH, "app/Nuvio-on-GEN5W.apk", 0o644)
        print(f"    + Added app/Nuvio-on-GEN5W.apk ({os.path.getsize(APK_PATH)} bytes)")

    print(f"[+] Package created: {STORE_ZIP_PATH} ({os.path.getsize(STORE_ZIP_PATH)} bytes)")
    print(f"    SHA-256: {sha256_file(STORE_ZIP_PATH)}")


def package_usb_distribution():
    print(f"[*] Preparing USB Installation structure in {USB_DIR}...")
    os.makedirs(os.path.join(USB_DIR, "Packages"), exist_ok=True)

    # 1. Copy USB-NEMESIS.data to USB root as NEMESIS.data
    usb_data_src = os.path.join(ROOT_DIR, "packaging", "USB-NEMESIS.data")
    usb_data_dst = os.path.join(USB_DIR, "NEMESIS.data")
    shutil.copy2(usb_data_src, usb_data_dst)
    print(f"    + Created {usb_data_dst}")

    # 2. Copy nuvio.gen5w.zip to Packages/
    usb_pkg_dst = os.path.join(USB_DIR, "Packages", "nuvio.gen5w.zip")
    shutil.copy2(STORE_ZIP_PATH, usb_pkg_dst)
    print(f"    + Copied package to {usb_pkg_dst}")

    # 3. Create standalone USB flash zip
    print(f"[*] Building extractable USB Installer archive: {USB_ZIP_PATH}...")
    with zipfile.ZipFile(USB_ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(usb_data_dst, "NEMESIS.data")
        z.write(usb_pkg_dst, "Packages/nuvio.gen5w.zip")

    print(f"[+] USB Installer ZIP created: {USB_ZIP_PATH} ({os.path.getsize(USB_ZIP_PATH)} bytes)")
    print(f"    SHA-256: {sha256_file(USB_ZIP_PATH)}")


def main():
    print("=" * 60)
    print(" Nuvio on GEN5W - Automated NEMESIS Packaging Pipeline")
    print("=" * 60)

    ensure_apk_exists()
    package_store_zip()
    package_usb_distribution()

    print("\n" + "=" * 60)
    print(" [✓] Packaging Complete & Ready for Vehicle Installation!")
    print("=" * 60)
    print(f" Store ZIP:   {STORE_ZIP_PATH}")
    print(f" USB Folder:  {USB_DIR}")
    print(f" USB ZIP:     {USB_ZIP_PATH}")
    print("=" * 60)


if __name__ == "__main__":
    main()

