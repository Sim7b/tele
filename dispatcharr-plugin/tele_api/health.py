"""Health of the provider streams, counted from the Dispatcharr system events (plugin event hook).

Kept in one Redis hash, stream id -> decayed counters (core.health_update): a few hundred bytes per stream
that failed or played lately; entries whose failures faded away are dropped.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, Iterable, Optional

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.health")

HEALTH_KEY = "tele_api:health"


def record(redis_client, event: Any, payload: Any) -> bool:
    found = core.event_health(event, payload)
    if found is None or redis_client is None:
        return False
    stream_id, kind = found
    now = time.time()
    try:
        current = redis_client.hget(HEALTH_KEY, str(stream_id))
        state = json.loads(core.to_text(current)) if current else None
    except (ValueError, TypeError):
        state = None
    updated = core.health_update(state if isinstance(state, dict) else None, kind, now)
    if kind == "play" and core.health_score(updated, now) < 0.01:
        # Nothing worth keeping for a stream that only plays
        redis_client.hdel(HEALTH_KEY, str(stream_id))
        return True
    redis_client.hset(HEALTH_KEY, str(stream_id), json.dumps(updated))
    return True


def scores(redis_client, stream_ids: Iterable[int]) -> Dict[str, Dict[str, Any]]:
    ids = [str(i) for i in stream_ids]
    if not ids:
        return {}
    now = time.time()
    values = redis_client.hmget(HEALTH_KEY, ids)
    result = {}
    for stream_id, raw in zip(ids, values):
        state: Optional[Dict[str, Any]] = None
        if raw:
            try:
                parsed = json.loads(core.to_text(raw))
                state = parsed if isinstance(parsed, dict) else None
            except (ValueError, TypeError):
                state = None
        result[stream_id] = core.health_json(state, now)
    return result


def tracked(redis_client) -> int:
    try:
        return int(redis_client.hlen(HEALTH_KEY))
    except Exception:
        return 0
