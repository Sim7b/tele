"""Viewing statistics of the admin page, kept STATS_DAYS days in Redis (no personal data: channels and counts).

- starts of each channel per day and of every channel per hour, from the channel_start events (plugin event hook);
- highest provider connections used per account and day, read every 10 minutes by the plugin task.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Tuple

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.stats")

VIEWS_KEY = "tele_api:stats:views:{day}"
PEAKS_KEY = "tele_api:stats:peaks:{day}"
HOURS_KEY = "tele_api:stats:hours:{day}"
NAMES_KEY = "tele_api:stats:names"
VOD_KEY = "tele_api:stats:vod:{day}"
VOD_USERS_KEY = "tele_api:stats:vod_users:{day}"
VOD_NAMES_KEY = "tele_api:stats:vod_names"
VOD_SEEN_KEY = "tele_api:stats:vod_seen:{session}"
VOD_SEEN_TTL = 12 * 3600
TTL = (core.STATS_DAYS + 1) * 86400


def record_view(redis_client, payload: Any) -> None:
    if redis_client is None or not isinstance(payload, dict):
        return
    channel = core.to_text(payload.get("channel_id"))
    if not channel or len(channel) > 64:
        return
    now = time.time()
    key = VIEWS_KEY.format(day=core.day_key(now))
    hours = HOURS_KEY.format(day=core.day_key(now))
    try:
        redis_client.hincrby(key, channel, 1)
        redis_client.expire(key, TTL)
        redis_client.hincrby(hours, str(int(now // 3600) % 24), 1)
        redis_client.expire(hours, TTL)
        name = core.to_text(payload.get("channel_name"))
        if name:
            redis_client.hset(NAMES_KEY, channel, name[:200])
    except Exception:
        logger.debug("Télé API: view not counted", exc_info=True)


def sample_peaks(redis_client) -> None:
    """Connections used now by account (sum of its profiles), kept as the day's peak when higher."""
    from . import data

    if redis_client is None:
        return
    try:
        limits = data.profile_limits()
        used = data.connections_by_profile(p.profile_id for p in limits) or {}
        by_account: Dict[int, int] = {}
        for profile in limits:
            by_account[profile.account_id] = by_account.get(profile.account_id, 0) + max(0, used.get(profile.profile_id, 0))
        key = PEAKS_KEY.format(day=core.day_key(time.time()))
        for account, count in by_account.items():
            previous = core.to_int(redis_client.hget(key, str(account)), 0) or 0
            if count > previous:
                redis_client.hset(key, str(account), count)
        redis_client.expire(key, TTL)
    except Exception:
        logger.debug("Télé API: peaks not sampled", exc_info=True)


def sample_vod(redis_client) -> int:
    """Counts the movies and episodes being watched, once per playback session (sampled every 10 minutes)."""
    from . import data

    if redis_client is None:
        return 0
    counted = 0
    try:
        day = core.day_key(time.time())
        for session in data.vod_sessions(redis_client):
            if not redis_client.set(VOD_SEEN_KEY.format(session=session.client_id[:128]), "1", nx=True, ex=VOD_SEEN_TTL):
                continue
            kind, uuid = data.vod_content(redis_client, session.client_id)
            key = core.vod_play_key(kind, uuid, session.title)
            if key is None:
                continue
            redis_client.hincrby(VOD_KEY.format(day=day), key, 1)
            redis_client.expire(VOD_KEY.format(day=day), TTL)
            if session.title:
                redis_client.hset(VOD_NAMES_KEY, key, session.title[:200])
            if session.user_id:
                redis_client.hincrby(VOD_USERS_KEY.format(day=day), session.user_id[:32], 1)
                redis_client.expire(VOD_USERS_KEY.format(day=day), TTL)
            counted += 1
    except Exception:
        logger.debug("Télé API: VOD plays not sampled", exc_info=True)
    return counted


def vod_counts(redis_client, days: int):
    day_keys = core.last_days(time.time(), days)
    return day_keys, [redis_client.hgetall(VOD_KEY.format(day=d)) or {} for d in day_keys]


