"""Extras of the sheets and the player, looked up by the server for every TV and kept a while:

- the titles that may also please (TMDB recommendations, then similar titles), with the TMDB key of the plugin;
- the intro and end credits of a movie or an episode, from TheIntroDB (a public community base, by TMDB id), so
  a TV can skip the intro and offer the next episode at the right time.

Kept in the SQLite file of tmdb.py (table "extras"): every TV asking for the same title costs one request only.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from . import core, tmdb, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.extras")

INTRODB = "https://api.theintrodb.org/v4/media"
KEEP_FOUND = 14 * 86400
KEEP_MISSING = 2 * 86400
MAX_ANSWER = 512 * 1024


def _table(connection) -> None:
    connection.execute("CREATE TABLE IF NOT EXISTS extras (key TEXT PRIMARY KEY, at REAL, found INTEGER, data TEXT)")


def _cached(key: str) -> Optional[Any]:
    with tmdb._lock, tmdb._connection() as connection:
        _table(connection)
        row = connection.execute("SELECT at, found, data FROM extras WHERE key = ?", (key,)).fetchone()
    if row is None:
        return None
    at, found, data = row
    if time.time() - (at or 0) > (KEEP_FOUND if found else KEEP_MISSING):
        return None
    try:
        return json.loads(data)
    except (TypeError, ValueError):
        return None


def _keep(key: str, value: Any, found: bool) -> None:
    with tmdb._lock, tmdb._connection() as connection:
        _table(connection)
        connection.execute(
            "INSERT OR REPLACE INTO extras (key, at, found, data) VALUES (?, ?, ?, ?)",
            (key, time.time(), 1 if found else 0, json.dumps(value, separators=(",", ":"))),
        )


def segments(tmdb_id: int, season: Optional[int], episode: Optional[int], duration_ms: Optional[int]) -> Dict[str, Any]:
    """Intro, recap, credits and preview of a movie (no season) or an episode, from TheIntroDB; {} when unknown."""
    key = f"seg:{tmdb_id}:{season}:{episode}"
    cached = _cached(key)
    if cached is not None:
        return cached
    params: Dict[str, Any] = {"tmdb_id": tmdb_id}
    if season is not None and episode is not None:
        params.update(season=season, episode=episode)
    if duration_ms:
        params["duration_ms"] = duration_ms
    request = urllib.request.Request(
        f"{INTRODB}?{urllib.parse.urlencode(params)}",
        headers={"Accept": "application/json", "User-Agent": f"Tele API v{core.PLUGIN_VERSION}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            answer = json.loads(response.read(MAX_ANSWER))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            _keep(key, {}, False)
        return {}  # 429 or a failure: not kept, asked again later
    except (OSError, ValueError):
        return {}
    found = core.parse_segments(answer)
    _keep(key, found, bool(found))
    return found


def related(kind: str, tmdb_id: int, language: str) -> Optional[List[str]]:
    """TMDB ids of the titles that may also please (recommendations first, then similar ones); None without key."""
    key_value = vault.tmdb_key()
    if not key_value:
        return None
    key = f"rel:{kind}:{tmdb_id}:{language}"
    cached = _cached(key)
    if cached is not None:
        return cached
    path = f"/movie/{tmdb_id}" if kind == "movie" else f"/tv/{tmdb_id}"
    try:
        details = tmdb._get(path, {"language": language, "append_to_response": "recommendations,similar"}, key_value)
    except PermissionError:
        return None
    if details is None:
        return []
    found = core.related_ids(details)
    _keep(key, found, bool(found))
    return found
