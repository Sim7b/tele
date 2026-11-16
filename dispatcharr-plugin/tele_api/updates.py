"""The latest version of the Télé app, read from its GitHub releases (kept six hours in Redis), so the Télé page
shows which TVs are behind. The TVs update themselves from the same releases."""

from __future__ import annotations

import json
import logging
import urllib.request
from typing import Any, Dict, Optional

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.updates")

LATEST = "https://api.github.com/repos/Sim7b/tele/releases/latest"
CACHE_KEY = "tele_api:latest_app"
KEEP = 6 * 3600
KEEP_FAILED = 30 * 60


def latest(redis_client) -> Optional[Dict[str, Any]]:
    """{"version", "url"} of the latest release, None when GitHub could not be read."""
    if redis_client is not None:
        try:
            cached = redis_client.get(CACHE_KEY)
            if cached:
                found = json.loads(core.to_text(cached))
                return found or None
        except Exception:
            logger.debug("Télé API: latest version not read from Redis", exc_info=True)
    request = urllib.request.Request(
        LATEST, headers={"Accept": "application/vnd.github+json", "User-Agent": f"Tele API v{core.PLUGIN_VERSION}"}
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            found = core.latest_release(json.loads(response.read(512 * 1024)))
    except (OSError, ValueError):
        found = None
    if redis_client is not None:
        try:
            redis_client.set(CACHE_KEY, json.dumps(found or {}), ex=KEEP if found else KEEP_FAILED)
        except Exception:
            logger.debug("Télé API: latest version not kept", exc_info=True)
    return found
