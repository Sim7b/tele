"""Budgets of the shared services: what one user (or one address, for the routes without a key) may spend of the
quotas the server has with OpenSubtitles, TheIntroDB, TMDB, or of its own CPU for the posters.

Counters in Redis per period; without Redis everything is allowed (never a lockout).
"""

from __future__ import annotations

import logging
import time
from typing import Any

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.quota")

KEY = "tele_api:quota:{scope}:{who}:{window}"


def allow(redis_client, scope: str, who: Any, limit: int, period: int) -> bool:
    """True while [who] spent less than [limit] of [scope] in the current [period] (seconds); counts this one."""
    if redis_client is None or limit <= 0:
        return True
    window = int(time.time() // period)
    key = KEY.format(scope=scope, who=str(who)[:64], window=window)
    try:
        used = redis_client.incr(key)
        if used == 1:
            redis_client.expire(key, period + 60)
        return int(used) <= limit
    except Exception:
        logger.debug("Télé API: quota not counted", exc_info=True)
        return True
