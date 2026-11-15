"""Messages for one Télé TV or for the TVs of one user, sent from the Télé page (the plugin setting is for all of them).

One Redis hash, "device:<install id>" / "user:<user id>" -> {"text", "until"}; expired ones are dropped when listed.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Dict, List, Optional

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.messages")

MESSAGES_KEY = "tele_api:messages"


def for_tv(redis_client, global_text: Any, user_agent: Optional[str], user_id: Any) -> Optional[str]:
    targeted: Dict[Any, Any] = {}
    if redis_client is not None:
        try:
            targeted = redis_client.hgetall(MESSAGES_KEY) or {}
        except Exception:
            logger.debug("Télé API: targeted messages not read", exc_info=True)
    return core.pick_message(global_text, targeted, core.device_id(user_agent), user_id, time.time())


def send(redis_client, key: str, text: Optional[str], hours: int) -> None:
    if text:
        redis_client.hset(MESSAGES_KEY, key, json.dumps({"text": text[: core.MAX_MESSAGE], "until": time.time() + hours * 3600}))
    else:
        redis_client.hdel(MESSAGES_KEY, key)


def active(redis_client) -> List[Dict[str, Any]]:
    raw = redis_client.hgetall(MESSAGES_KEY) or {}
    now = time.time()
    listed = core.active_messages(raw, now)
    kept = {f"{m['kind']}:{m['target']}" for m in listed}
    stale = [key for key in raw if (core.to_text(key) or "") not in kept]
    if stale:
        redis_client.hdel(MESSAGES_KEY, *stale)
    return listed
