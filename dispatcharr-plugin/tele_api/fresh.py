"""What is new in the VOD of the server, for the TVs:

- the movies and series added lately (their first provider relation is recent), adult ones left out;
- the latest episode of the series a TV follows (in its list or being watched), with the time Dispatcharr found it.

Dispatcharr finds the episodes of a series when it loads them from the provider (prefetch.py does it every day for
the series watched lately): the series a TV asks about are kept a few days among the ones loaded first, so their new
episodes are found without waiting for a viewer to open them.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, Iterable, List

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.fresh")

FOLLOWED_KEY = "tele_api:fresh:followed"
FOLLOWED_FOR = 3 * 86400
MAX_ADDED = 100
EPISODES_FOR = 30 * 86400


def follow(redis_client, series_ids: Iterable[int]) -> None:
    """Series followed by a TV: loaded first by the prefetch for a few days."""
    ids = [str(i) for i in series_ids]
    if redis_client is None or not ids:
        return
    try:
        now = time.time()
        redis_client.zadd(FOLLOWED_KEY, {i: now for i in ids})
        redis_client.zremrangebyscore(FOLLOWED_KEY, 0, now - FOLLOWED_FOR)
    except Exception:
        logger.debug("Télé API: followed series not kept", exc_info=True)


def followed(redis_client, limit: int = 200) -> List[int]:
    """Series followed lately by the TVs, latest first."""
    if redis_client is None:
        return []
    try:
        values = redis_client.zrevrangebyscore(FOLLOWED_KEY, "+inf", time.time() - FOLLOWED_FOR, start=0, num=limit)
    except Exception:
        return []
    return [i for i in (core.to_int(v) for v in values) if i]


def added(days: int) -> List[Dict[str, Any]]:
    """Movies and series whose first provider relation is from the last [days], newest first, adult ones left out."""
    from datetime import datetime, timedelta, timezone

    from django.db.models import Min

    from apps.vod.models import M3UMovieRelation, M3USeriesRelation

    since = datetime.now(timezone.utc) - timedelta(days=days)
    found = []
    for kind, model, field in (("movie", M3UMovieRelation, "movie_id"), ("series", M3USeriesRelation, "series_id")):
        rows = (
            model.objects.filter(m3u_account__is_active=True)
            .values(field)
            .annotate(first=Min("created_at"))
            .filter(first__gte=since)
            .order_by("-first")[:MAX_ADDED * 2]
        )
        ids = [row[field] for row in rows]
        adult = {
            title_id for title_id, name in model.objects.filter(**{f"{field}__in": ids}).values_list(field, "category__name")
            if core.is_adult_group(name)
        }
        if kind == "movie":
            from apps.vod.models import Movie

            adult |= set(Movie.objects.filter(id__in=ids, is_adult=True).values_list("id", flat=True))
        found += [{"kind": kind, "id": row[field], "at": row["first"].timestamp()} for row in rows if row[field] not in adult]
    return core.newest(found, MAX_ADDED)


def latest_episodes(series_ids: List[int]) -> Dict[str, Dict[str, Any]]:
    """The latest episode found of each series, when found within EPISODES_FOR: {series id: {at, season, episode}}."""
    from apps.vod.models import Episode

    since = time.time() - EPISODES_FOR
    result: Dict[str, Dict[str, Any]] = {}
    rows = (
        Episode.objects.filter(series_id__in=series_ids)
        .order_by("series_id", "-created_at")
        .values("series_id", "created_at", "season_number", "episode_number")
    )
    for row in rows.iterator(chunk_size=2000):
        key = str(row["series_id"])
        if key in result or row["created_at"] is None:
            continue
        at = row["created_at"].timestamp()
        if at >= since:
            result[key] = {"at": at, "season": row["season_number"], "episode": row["episode_number"]}
    return result
