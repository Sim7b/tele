"""TMDB facts of the VOD titles, looked up once on the server for every Télé TV: the vote and its number of votes,
popularity, countries, studios and original language. The TVs read them from the plugin instead of each looking
every title up on TMDB with a key of its own (thousands of requests per TV).

Kept in the SQLite file of tmdb.py, table "facts", by kind and TMDB id; refreshed after REFRESH_AFTER (votes and
popularity change).
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, Iterable, List, Optional, Set

from . import core, probe, tmdb, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.facts")

BATCH = 1500
PAUSE = 0.08
REFRESH_AFTER = 21 * 86400
MAX_STUDIOS = 6


def _table(connection) -> None:
    connection.execute(
        "CREATE TABLE IF NOT EXISTS facts (kind TEXT, tmdb INTEGER, at REAL, data TEXT, PRIMARY KEY (kind, tmdb))"
    )


def served_tmdb_ids(kind: str) -> Set[int]:
    """TMDB ids of the movies or series of the groups served to the TVs (Dispatcharr's own ids and the plugin's)."""
    from apps.vod.models import Movie, Series

    model = Movie if kind == "movie" else Series
    query = model.objects.filter(m3u_relations__m3u_account__is_active=True)
    selected = probe.selected_ids()
    if selected is not None:
        query = query.filter(m3u_relations__category_id__in=selected)
    found: Set[int] = set()
    items: List[int] = []
    for item_id, tmdb_id in query.distinct().values_list("id", "tmdb_id").iterator(chunk_size=5000):
        number = core.to_int(tmdb_id)
        if number and number > 0:
            found.add(number)
        else:
            items.append(item_id)
    if items:
        ours = tmdb.ids(kind)
        wanted = set(items)
        found.update(int(t) for i, t in ours.items() if int(i) in wanted)
    return found


def _stale(kind: str, wanted: Iterable[int], now: float, limit: int) -> List[int]:
    with tmdb._lock, tmdb._connection() as connection:
        _table(connection)
        known = {row[0]: row[1] for row in connection.execute("SELECT tmdb, at FROM facts WHERE kind = ?", (kind,))}
    missing = [i for i in wanted if i not in known]
    old = sorted((i for i in wanted if i in known and now - known[i] > REFRESH_AFTER), key=lambda i: known[i])
    return (missing + old)[:limit]


def run(settings: Dict[str, Any], should_stop) -> int:
    """Looks up the facts of up to BATCH titles (missing ones first, then the oldest); called with the TMDB lock."""
    key = vault.tmdb_key()
    if not key or not settings.get("tmdb_enabled", True):
        return 0
    language = core.to_text(settings.get("tmdb_language")) or "fr-FR"
    now = time.time()
    done = 0
    for kind, path in (("movie", "/movie/"), ("series", "/tv/")):
        for tmdb_id in _stale(kind, served_tmdb_ids(kind), now, BATCH - done):
            if should_stop():
                return done
            try:
                details = tmdb._get(f"{path}{tmdb_id}", {"language": language}, key)
            except PermissionError:
                logger.warning("Télé API: TMDB refused the key, facts stopped")
                return done
            facts = core.tmdb_facts(details) if details else None
            if details is None:
                time.sleep(PAUSE)
                continue  # Unknown id or TMDB unavailable: kept as is, looked up again later
            with tmdb._lock, tmdb._connection() as connection:
                _table(connection)
                connection.execute(
                    "INSERT OR REPLACE INTO facts (kind, tmdb, at, data) VALUES (?, ?, ?, ?)",
                    (kind, tmdb_id, time.time(), json.dumps(facts, ensure_ascii=False, separators=(",", ":"))),
                )
            done += 1
            time.sleep(PAUSE)
    if done:
        logger.info("Télé API: TMDB facts of %d titles looked up", done)
    return done


def served(kind: str, since: Optional[float] = None) -> Dict[str, Any]:
    """Facts by TMDB id ({"<id>": facts}), the ones changed after [since] only when given, and the time of the newest."""
    query = "SELECT tmdb, at, data FROM facts WHERE kind = ?"
    params: List[Any] = [kind]
    if since:
        query += " AND at > ?"
        params.append(since)
    with tmdb._lock, tmdb._connection() as connection:
        _table(connection)
        rows = connection.execute(query, params).fetchall()
    found: Dict[str, Any] = {}
    newest = since or 0.0
    for tmdb_id, at, raw in rows:
        try:
            found[str(tmdb_id)] = json.loads(raw)
        except ValueError:
            continue
        newest = max(newest, at or 0.0)
    return {"facts": found, "until": newest}


def counts() -> Dict[str, int]:
    with tmdb._lock, tmdb._connection() as connection:
        _table(connection)
        rows = connection.execute("SELECT kind, COUNT(*) FROM facts GROUP BY kind").fetchall()
    return {kind: count for kind, count in rows}
