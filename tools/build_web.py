#!/usr/bin/env python3
"""
Nuvio on GEN5W - Web Assets Build & ES5 Verification Pipeline

Bundles and validates frontend web assets for Android 4.4 KitKat (Chromium 33).
Target output: app/src/main/assets/www/
"""

import os
import re
import sys
import shutil

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_DIR = os.path.join(ROOT_DIR, "web", "src")
OUTPUT_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "assets", "www")

JS_FILES = [
    "polyfills.js",
    "addons.js",
    "debrid.js",
    "api.js",
    "player-bridge.js",
    "ui.js",
    "app.js",
]

# Patterns that would break Android 4.4.2 KitKat's Chromium 33
FORBIDDEN_ES6_PATTERNS = [
    (r"(?<![\w$])let\s+[\w$]+", "ES6 'let' keyword (use 'var')"),
    (r"(?<![\w$])const\s+[\w$]+", "ES6 'const' keyword (use 'var')"),
    (r"=>", "ES6 arrow function (use 'function()')"),
    (r"`[^`]*`", "ES6 template literal (use string concatenation)"),
    (r"(?<![\w$])class\s+[\w$]+", "ES6 'class' declaration (use prototypes)"),
    (r"\.\.\.[\w$]+", "ES6 spread/rest operator"),
    (r"(?<![\w$])async\s+function", "ES8 'async function'"),
    (r"(?<![\w$])await\s+", "ES8 'await' operator"),
]


def check_es5_compliance(content, filename):
    errors = []
    # Strip comments to avoid false alarms
    content_no_comments = re.sub(r"/\*.*?\*/", "", content, flags=re.DOTALL)
    content_no_comments = re.sub(r"//.*", "", content_no_comments)

    for pattern, description in FORBIDDEN_ES6_PATTERNS:
        matches = re.finditer(pattern, content_no_comments)
        for match in matches:
            errors.append(f"{filename}: Found {description} at position {match.start()}")
    return errors


def build():
    print(f"[*] Building Nuvio Web Frontend for Gen5W (KitKat API 19)...")
    print(f"[*] Source directory: {SRC_DIR}")
    print(f"[*] Target directory: {OUTPUT_DIR}")

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # 1. Bundle and Validate JS
    bundled_js = ["/* Nuvio on GEN5W - Bundled ES5 Core */\n"]
    all_errors = []

    for js_name in JS_FILES:
        src_path = os.path.join(SRC_DIR, "js", js_name)
        if not os.path.isfile(src_path):
            print(f"[!] Error: Missing required source file: {src_path}", file=sys.stderr)
            sys.exit(1)

        with open(src_path, "r", encoding="utf-8") as f:
            code = f.read()

        errors = check_es5_compliance(code, js_name)
        if errors:
            all_errors.extend(errors)

        bundled_js.append(f"\n/* --- Begin {js_name} --- */\n")
        bundled_js.append(code)
        bundled_js.append(f"\n/* --- End {js_name} --- */\n")

    if all_errors:
        print("[!] ES5 Compliance Validation Failed:", file=sys.stderr)
        for err in all_errors:
            print(f"    - {err}", file=sys.stderr)
        sys.exit(1)

    print(f"[+] All {len(JS_FILES)} JavaScript modules passed strict ES5 KitKat validation.")

    # Write bundle.js
    bundle_path = os.path.join(OUTPUT_DIR, "bundle.js")
    with open(bundle_path, "w", encoding="utf-8") as f:
        f.write("".join(bundled_js))
    print(f"[+] Written {bundle_path} ({os.path.getsize(bundle_path)} bytes)")

    # 2. Process style.css
    css_src = os.path.join(SRC_DIR, "style.css")
    css_dst = os.path.join(OUTPUT_DIR, "style.css")
    shutil.copy2(css_src, css_dst)
    print(f"[+] Written {css_dst} ({os.path.getsize(css_dst)} bytes)")

    # 3. Process index.html (replace individual script tags with single bundle.js for KitKat WebView performance)
    html_src = os.path.join(SRC_DIR, "index.html")
    with open(html_src, "r", encoding="utf-8") as f:
        html_content = f.read()

    # Replace the multiple script tags with bundle.js
    script_block_pattern = r"<!-- Core Polyfills & Application Scripts \(ES5\) -->.*?</body>"
    replacement = '<script src="bundle.js"></script>\n</body>'
    html_bundled = re.sub(script_block_pattern, replacement, html_content, flags=re.DOTALL)

    html_dst = os.path.join(OUTPUT_DIR, "index.html")
    with open(html_dst, "w", encoding="utf-8") as f:
        f.write(html_bundled)
    print(f"[+] Written {html_dst} ({os.path.getsize(html_dst)} bytes)")

    print("[*] Build completed successfully!")


if __name__ == "__main__":
    build()

