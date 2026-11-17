#!/usr/bin/env python3
"""Raise one of evilo's two independent versions.

Positions carry like an odometer with base 10: raising a higher position resets
the ones below it.

    1.0.0 + z -> 1.0.1
    1.0.9 + z -> 1.1.0      (carry resets z)
    1.9.5 + y -> 2.0.0      (carry resets y and z)

Two versions are tracked separately:

    app      EVILO_VERSION_NAME in project.properties, plus EVILO_VERSION_CODE
    library  evilo-plugin-sdk in gradle/libs.versions.toml

The app version tracks the application release. The library version is the
protocol contract that plugin repositories compile against, so it moves only
when `evilo-library/` changes. Publishing the library under an app bump would
re-upload identical artifacts and spend Maven Central quota for nothing.

Usage:
    ./.github/scripts/bump_version.py app z
    ./.github/scripts/bump_version.py library z
    ./.github/scripts/bump_version.py library z --dry-run
"""

import argparse
import pathlib
import re
import sys

POSITIONS = {"x": 0, "y": 1, "z": 2}


def read(path, pattern):
    text = path.read_text()
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise SystemExit(f"{path} does not contain the expected version line.")
    return text, match.group(1).strip()


def bump(version, position):
    parts = [int(part) for part in version.split(".")]
    if len(parts) != 3 or any(part < 0 for part in parts):
        raise SystemExit(f"Expected x.y.z, found '{version}'.")
    index = POSITIONS[position]
    parts[index] += 1
    # Odometers reset the lower positions when a higher one moves.
    for lower in range(index + 1, 3):
        parts[lower] = 0
    # A single decimal position cannot hold ten; carry it upward.
    for lower in range(2, 0, -1):
        if parts[lower] >= 10:
            parts[lower] = 0
            parts[lower - 1] += 1
    return ".".join(str(part) for part in parts)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("target", choices=["app", "library"], help="version to raise")
    parser.add_argument("position", choices=sorted(POSITIONS), help="position to raise")
    parser.add_argument("--dry-run", action="store_true", help="print the result without writing")
    args = parser.parse_args()

    if args.target == "app":
        properties = pathlib.Path("project.properties")
        if not properties.is_file():
            raise SystemExit("Run this from the repository root; no project.properties here.")
        text, current = read(properties, r"^EVILO_VERSION_NAME=(.*)$")
        _, code = read(properties, r"^EVILO_VERSION_CODE=(.*)$")
        updated = bump(current, args.position)
        print(f"app     {current} -> {updated} (raise {args.position}, versionCode {int(code) + 1})")
        if args.dry_run:
            return
        text = re.sub(r"^EVILO_VERSION_NAME=.*$", f"EVILO_VERSION_NAME={updated}", text, count=1, flags=re.MULTILINE)
        text = re.sub(r"^EVILO_VERSION_CODE=.*$", f"EVILO_VERSION_CODE={int(code) + 1}", text, count=1, flags=re.MULTILINE)
        properties.write_text(text)
        print("Updated project.properties. The library version is untouched.")
        return

    catalog = pathlib.Path("gradle/libs.versions.toml")
    if not catalog.is_file():
        raise SystemExit("Run this from the repository root; no gradle/libs.versions.toml here.")
    text, current = read(catalog, r'^evilo-plugin-sdk = "(.*)"$')
    updated = bump(current, args.position)
    print(f"library {current} -> {updated} (raise {args.position})")
    if args.dry_run:
        return
    text = re.sub(r'^evilo-plugin-sdk = ".*"$', f'evilo-plugin-sdk = "{updated}"', text, count=1, flags=re.MULTILINE)
    catalog.write_text(text)
    print(f"Updated {catalog}. Commit it, then tag library-v{updated}.")


if __name__ == "__main__":
    sys.exit(main())
