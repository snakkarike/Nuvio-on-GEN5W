#!/usr/bin/env python3
"""
Nuvio on GEN5W - Automated NEMESIS Packaging & Validation Script

Validates:
1. short-desc <= 64 chars and detailed-desc <= 256 chars.
2. Required dependencies: media.codecs, keyboard.hackers, keyboard.activate.
3. Proper POSIX file permissions (0755) for all shell scripts inside the package ZIP.

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
import argparse
import subprocess
import configparser

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

REQUIRED_DEPENDENCIES = [
    "media.codecs",
    "keyboard.hackers",
    "keyboard.activate",
]


def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def validate_nemesis_manifest(manifest_path):
    print(f"[*] Validating NEMESIS manifest: {manifest_path}...")
    config = configparser.ConfigParser()
    config.read(manifest_path, encoding="utf-8")

    if not config.has_section("nemesis"):
        print("[!] Validation Error: Missing [nemesis] section in NEMESIS.data", file=sys.stderr)
        sys.exit(1)

    nemesis_sec = config["nemesis"]
    short_desc = nemesis_sec.get("short-desc", "")
    detailed_desc = nemesis_sec.get("detailed-desc", "")
    depends = nemesis_sec.get("depends", "")

    # 1. Check description lengths
    print(f"    - Checking short-desc length: {len(short_desc)} / 64 max...")
    if len(short_desc) > 64:
        print(f"[!] Error: short-desc exceeds 64 characters ({len(short_desc)}): '{short_desc}'", file=sys.stderr)
        sys.exit(1)

    print(f"    - Checking detailed-desc length: {len(detailed_desc)} / 256 max...")
    if len(detailed_desc) > 256:
        print(f"[!] Error: detailed-desc exceeds 256 characters ({len(detailed_desc)}): '{detailed_desc}'", file=sys.stderr)
        sys.exit(1)

    # 2. Check dependencies
    print(f"    - Checking declared dependencies: {depends}...")
    for req_dep in REQUIRED_DEPENDENCIES:
        if req_dep not in depends:
            print(f"[!] Error: Missing required NEMESIS dependency: {req_dep}", file=sys.stderr)
            sys.exit(1)

    # 3. Check App and Service definitions
    if not config.has_section("app.0"):
        print("[!] Error: Missing [app.0] section in NEMESIS.data", file=sys.stderr)
        sys.exit(1)

    if not config.has_section("service.0"):
        print("[!] Error: Missing [service.0] section in NEMESIS.data", file=sys.stderr)
        sys.exit(1)

    print("[✓] NEMESIS manifest format and store rules validation PASSED.")


def ensure_apk_exists(custom_apk=None):
    os.makedirs(os.path.join(DIST_DIR, "app"), exist_ok=True)
    if custom_apk:
        if not os.path.isfile(custom_apk):
            print(f"[!] Specified APK does not exist: {custom_apk}", file=sys.stderr)
            sys.exit(1)
        if os.path.abspath(custom_apk) != os.path.abspath(APK_PATH):
            shutil.copy2(custom_apk, APK_PATH)
            print(f"[+] Using specified APK: {custom_apk} -> {APK_PATH}")
        else:
            print(f"[+] Using existing target APK: {APK_PATH}")
        return

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
    zinfo.external_attr = (0o100000 | mode) << 16
    zinfo.compress_type = zipfile.ZIP_DEFLATED
    zip_handle.writestr(zinfo, data)


def package_store_zip():
    print(f"[*] Creating NEMESIS Store Package: {STORE_ZIP_PATH}...")
    os.makedirs(PACKAGES_DIR, exist_ok=True)

    with zipfile.ZipFile(STORE_ZIP_PATH, "w") as z:
        for rel_src, arc_name, mode in FILES_TO_PACK:
            full_src = os.path.join(ROOT_DIR, rel_src)
            if not os.path.isfile(full_src):
                print(f"[!] Error: Missing required component: {full_src}", file=sys.stderr)
                sys.exit(1)
            add_file_with_permissions(z, full_src, arc_name, mode)
            print(f"    + Added {arc_name} (mode: {oct(mode)})")

        add_file_with_permissions(z, APK_PATH, "app/Nuvio-on-GEN5W.apk", 0o644)
        print(f"    + Added app/Nuvio-on-GEN5W.apk ({os.path.getsize(APK_PATH)} bytes)")

    # Verify permissions inside generated ZIP
    print("[*] Verifying file permissions inside generated package ZIP...")
    with zipfile.ZipFile(STORE_ZIP_PATH, "r") as z:
        for info in z.infolist():
            mode = (info.external_attr >> 16) & 0o777
            if info.filename.endswith(".sh"):
                if mode != 0o755:
                    print(f"[!] Error: Script {info.filename} has incorrect permission {oct(mode)}, expected 0o755", file=sys.stderr)
                    sys.exit(1)
            print(f"    - {info.filename:25} permission: {oct(mode)} [OK]")

    print(f"[+] Package created: {STORE_ZIP_PATH} ({os.path.getsize(STORE_ZIP_PATH)} bytes)")
    print(f"    SHA-256: {sha256_file(STORE_ZIP_PATH)}")


def package_usb_distribution():
    print(f"[*] Preparing USB Installation structure in {USB_DIR}...")
    os.makedirs(os.path.join(USB_DIR, "Packages"), exist_ok=True)

    usb_data_src = os.path.join(ROOT_DIR, "packaging", "USB-NEMESIS.data")
    usb_data_dst = os.path.join(USB_DIR, "NEMESIS.data")
    shutil.copy2(usb_data_src, usb_data_dst)
    print(f"    + Created {usb_data_dst}")

    usb_pkg_dst = os.path.join(USB_DIR, "Packages", "nuvio.gen5w.zip")
    shutil.copy2(STORE_ZIP_PATH, usb_pkg_dst)
    print(f"    + Copied package to {usb_pkg_dst}")

    print(f"[*] Building extractable USB Installer archive: {USB_ZIP_PATH}...")
    with zipfile.ZipFile(USB_ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(usb_data_dst, "NEMESIS.data")
        z.write(usb_pkg_dst, "Packages/nuvio.gen5w.zip")

    print(f"[+] USB Installer ZIP created: {USB_ZIP_PATH} ({os.path.getsize(USB_ZIP_PATH)} bytes)")
    print(f"    SHA-256: {sha256_file(USB_ZIP_PATH)}")


def main():
    parser = argparse.ArgumentParser(description="Nuvio on GEN5W - NEMESIS Packaging Pipeline")
    parser.add_argument("--apk", type=str, help="Path to APK to package (e.g. app/build/outputs/apk/debug/app-debug.apk)")
    args = parser.parse_args()

    print("=" * 60)
    print(" Nuvio on GEN5W - Automated NEMESIS Packaging Pipeline")
    print("=" * 60)

    manifest_path = os.path.join(ROOT_DIR, "packaging", "NEMESIS.data")
    validate_nemesis_manifest(manifest_path)

    ensure_apk_exists(args.apk)
    package_store_zip()
    package_usb_distribution()

    print("\n" + "=" * 60)
    print(" [✓] Packaging & Verification Complete! Ready for Vehicle Installation.")
    print("=" * 60)
    print(f" Store ZIP:   {STORE_ZIP_PATH}")
    print(f" USB Folder:  {USB_DIR}")
    print(f" USB ZIP:     {USB_ZIP_PATH}")
    print("=" * 60)


if __name__ == "__main__":
    main()
