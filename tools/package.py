#!/usr/bin/env python3
"""
Nuvio on GEN5W - Automated NEMESIS Packaging & Validation Pipeline

Validates:
1. short-desc <= 64 chars and detailed-desc <= 256 chars.
2. Required dependencies: media.codecs, keyboard.hackers, keyboard.activate.
3. Proper POSIX file permissions (0755) for all shell scripts inside the package ZIP.

Generates:
1. dist/packages/nuvio.gen5w.zip & dist/Packages/nuvio.gen5w.zip (Store package)
2. dist/usb/ (USB drive root folder layout)
3. dist/Nuvio-Auto-v{version}-USB-Installer.zip (Official release USB installer)
4. dist/nuvio.gen5w-usb-install.zip (Compatibility link)
"""

import os
import sys
import re
import shutil
import zipfile
import hashlib
import argparse
import subprocess
import configparser

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BUILD_SCRIPT = os.path.join(ROOT_DIR, "tools", "build.py")
DIST_DIR = os.path.join(ROOT_DIR, "dist")
PACKAGES_LOWER = os.path.join(DIST_DIR, "packages")
PACKAGES_UPPER = os.path.join(DIST_DIR, "Packages")
USB_DIR = os.path.join(DIST_DIR, "usb")

APK_PATH = os.path.join(DIST_DIR, "app", "Nuvio-on-GEN5W.apk")
STORE_ZIP_PATH = os.path.join(PACKAGES_LOWER, "nuvio.gen5w.zip")

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


