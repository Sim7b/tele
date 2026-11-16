"""The VOD catalog of the groups served to the TVs, ready for Télé in one gzipped answer per kind: each movie or
series once, with its group, its TMDB id (Dispatcharr's, else the one found by the plugin) and the fields Télé
reads, in the format of the Dispatcharr VOD API. It replaces hundreds of pages of that API and a second pass for
the groups on every TV.

Built from the database in one query per kind, kept in memory while nothing changes (same groups, same number of
titles, no title updated), so a TV asking again costs a cheap check only.
"""

from __future__ import annotations

import gzip
import json
import logging
import threading
import time
from datetime import datetime, timezone as dt_timezone
from typing import Any, Dict, List, Optional, Tuple

from . import core, probe, tmdb

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.catalog")

MAX_DESCRIPTION = 400
_cache: Dict[Tuple[str, bool], Tuple[Any, bytes, float, int]] = {}
_lock = threading.Lock()


def _relations(kind: str):
    from apps.vod.models import M3UMovieRelation, M3USeriesRelation, M3UVODCategoryRelation

    model, title = (M3UMovieRelation, "movie") if kind == "movie" else (M3USeriesRelation, "series")
    enabled = M3UVODCategoryRelation.objects.filter(enabled=True, m3u_account__is_active=True).values_list("category_id", flat=True)
    query = model.objects.filter(m3u_account__is_active=True, category_id__in=enabled)
    selected = probe.selected_ids()
    if selected is not None:
        query = query.filter(category_id__in=selected)
    return query, title


def _signature(query, title: str) -> Any:
    from django.db.models import Count, Max

    found = query.aggregate(count=Count("id"), changed=Max("updated_at"), titles=Max(f"{title}__updated_at"))
    # The TMDB ids the plugin finds change the catalog too
    return (found["count"], str(found["changed"]), str(found["titles"]), str(probe.load_selection()), tmdb.counts()["matched"])


def _iso(value) -> Optional[str]:
    return value.isoformat() if value is not None else None


def build(kind: str, hide_adult: bool, since: Optional[float] = None) -> Tuple[List[Dict[str, Any]], float]:
    """(items, time they were read at): the titles of the groups served, the ones changed after [since] only."""
    query, title = _relations(kind)
    if since:
        from django.db.models import Q

        moment = datetime.fromtimestamp(since, tz=dt_timezone.utc)
        query = query.filter(Q(updated_at__gt=moment) | Q(**{f"{title}__updated_at__gt": moment}) | Q(created_at__gt=moment))
    if hide_adult and kind == "movie":
        query = query.exclude(movie__is_adult=True)
    fields = ("id", "uuid", "name", "year", "logo_id", "description", "genre", "tmdb_id", "imdb_id", "created_at")
    read_at = time.time()
    ours = tmdb.ids(kind)
    items: List[Dict[str, Any]] = []
    seen = set()
    rows = query.order_by(f"{title}__name", f"{title}_id").values_list(*[f"{title}__{f}" for f in fields], "category__name")
    for row in rows.iterator(chunk_size=5000):
        values = dict(zip(fields, row[:-1]))
        group = row[-1]
        if values["id"] in seen or not values["name"]:
            continue
        if hide_adult and core.is_adult_group(group):
            continue
        seen.add(values["id"])
        tmdb_id = core.to_text(values["tmdb_id"])
        if not tmdb_id or tmdb_id == "0":
            tmdb_id = ours.get(str(values["id"]))
        items.append(core.catalog_item(values, group, tmdb_id, MAX_DESCRIPTION))
    return items, read_at


def answer(kind: str, hide_adult: bool, since: Optional[float] = None) -> Tuple[bytes, float, int]:
    """(gzipped JSON, time to ask the changes from, number of titles); the whole catalog is kept while unchanged."""
    if since:
        items, read_at = build(kind, hide_adult, since)
        return _gzip(items), read_at, len(items)
    query, title = _relations(kind)
    signature = _signature(query, title)
    key = (kind, hide_adult)
    with _lock:
        cached = _cache.get(key)
    if cached and cached[0] == signature:
        return cached[1], cached[2], cached[3]
    started = time.monotonic()
    items, read_at = build(kind, hide_adult)
    body = _gzip(items)
    with _lock:
        _cache[key] = (signature, body, read_at, len(items))
    logger.info("Télé API: %s catalog of %d titles built in %.1f s (%d KB gzipped)",
                kind, len(items), time.monotonic() - started, len(body) // 1024)
    return body, read_at, len(items)


def _gzip(items: List[Dict[str, Any]]) -> bytes:
    raw = json.dumps({"api": core.API_VERSION, "results": items}, ensure_ascii=False, separators=(",", ":")).encode()
    return gzip.compress(raw, compresslevel=6)
