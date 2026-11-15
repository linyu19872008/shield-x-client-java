#!/usr/bin/env python3
"""Check relative Markdown links in the repository."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", "target", "node_modules", ".codegraph", ".cache"}
LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")


def strip_code(text: str) -> str:
    return re.sub(r"```.*?```", "", text, flags=re.S)


def main() -> int:
    broken: list[str] = []
    for md in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in md.parts):
            continue
        text = strip_code(md.read_text(encoding="utf-8", errors="replace"))
        for raw in LINK.findall(text):
            target = raw.strip().strip("<>")
            if not target or target.startswith(("#", "http://", "https://", "mailto:")):
                continue
            target = target.split("#", 1)[0]
            if not target or target.startswith("/"):
                continue
            path = (md.parent / target).resolve()
            if not path.exists():
                broken.append(f"{md.relative_to(ROOT)} -> {raw}")
    if broken:
        print("Broken relative links:")
        for item in broken:
            print("  " + item)
        return 1
    print("All relative Markdown links resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
