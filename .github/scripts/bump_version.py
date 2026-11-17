#!/usr/bin/env python3
"""Raise the shared evilo version by one increment of a chosen position.

The app and the plugin SDK publish under the same version, so this is the only
supported way to produce the next one. Positions carry like an odometer with
base 10: incrementing a higher position resets the ones below it.

    1.0.0 + z -> 1.0.1
    1.0.9 + z -> 1.1.0      (carry resets z)
    1.9.5 + y -> 2.0.0      (carry resets y and z)
    1.9.5 + x -> 2.0.0

Usage:
    ./.github/scripts/bump_version.py z      # or y, or x
    ./.github/scripts/bump_version.py z --dry-run
"""

import argparse
import pathlib
import re
import sys

POSITIONS = {"x": 0, "y": 1, "z": 2}
PROPERTIES = pathlib.Path("project.properties")
NAME = "EVILO_VERSION_NAME"
CODE = "EVILO_VERSION_CODE"


def parse(text, key):
    match = re.search(rf"^{key}=(.*)$", text, re.MULTILINE)
    if not match:
        raise SystemExit(f"{PROPERTIES} has no {key}.")
    return match.group(1).strip()


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
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("position", choices=sorted(POSITIONS), help="position to raise")
    parser.add_argument("--dry-run", action="store_true", help="print the result without writing")
    args = parser.parse_args()

    text = PROPERTIES.read_text() if PROPERTIES.is_file() else None
    if text is None:
        raise SystemExit(f"Run this from the repository root; no {PROPERTIES} here.")
    current = parse(text, NAME)
    code = int(parse(text, CODE))
    updated = bump(current, args.position)

    if updated == current:
        raise SystemExit(f"No change: {current} already cannot carry further at {args.position}.")

    print(f"{current} -> {updated} (raise {args.position}, versionCode {code + 1})")
    if args.dry_run:
        return

    text = re.sub(rf"^{NAME}=.*$", f"{NAME}={updated}", text, count=1, flags=re.MULTILINE)
    text = re.sub(rf"^{CODE}=.*$", f"{CODE}={code + 1}", text, count=1, flags=re.MULTILINE)
    PROPERTIES.write_text(text)
    print(f"Updated {PROPERTIES}. Commit it, then tag v{updated}.")


if __name__ == "__main__":
    sys.exit(main())