def get_version_info():
    gradle_path = os.path.join(ROOT_DIR, "app", "build.gradle")
    version_name = "1.0.1"
    version_code = 2
    if os.path.isfile(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            content = f.read()
            match_vname = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", content)
            if match_vname:
                version_name = match_vname.group(1)
            match_vcode = re.search(r"versionCode\s+(\d+)", content)
            if match_vcode:
                version_code = int(match_vcode.group(1))
    return version_name, version_code


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


def add_bytes_with_permissions(zip_handle, data_bytes, arc_name, mode):
    zinfo = zipfile.ZipInfo(arc_name)
    zinfo.external_attr = (0o100000 | mode) << 16
    zinfo.compress_type = zipfile.ZIP_DEFLATED
    zip_handle.writestr(zinfo, data_bytes)


def package_store_zip():
    print(f"[*] Creating NEMESIS Store Package: {STORE_ZIP_PATH}...")
    os.makedirs(PACKAGES_LOWER, exist_ok=True)
    os.makedirs(PACKAGES_UPPER, exist_ok=True)

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

    # Also mirror to Packages/ for backward compatibility on case-sensitive filesystems
    upper_store_zip = os.path.join(PACKAGES_UPPER, "nuvio.gen5w.zip")
    if os.path.normcase(os.path.abspath(STORE_ZIP_PATH)) != os.path.normcase(os.path.abspath(upper_store_zip)):
        shutil.copy2(STORE_ZIP_PATH, upper_store_zip)

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


def package_usb_distribution(version_name, version_code):
    print(f"[*] Preparing USB Installation structure in {USB_DIR}...")
    usb_packages_dir = os.path.join(USB_DIR, "packages")
    os.makedirs(usb_packages_dir, exist_ok=True)

    # Generate USB NEMESIS.data
    usb_data_content = f"[nemesis]\nversion={version_name}\ntype=execute\npath=NEMESIS.sh\n"
    usb_data_dst = os.path.join(USB_DIR, "NEMESIS.data")
    with open(usb_data_dst, "w", encoding="utf-8") as f:
        f.write(usb_data_content)
    print(f"    + Created {usb_data_dst}")

    # Generate USB NEMESIS.sh
    nemesis_sh_template = os.path.join(ROOT_DIR, "packaging", "NEMESIS.sh")
    if os.path.isfile(nemesis_sh_template):
        with open(nemesis_sh_template, "r", encoding="utf-8") as f:
            clean_install = f.read()
    else:
        clean_install = ""

    clean_install = clean_install.replace("__VERSION_CODE__", str(version_code))
    usb_sh_dst = os.path.join(USB_DIR, "NEMESIS.sh")
    with open(usb_sh_dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(clean_install)
    try:
        os.chmod(usb_sh_dst, 0o755)
    except Exception:
        pass
    print(f"    + Created {usb_sh_dst} (mode: 0755)")

    # Copy package into USB packages/
    usb_pkg_dst = os.path.join(usb_packages_dir, "nuvio.gen5w.zip")
    shutil.copy2(STORE_ZIP_PATH, usb_pkg_dst)
    print(f"    + Copied package to {usb_pkg_dst}")

    # Build Official USB Installer ZIP: Nuvio-gen5-hmg-nemesis-v{version}-USB-Installer.zip
    official_zip_name = f"Nuvio-gen5-hmg-nemesis-v{version_name}-USB-Installer.zip"
    official_zip_path = os.path.join(DIST_DIR, official_zip_name)
    compat_zip_path = os.path.join(DIST_DIR, "nuvio.gen5w-usb-install.zip")

    print(f"[*] Building official USB Installer archive: {official_zip_path}...")
    with zipfile.ZipFile(official_zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        add_bytes_with_permissions(z, usb_data_content.encode("utf-8"), "NEMESIS.data", 0o644)
        add_bytes_with_permissions(z, clean_install.encode("utf-8"), "NEMESIS.sh", 0o755)
        add_file_with_permissions(z, usb_pkg_dst, "packages/nuvio.gen5w.zip", 0o644)

    # Validate USB Installer archive structure
    print("[*] Validating official USB Installer archive...")
    with zipfile.ZipFile(official_zip_path, "r") as z:
        if z.testzip() is not None:
            print("[!] Error: Corrupted USB installer zip archive", file=sys.stderr)
            sys.exit(1)
        namelist = set(z.namelist())
        expected_files = {"NEMESIS.data", "NEMESIS.sh", "packages/nuvio.gen5w.zip"}
        if namelist != expected_files:
            print(f"[!] Error: USB package contents mismatch. Found: {namelist}, Expected: {expected_files}", file=sys.stderr)
            sys.exit(1)
        sh_content = z.read("NEMESIS.sh").decode("utf-8")
        if f"EXPECTED_CODE={version_code}" not in sh_content:
            print(f"[!] Error: NEMESIS.sh missing expected versionCode {version_code}", file=sys.stderr)
            sys.exit(1)
        data_content = z.read("NEMESIS.data").decode("utf-8")
        if "type=execute" not in data_content or "path=NEMESIS.sh" not in data_content:
            print("[!] Error: NEMESIS.data missing execute path", file=sys.stderr)
            sys.exit(1)

    shutil.copy2(official_zip_path, compat_zip_path)

    print(f"[+] USB Installer ZIP created: {official_zip_path} ({os.path.getsize(official_zip_path)} bytes)")
    print(f"    SHA-256: {sha256_file(official_zip_path)}")


def main():
    parser = argparse.ArgumentParser(description="Nuvio on GEN5W - NEMESIS Packaging Pipeline")
    parser.add_argument("--apk", type=str, help="Path to APK to package (e.g. app/build/outputs/apk/debug/app-debug.apk)")
    args = parser.parse_args()

    print("=" * 60)
    print(" Nuvio on GEN5W - Automated NEMESIS Packaging Pipeline")
    print("=" * 60)

    version_name, version_code = get_version_info()
    print(f"[*] Target App: Nuvio Auto v{version_name} (versionCode: {version_code})")

    manifest_path = os.path.join(ROOT_DIR, "packaging", "NEMESIS.data")
    validate_nemesis_manifest(manifest_path)

    ensure_apk_exists(args.apk)
    package_store_zip()
    package_usb_distribution(version_name, version_code)

    official_zip_name = f"Nuvio-gen5-hmg-nemesis-v{version_name}-USB-Installer.zip"
    official_zip_path = os.path.join(DIST_DIR, official_zip_name)

    print("\n" + "=" * 60)
    print(" [✓] Packaging & Verification Complete! Ready for Vehicle Installation.")
    print("=" * 60)
    print(f" Store Package:  {STORE_ZIP_PATH}")
    print(f" USB Folder:     {USB_DIR}")
    print(f" USB Installer:  {official_zip_path}")
    print("=" * 60)


if __name__ == "__main__":
    main()
