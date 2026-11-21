"""What the TVs of a user are playing, to resume it on another one, and what a TV sends to another.

- report: a playing TV tells every 30 s what it plays (a channel, or a movie or episode and its position); kept in a
  Redis hash per user, device -> item, read as current for NOW_FRESH seconds;
- others: what the other TVs of the user play now, and those TVs (to send to one of them);
- send / incoming: an item sent to one TV of the same user, kept SEND_FOR seconds until it reads it (once).
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, List, Optional

from . import core, devices

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.handoff")

NOW_FRESH = 90
NOW_KEPT = 86400
SEND_FOR = 120
TV_ONLINE = 10 * 60
NAMES_KEY = "tele_api:handoff:names"


def _now_key(user_id: Any) -> str:
    return f"tele_api:handoff:now:{int(user_id)}"


def _send_key(device: str) -> str:
    return f"tele_api:handoff:send:{device}"


def _load(raw: Any) -> Optional[Dict[str, Any]]:
    try:
        value = json.loads(core.to_text(raw) or "null")
    except (TypeError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def report(redis_client, user_id: Any, device: str, name: Optional[str], item: Optional[Dict[str, Any]]) -> None:
    """What [device] plays now ([item], checked by core.handoff_item), or nothing more (None)."""
    key = _now_key(user_id)
    if name:
        redis_client.hset(NAMES_KEY, device, name)
    if item is None:
        redis_client.hdel(key, device)
        return
    redis_client.hset(key, device, json.dumps({**item, "at": time.time()}))
    redis_client.expire(key, NOW_KEPT)


def others(redis_client, user_id: Any, device: str) -> Dict[str, Any]:
    """What the other TVs of the user play now, and the TVs of the user seen lately (to send to)."""
    now = time.time()
    names = {core.to_text(k): core.to_text(v) for k, v in (redis_client.hgetall(NAMES_KEY) or {}).items()}
    playing: List[Dict[str, Any]] = []
    for other, raw in (redis_client.hgetall(_now_key(user_id)) or {}).items():
        other = core.to_text(other)
        item = _load(raw)
        if other == device or item is None or now - (core.to_float(item.get("at"), 0.0) or 0.0) > NOW_FRESH:
            continue
        playing.append({**item, "device": other, "name": names.get(other)})
    tvs = []
    for other, raw in devices.all_raw(redis_client).items():
        other = core.to_text(other)
        seen = _load(raw)
        if other == device or seen is None or seen.get("user") != str(user_id):
            continue
        if now - (core.to_float(seen.get("seen"), 0.0) or 0.0) <= TV_ONLINE:
            tvs.append({"device": other, "name": names.get(other)})
    return {"playing": sorted(playing, key=lambda i: -(i.get("at") or 0)), "tvs": tvs}


def send(redis_client, user_id: Any, device: str, to: str, item: Dict[str, Any]) -> bool:
    """Sends [item] to the TV [to], when it is a TV of the same user; False otherwise."""
    target = _load(devices.all_raw(redis_client).get(to) or devices.all_raw(redis_client).get(to.encode()))
    if target is None or target.get("user") != str(user_id) or to == device:
        return False
    sender = core.to_text(redis_client.hget(NAMES_KEY, device))
    redis_client.setex(_send_key(to), SEND_FOR, json.dumps({**item, "device": device, "name": sender, "at": time.time()}))
    return True


def incoming(redis_client, device: str) -> Optional[Dict[str, Any]]:
    """What another TV sent to [device], read once."""
    key = _send_key(device)
    raw = redis_client.get(key)
    if raw is None:
        return None
    redis_client.delete(key)
    return _load(raw)
