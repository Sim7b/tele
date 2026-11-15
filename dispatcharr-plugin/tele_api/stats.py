"""Viewing statistics of the admin page, kept STATS_DAYS days in Redis (no personal data: channels and counts).

- starts of each channel per day, from the channel_start events (plugin event hook);
- highest provider connections used per account and day, read every 10 minutes by the plugin task.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict

from . import core

logger = logging.getLogger(__name__)

VIEWS_KEY = "tele_api:stats:views:{day}"
PEAKS_KEY = "tele_api:stats:peaks:{day}"
NAMES_KEY = "tele_api:stats:names"
TTL = (core.STATS_DAYS + 1) * 86400


def record_view(redis_client, payload: Any) -> None:
    if redis_client is None or not isinstance(payload, dict):
        return
    channel = core.to_text(payload.get("channel_id"))
    if not channel or len(channel) > 64:
        return
    key = VIEWS_KEY.format(day=core.day_key(time.time()))
    try:
        redis_client.hincrby(key, channel, 1)
        redis_client.expire(key, TTL)
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


def report(redis_client, days: int) -> Dict[str, Any]:
    from . import data

    day_keys = core.last_days(time.time(), days)
    views = [redis_client.hgetall(VIEWS_KEY.format(day=d)) or {} for d in day_keys]
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
    }
