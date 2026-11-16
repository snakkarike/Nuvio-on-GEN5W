#!/usr/bin/env python3
"""
Extract release notes for a specific version from CHANGELOG.md.
Usage: python3 tools/extract_changelog.py [version] [output_file]
"""
import sys
import re

def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "1.0.6"
    output_path = sys.argv[2] if len(sys.argv) > 2 else "release_notes.md"
    target_ver = version.lstrip("v")

    try:
        with open("CHANGELOG.md", "r", encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"Error reading CHANGELOG.md: {e}", file=sys.stderr)
        content = ""

    pattern = rf"## \[{re.escape(target_ver)}\][^\n]*\n(.*?)(?=\n## \[|\Z)"
    match = re.search(pattern, content, re.DOTALL)
    if match:
        notes = re.sub(r"\n+---\s*$", "", match.group(1).strip()).strip()
    else:
        notes = f"Release {target_ver}"

    with open(output_path, "w", encoding="utf-8") as out:
        out.write(notes + "\n")

    print(f"Extracted release notes for {target_ver} into {output_path} ({len(notes)} chars)")

if __name__ == "__main__":
    main()