def report(redis_client, days: int) -> Dict[str, Any]:
    from . import data

    day_keys = core.last_days(time.time(), days)
    views = [redis_client.hgetall(VIEWS_KEY.format(day=d)) or {} for d in day_keys]
    hours = [redis_client.hgetall(HOURS_KEY.format(day=d)) or {} for d in day_keys]
    names = redis_client.hgetall(NAMES_KEY) or {}
    peaks = [redis_client.hgetall(PEAKS_KEY.format(day=d)) or {} for d in day_keys]
    limits: Dict[int, Any] = {}
    for profile in data.profile_limits():
        name, total = limits.get(profile.account_id, (profile.account_name, 0))
        limits[profile.account_id] = (name, total + (profile.max_streams if profile.active else 0))
    return {
        "days": days,
        "top_channels": core.top_channels(views, {core.to_text(k): v for k, v in names.items()}),
        "peaks": core.peaks(day_keys, peaks, limits),
        "daily_starts": core.daily_starts(day_keys, views),
        "hourly_starts_utc": core.hourly_starts(hours),
        **vod_report(redis_client, day_keys),
    }


def vod_report(redis_client, day_keys) -> Dict[str, Any]:
    from . import data

    vod = [redis_client.hgetall(VOD_KEY.format(day=d)) or {} for d in day_keys]
    names = {core.to_text(k): v for k, v in (redis_client.hgetall(VOD_NAMES_KEY) or {}).items()}
    users = [redis_client.hgetall(VOD_USERS_KEY.format(day=d)) or {} for d in day_keys]
    user_ids = {core.to_text(u) for day in users for u in day}
    return {
        "top_vod": core.top_vod(vod, names),
        "vod_by_user": core.users_plays(users, data.usernames(user_ids)),
        "daily_vod": core.daily_starts(day_keys, vod),
    }


def popular(redis_client, days: int, limit: int = 40) -> List[Dict[str, Any]]:
    """Movies and series most played on the server lately, for the TVs: adult titles and groups left out."""
    from apps.vod.models import M3UMovieRelation, M3USeriesRelation, Movie

    plays: Dict[str, int] = {}
    for day in vod_counts(redis_client, days)[1]:
        for key, count in day.items():
            key = core.to_text(key) or ""
            if key.startswith(("movie:", "episode:")):
                plays[key] = plays.get(key, 0) + (core.to_int(count, 0) or 0)
    top = sorted(plays, key=lambda k: -plays[k])[:500]
    movie_uuids = [k.split(":", 1)[1] for k in top if k.startswith("movie:")]
    episode_uuids = [k.split(":", 1)[1] for k in top if k.startswith("episode:")]
    titles: Dict[str, Tuple[str, int]] = {}
    adult = set()
    for row in Movie.objects.filter(uuid__in=movie_uuids).values("id", "uuid", "is_adult"):
        titles[f"movie:{row['uuid']}"] = ("movie", row["id"])
        if row["is_adult"]:
            adult.add(("movie", row["id"]))
    try:
        from apps.vod.models import Episode

        for row in Episode.objects.filter(uuid__in=episode_uuids).values("uuid", "series_id"):
            if row["series_id"]:
                titles[f"episode:{row['uuid']}"] = ("series", row["series_id"])
    except Exception:
        logger.debug("Télé API: episodes not resolved", exc_info=True)
    movie_ids = [i for kind, i in titles.values() if kind == "movie"]
    series_ids = [i for kind, i in titles.values() if kind == "series"]
    for movie_id, name in M3UMovieRelation.objects.filter(movie_id__in=movie_ids).values_list("movie_id", "category__name"):
        if core.is_adult_group(name):
            adult.add(("movie", movie_id))
    for series_id, name in M3USeriesRelation.objects.filter(series_id__in=series_ids).values_list("series_id", "category__name"):
        if core.is_adult_group(name):
            adult.add(("series", series_id))
    return core.popular_titles(plays, titles, adult, limit)
