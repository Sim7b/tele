"""Télé TVs seen by the plugin: install id (from the User-Agent), user, version of Télé, address, last request.

One Redis hash, install id -> JSON; written at most once a minute per TV.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Optional

from . import core

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.devices")

DEVICES_KEY = "tele_api:devices"
WRITE_EVERY = 60


def seen(redis_client, user_agent: Optional[str], user_id, version: Optional[str], ip: Optional[str]) -> None:
    device = core.device_id(user_agent)
    if device is None or redis_client is None:
        return
    now = time.time()
    try:
        previous = redis_client.hget(DEVICES_KEY, device)
        if previous:
            data = json.loads(core.to_text(previous) or "{}")
            if isinstance(data, dict) and now - (core.to_float(data.get("seen"), 0.0) or 0.0) < WRITE_EVERY \
                    and data.get("user") == str(user_id):
                return
        redis_client.hset(DEVICES_KEY, device, json.dumps(
            {"user": str(user_id), "version": core.app_version(version), "ip": ip, "seen": now}
        ))
    except Exception:
        logger.debug("Télé API: TV not recorded", exc_info=True)


def all_raw(redis_client):
    try:
        return redis_client.hgetall(DEVICES_KEY) or {}
    except Exception:
        return {}


def forget(redis_client, device: str) -> bool:
    try:
        return bool(redis_client.hdel(DEVICES_KEY, device))
    except Exception:
        return False
