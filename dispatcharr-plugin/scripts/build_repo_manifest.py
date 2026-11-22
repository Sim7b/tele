#!/usr/bin/env python3
"""Writes the manifests of the Télé plugin repository for Dispatcharr (Plugins → Repositories → add a URL).

Usage: build_repo_manifest.py <release tag> [previous plugin manifest URL]

Next to dist/tele_api-<version>.zip, it writes:
- dist/manifest.json: the repository, served at
  https://github.com/Sim7b/tele/releases/latest/download/manifest.json (the URL to add in Dispatcharr);
- dist/tele_api.json: the plugin and its versions, with the SHA-256 of each zip (checked by Dispatcharr before it
  installs one).

The versions already published are read from the plugin manifest of the latest release: a plugin version already
listed keeps its entry (its zip in the release that first shipped it), a new one is added first. Only the standard
library: it runs in the release job.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

REPO = "https://github.com/Sim7b/tele"
LATEST = f"{REPO}/releases/latest/download"
SLUG = "tele_api"
MAX_VERSIONS = 15
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def previous_versions(url: Optional[str]) -> List[Dict[str, Any]]:
    """Versions of the plugin manifest published at [url], empty when there is none (first release, offline)."""
    if not url:
        return []
    try:
        with urllib.request.urlopen(url, timeout=20) as response:  # noqa: S310 (fixed https URL)
            data = json.loads(response.read(2_000_000))
    except Exception as error:  # A first release has none: every version starts from this one
        print(f"No previous plugin manifest ({type(error).__name__})", file=sys.stderr)
        return []
    versions = (data.get("manifest", data) if isinstance(data, dict) else {}).get("versions")
    return [v for v in versions if isinstance(v, dict) and v.get("version") and v.get("url")] if isinstance(versions, list) else []


def build(tag: str, plugin: Dict[str, Any], zip_bytes: bytes, previous: List[Dict[str, Any]], now: str) -> Dict[str, Dict[str, Any]]:
    """The repository manifest and the plugin manifest for release [tag] of [plugin] (its plugin.json)."""
    version = str(plugin["version"])
    current = next((v for v in previous if v.get("version") == version), None)
    if current is None:
        current = {
            "version": version,
            "url": f"{REPO}/releases/download/{tag}/{SLUG}-{version}.zip",
            "checksum_sha256": hashlib.sha256(zip_bytes).hexdigest(),
            "size": len(zip_bytes),
            "last_updated": now,
            "changelog": f"{REPO}/releases/tag/{tag}",
        }
    versions = [current] + [v for v in previous if v.get("version") != version]
    versions = versions[:MAX_VERSIONS]
    description = plugin.get("description", "")
    detail = {
        "slug": SLUG,
        "name": plugin.get("name", "Télé API"),
        "description": description,
        "author": "Sim7b",
        "license": "GPL-3.0",
        "repo_url": REPO,
        "registry_url": REPO,
        "registry_name": "Sim7b/tele",
        "last_updated": current["last_updated"],
        "latest": current,
        "versions": versions,
    }
    entry = {
        "slug": SLUG,
        "name": detail["name"],
        "description": description,
        "manifest_url": f"{LATEST}/{SLUG}.json",
        "author": detail["author"],
        "license": detail["license"],
        "last_updated": current["last_updated"],
        "latest_version": version,
        "latest_sha256": current["checksum_sha256"],
        "latest_url": current["url"],
        "latest_size": current.get("size", 0),
    }
    repository = {
        "registry_url": REPO,
        "registry_name": "Sim7b/tele",
        "root_url": f"{REPO}/releases/download",
        "plugins": [entry],
    }
    return {"manifest.json": {"manifest": repository}, f"{SLUG}.json": {"manifest": detail}}


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    tag = sys.argv[1] if sys.argv[1].startswith("v") else f"v{sys.argv[1]}"
    previous_url = sys.argv[2] if len(sys.argv) > 2 else f"{LATEST}/{SLUG}.json"
    with open(os.path.join(ROOT, SLUG, "plugin.json"), encoding="utf-8") as file:
        plugin = json.load(file)
    zip_path = os.path.join(ROOT, "dist", f"{SLUG}-{plugin['version']}.zip")
    with open(zip_path, "rb") as file:
        zip_bytes = file.read()
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for name, content in build(tag, plugin, zip_bytes, previous_versions(previous_url), now).items():
        path = os.path.join(ROOT, "dist", name)
        with open(path, "w", encoding="utf-8") as file:
            json.dump(content, file, ensure_ascii=False, indent=2)
            file.write("\n")
        print(os.path.relpath(path))


if __name__ == "__main__":
    main()
