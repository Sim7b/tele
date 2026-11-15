"""TMDB ids of the movies and series Dispatcharr has none for, found with the TMDB key of the plugin.

Télé groups the files of a same title by TMDB id: with these ids, the same movie listed under different names by
the providers becomes one title with several versions, on every TV, without a TMDB key on each of them. Kept in a
SQLite file of the data folder (tens of thousands of titles); Dispatcharr's own data is never changed.
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from . import core, probe, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.tmdb")

API = "https://api.themoviedb.org/3"
BATCH = 300
LOCK_KEY = "tele_api:tmdb:lock"
PAUSE = 0.3
RETRY_UNMATCHED_AFTER = 30 * 86400
_lock = threading.Lock()


def _db() -> sqlite3.Connection:
    os.makedirs(probe.data_dir(), exist_ok=True)
    connection = sqlite3.connect(os.path.join(probe.data_dir(), "tmdb.sqlite"), timeout=30)
    connection.execute(
        "CREATE TABLE IF NOT EXISTS ids (kind TEXT, item INTEGER, tmdb INTEGER, at REAL, PRIMARY KEY (kind, item))"
    )
    # Name and search of the titles, to see why some find no sure match (columns added by 0.3.1)
    columns = {row[1] for row in connection.execute("PRAGMA table_info(ids)")}
    # Best candidate and its confidence (0.3.4): kept when below the threshold, to accept it later
    for column, kind in (("name", "TEXT"), ("query", "TEXT"), ("year", "INTEGER"), ("cand", "INTEGER"), ("cand_title", "TEXT"),
                         ("cand_year", "INTEGER"), ("score", "INTEGER")):
        if column not in columns:
            connection.execute(f"ALTER TABLE ids ADD COLUMN {column} {kind}")
    return connection


@contextlib.contextmanager
def _connection():
    """A connection to the file, committed and closed after use."""
    connection = _db()
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def ids(kind: str) -> Dict[str, str]:
    """Dispatcharr id -> TMDB id of the titles matched (movies or series)."""
    with _lock, _connection() as connection:
        rows = connection.execute("SELECT item, tmdb FROM ids WHERE kind = ? AND tmdb IS NOT NULL", (kind,)).fetchall()
    return {str(item): str(tmdb) for item, tmdb in rows}


def counts() -> Dict[str, int]:
    with _lock, _connection() as connection:
        matched = connection.execute("SELECT COUNT(*) FROM ids WHERE tmdb IS NOT NULL").fetchone()[0]
        unmatched = connection.execute("SELECT COUNT(*) FROM ids WHERE tmdb IS NULL").fetchone()[0]
    return {"matched": matched, "unmatched": unmatched}


def unmatched(limit: int = 50) -> List[Dict[str, Any]]:
    """Latest titles without a sure match: their name at the provider and what was searched."""
    with _lock, _connection() as connection:
        rows = connection.execute(
            "SELECT kind, item, name, query, year, cand, cand_title, cand_year, score FROM ids "
            "WHERE tmdb IS NULL AND name IS NOT NULL ORDER BY score IS NULL, score DESC, at DESC LIMIT ?", (limit,)
        ).fetchall()
    return [
        {"kind": kind, "item": item, "name": name, "query": query, "year": year,
         "candidate": {"id": cand, "title": cand_title, "year": cand_year, "score": score} if cand else None}
        for kind, item, name, query, year, cand, cand_title, cand_year, score in rows
    ]


def accept_candidates(min_confidence: int) -> int:
    """Keeps the stored candidates that reach [min_confidence] (after the threshold was lowered): no new request."""
    with _lock, _connection() as connection:
        return connection.execute(
            "UPDATE ids SET tmdb = cand WHERE tmdb IS NULL AND cand IS NOT NULL AND score >= ?", (min_confidence,)
        ).rowcount


def accept(kind: str, item: int) -> bool:
    """Keeps the stored candidate of one title (an admin checked it on the Télé page)."""
    with _lock, _connection() as connection:
        return connection.execute(
            "UPDATE ids SET tmdb = cand, query = 'manual' WHERE kind = ? AND item = ? AND cand IS NOT NULL", (kind, item)
        ).rowcount > 0


def set_manual(kind: str, item: int, tmdb_id: Optional[int]) -> None:
    """TMDB id set by hand by an admin (None: searched again at the next run)."""
    with _lock, _connection() as connection:
        if tmdb_id is None:
            connection.execute("DELETE FROM ids WHERE kind = ? AND item = ?", (kind, item))
        else:
            connection.execute(
                "INSERT INTO ids (kind, item, tmdb, at, name, query, year) VALUES (?, ?, ?, ?, NULL, 'manual', NULL) "
                "ON CONFLICT(kind, item) DO UPDATE SET tmdb = excluded.tmdb, at = excluded.at, query = 'manual'",
                (kind, item, tmdb_id, time.time()),
            )


def export_rows() -> List[Dict[str, Any]]:
    """The TMDB ids found or set by hand, with the provider name of each title (to find it on another server)."""
    with _lock, _connection() as connection:
        rows = connection.execute("SELECT kind, item, tmdb, name, query FROM ids WHERE tmdb IS NOT NULL").fetchall()
    return [{"kind": kind, "item": item, "tmdb": tmdb_id, "name": name or "", "manual": query == "manual"}
            for kind, item, tmdb_id, name, query in rows]


def import_rows(rows: List[tuple]) -> None:
    """(kind, item, tmdb id, name, manual) restored from a backup: they replace what is known of those titles."""
    with _lock, _connection() as connection:
        connection.executemany(
            "INSERT OR REPLACE INTO ids (kind, item, tmdb, at, name, query) VALUES (?, ?, ?, ?, ?, ?)",
            [(kind, item, tmdb_id, time.time(), name or None, "manual" if manual else name or None)
             for kind, item, tmdb_id, name, manual in rows],
        )


def retry_unmatched() -> int:
    """Forgets the titles without a sure match: searched again from the next run."""
    with _lock, _connection() as connection:
        return connection.execute("DELETE FROM ids WHERE tmdb IS NULL").rowcount


def forget() -> None:
    with _lock, _connection() as connection:
        connection.execute("DELETE FROM ids")


def _get(path: str, params: Dict[str, Any], key: str) -> Optional[Dict[str, Any]]:
    headers, auth = core.tmdb_auth(key)
    query = urllib.parse.urlencode({**params, **auth})
    request = urllib.request.Request(f"{API}{path}?{query}", headers={"Accept": "application/json", **headers})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read(2 * 1024 * 1024)
        data = json.loads(raw)
        return data if isinstance(data, dict) else None
    except urllib.error.HTTPError as error:
        if error.code == 429:
            time.sleep(5)
        elif error.code == 401:
            raise PermissionError("TMDB refused the key") from None
        return None
    except (OSError, ValueError):
        return None


def test_key(key: str) -> bool:
    """Whether TMDB accepts [key] (a request that costs nothing)."""
    try:
        return _get("/configuration", {}, key) is not None
    except PermissionError:
        return False


def _pending(kind: str, limit: int) -> List[Dict[str, Any]]:
    """Titles without a TMDB id at Dispatcharr nor here (unmatched ones are retried after a while)."""
    from apps.vod.models import Movie, Series

    model = Movie if kind == "movie" else Series
    with _lock, _connection() as connection:
        done = {row[0] for row in connection.execute(
            "SELECT item FROM ids WHERE kind = ? AND (tmdb IS NOT NULL OR at > ?)", (kind, time.time() - RETRY_UNMATCHED_AFTER)
        )}
    pending = []
    from django.db.models import Q

    query = model.objects.filter(m3u_relations__m3u_account__is_active=True).filter(Q(tmdb_id__isnull=True) | Q(tmdb_id=""))
    from . import probe

    selected = probe.selected_ids()
    if selected is not None:
        # Only the titles of the groups an admin chose to process
        query = query.filter(m3u_relations__category_id__in=selected)
    for row in query.distinct().values("id", "name", "year").order_by("-id").iterator(chunk_size=2000):
        if row["id"] not in done:
            pending.append(row)
            if len(pending) >= limit:
                break
    return pending


def match_batch(settings: Dict[str, Any], should_stop) -> Dict[str, int]:
    """Looks up the TMDB id of up to BATCH titles; stops when [should_stop]() says so."""
    key = vault.tmdb_key()
    if not key or not settings.get("tmdb_enabled", True):
        return {"matched": 0, "unmatched": 0}
    language = core.to_text(settings.get("tmdb_language")) or "fr-FR"
    threshold = core.tmdb_min_confidence(settings.get("tmdb_min_confidence"))
    found = accept_candidates(threshold)
    missed = 0
    for kind, path in (("movie", "/search/movie"), ("series", "/search/tv")):
        for row in _pending(kind, BATCH):
            if should_stop():
                return {"matched": found, "unmatched": missed}
            title, year = core.search_title(row["name"], row.get("year"))
            if not title:
                continue
            params: Dict[str, Any] = {"query": title, "language": language, "include_adult": "false"}
            if year:
                params["year" if kind == "movie" else "first_air_date_year"] = year
            try:
                answer = _get(path, params, key)
                best = core.tmdb_candidate((answer or {}).get("results"), title, year, kind) if answer else None
                if answer is not None and year and (best is None or best["score"] < threshold):
                    # The year of the provider may be wrong: searched again without it
                    time.sleep(PAUSE)
                    params.pop("year", None)
                    params.pop("first_air_date_year", None)
                    again = _get(path, params, key)
                    other = core.tmdb_candidate((again or {}).get("results"), title, year, kind) if again else None
                    if other and (best is None or other["score"] > best["score"]):
                        best = other
            except PermissionError:
                logger.warning("Télé API: TMDB refused the key, matching stopped")
                return {"matched": found, "unmatched": missed}
            if answer is None:
                continue  # TMDB unavailable: tried again at the next run
            tmdb_id = best["id"] if best and best["score"] >= threshold else None
            with _lock, _connection() as connection:
                connection.execute(
                    "INSERT OR REPLACE INTO ids (kind, item, tmdb, at, name, query, year, cand, cand_title, cand_year, score) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (kind, row["id"], tmdb_id, time.time(), (row["name"] or "")[:255], title[:255], year,
                     best["id"] if best else None, best["title"] if best else None, best["year"] if best else None,
                     best["score"] if best else None),
                )
            if tmdb_id:
                found += 1
            else:
                missed += 1
            time.sleep(PAUSE)
    if found or missed:
        logger.info("Télé API: TMDB ids found for %d titles, %d below %d %% of confidence", found, missed, threshold)
    return {"matched": found, "unmatched": missed}


def run(settings: Dict[str, Any], redis_client, should_stop) -> Dict[str, int]:
    """One batch of matching, one at a time on the server (Redis lock)."""
    if redis_client is None or not vault.tmdb_key():
        return {"matched": 0, "unmatched": 0}
    if not redis_client.set(LOCK_KEY, "1", nx=True, ex=15 * 60):
        return {"matched": 0, "unmatched": 0}
    try:
        return match_batch(settings, should_stop)
    finally:
        redis_client.delete(LOCK_KEY)
