"""Provider connection of a paused movie or episode, kept for its resume.

Dispatcharr frees the connection of a VOD session as soon as its request closes, which a long pause ends (the player
reads no more, the web server or the TV close it): another viewer could take it before the resume. A paused TV asks
for a hold: the counter of the provider profile of its session goes up by one, so the connection stays taken whatever
happens to the request; at the resume (or once the hold is over, setting "vod_pause_hold_minutes") it goes down again.

One hold per TV (its install id), in a Redis hash: device -> {"profile", "until"}.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, Optional

from . import core, data

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.holds")

HOLDS_KEY = "tele_api:vod_holds"
DEFAULT_MINUTES = 30
MAX_MINUTES = 240


def _session_profile(redis_client, device: str, user_id: Any) -> Optional[int]:
    """Provider profile of the VOD session of this TV and user (the most recent one)."""
    found = [
        s for s in data.vod_sessions(redis_client)
        if core.device_id(s.user_agent) == device and s.user_id == str(user_id) and s.profile_id
    ]
    if not found:
        return None
    return max(found, key=lambda s: s.connected_at or 0).profile_id


def _get(redis_client, device: str) -> Optional[Dict[str, Any]]:
    try:
        value = json.loads(core.to_text(redis_client.hget(HOLDS_KEY, device)) or "null")
    except (TypeError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def hold(redis_client, device: str, user_id: Any, minutes: int) -> Dict[str, Any]:
    """Holds (or holds longer) the connection of the VOD session of [device]: {"held", "until"}."""
    until = time.time() + max(1, min(minutes, MAX_MINUTES)) * 60
    current = _get(redis_client, device)
    if current is not None:
        current["until"] = until
        redis_client.hset(HOLDS_KEY, device, json.dumps(current))
        return {"held": True, "until": until}
    profile = _session_profile(redis_client, device, user_id)
    if profile is None:
        return {"held": False}
    # Written before the counter goes up: a hold is never counted without being known (and so released)
    if not redis_client.hsetnx(HOLDS_KEY, device, json.dumps({"profile": profile, "until": until, "user": str(user_id)})):
        return hold(redis_client, device, user_id, minutes)
    redis_client.incr(data._profile_key(profile))
    logger.info("Télé API: provider connection of profile %s held for a paused TV", profile)
    return {"held": True, "until": until}


def release(redis_client, device: str) -> bool:
    """Gives the connection held for [device] back; False when it held none."""
    current = _get(redis_client, device)
    # Only the call that removes the hold gives the connection back (two releases at once count once); a broken
    # entry is removed too
    if not redis_client.hdel(HOLDS_KEY, device):
        return False
    profile = core.to_int((current or {}).get("profile"))
    if profile:
        key = data._profile_key(profile)
        if (core.to_int(redis_client.decr(key), 0) or 0) < 0:
            redis_client.set(key, 0)
    return True


def expire(redis_client, now: Optional[float] = None) -> int:
    """Gives back the connections held past their time (a TV left paused, switched off); how many."""
    now = time.time() if now is None else now
    released = 0
    for device, raw in (redis_client.hgetall(HOLDS_KEY) or {}).items():
        try:
            until = float(json.loads(core.to_text(raw)).get("until"))
        except (TypeError, ValueError, AttributeError):
            until = 0.0
        if until <= now and release(redis_client, core.to_text(device)):
            released += 1
    if released:
        logger.info("Télé API: %d provider connections held for paused TVs given back", released)
    return released
