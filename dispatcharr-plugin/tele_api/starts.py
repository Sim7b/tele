"""How long the channels take to start on this server, for the Télé page.

Dispatcharr writes in the metadata of a running channel when it started it (init_time), when the provider
answered (connection_ready_time) and when its state last changed (state_changed_at: the buffer filled, then the
first viewer). The runner reads them every 15 seconds and keeps each start once:

- provider: from the start to the provider's answer (its connection, a slow DNS, a slow provider);
- buffer: from that answer to the channel ready for the viewers (Dispatcharr waits for about 1 MB of the
  stream, ffmpeg starts when the stream profile uses it).

The last starts are kept in Redis (a day at most); the page shows the medians, the slowest channels and advice.
"""

from __future__ import annotations

import json
import logging
import statistics
import time
from typing import Any, Dict, Iterable, List, Optional

from . import core, data

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.starts")

STARTS_KEY = "tele_api:starts"
SEEN_KEY = "tele_api:starts:seen:{}"
KEEP = 300
KEEP_SECONDS = 24 * 3600
# A state that changed long after the start is not the end of the start (a stream switch, a second viewer)
MAX_START_SECONDS = 120
READY_STATES = ("waiting_for_clients", "active")

# Above, a part of the start is worth a word on the page
SLOW_PROVIDER_MS = 3000
SLOW_BUFFER_MS = 4000
DNS_LIKE_MS = (4500, 5600)


def _number(metadata: Dict[str, Any], field: str) -> Optional[float]:
    return core.to_float(core.to_text(metadata.get(field)))


def measure(key: str, metadata: Dict[str, Any], now: float) -> Optional[Dict[str, Any]]:
    """The start of the running channel [key] from its [metadata], None while it is not over or unknown."""
    state = core.to_text(metadata.get("state"))
    started = _number(metadata, "init_time")
    answered = _number(metadata, "connection_ready_time")
    ready = _number(metadata, "state_changed_at")
    if state not in READY_STATES or not started or not answered or not ready:
        return None
    if not started <= answered <= ready or ready - started > MAX_START_SECONDS or now - started > KEEP_SECONDS:
        return None
    return {
        "key": key,
        "at": round(started, 3),
        "channel": (core.to_text(metadata.get("channel_name")) or "")[:80] or None,
        "stream": core.to_int(core.to_text(metadata.get("stream_id"))),
        "profile": core.to_int(core.to_text(metadata.get("stream_profile"))),
        "provider_ms": int((answered - started) * 1000),
        "buffer_ms": int((ready - answered) * 1000),
        "total_ms": int((ready - started) * 1000),
    }


def observe(redis_client, now: Optional[float] = None) -> int:
    """Keeps the starts of the running channels not seen yet; how many."""
    if redis_client is None:
        return 0
    now = time.time() if now is None else now
    kept = 0
    for metadata_key in data._scan(redis_client, "live:channel:*:metadata"):
        key = metadata_key[len("live:channel:"): -len(":metadata")]
        if not key or ":" in key:
            continue
        try:
            metadata = {core.to_text(k): v for k, v in (redis_client.hgetall(metadata_key) or {}).items()}
            start = measure(key, metadata, now)
            if start is None:
                continue
            # Once per start of the channel
            if not redis_client.set(SEEN_KEY.format(f"{key}:{start['at']}"), "1", nx=True, ex=KEEP_SECONDS):
                continue
            redis_client.lpush(STARTS_KEY, json.dumps(start, separators=(",", ":")))
            redis_client.ltrim(STARTS_KEY, 0, KEEP - 1)
            kept += 1
        except Exception:
            logger.debug("Télé API: start of %s not read", key, exc_info=True)
    return kept


def recent(redis_client, now: Optional[float] = None) -> List[Dict[str, Any]]:
    """The starts kept, newest first, of the last day."""
    if redis_client is None:
        return []
    now = time.time() if now is None else now
    found = []
    for raw in redis_client.lrange(STARTS_KEY, 0, KEEP - 1) or []:
        try:
            start = json.loads(core.to_text(raw))
        except (TypeError, ValueError):
            continue
        if isinstance(start, dict) and now - float(start.get("at") or 0) <= KEEP_SECONDS:
            found.append(start)
    return found


def _median(values: Iterable[int]) -> Optional[int]:
    values = list(values)
    return int(statistics.median(values)) if values else None


def _p90(values: Iterable[int]) -> Optional[int]:
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * 0.9))] if values else None


def summary(starts: List[Dict[str, Any]], profile_names: Optional[Dict[int, str]] = None) -> Dict[str, Any]:
    """Medians of the [starts], the slowest channels, the time by stream profile, and advice."""
    profile_names = profile_names or {}
    totals = [s["total_ms"] for s in starts]
    providers = [s["provider_ms"] for s in starts]
    buffers = [s["buffer_ms"] for s in starts]
    channels: Dict[str, List[int]] = {}
    for start in starts:
        channels.setdefault(start.get("channel") or start["key"], []).append(start["total_ms"])
    slowest = sorted(
        ({"channel": name, "starts": len(values), "median_ms": _median(values)} for name, values in channels.items()),
        key=lambda item: item["median_ms"] or 0, reverse=True,
    )[:10]
    profiles: Dict[str, List[int]] = {}
    for start in starts:
        name = profile_names.get(start.get("profile") or -1) or ("#%s" % start["profile"] if start.get("profile") else "default")
        profiles.setdefault(name, []).append(start["buffer_ms"])
    by_profile = [{"profile": name, "starts": len(values), "buffer_median_ms": _median(values)} for name, values in profiles.items()]
    advice = []
    provider_median, buffer_median = _median(providers), _median(buffers)
    if provider_median is not None:
        dns_like = sum(1 for value in providers if DNS_LIKE_MS[0] <= value <= DNS_LIKE_MS[1])
        if len(providers) >= 5 and dns_like >= len(providers) * 0.6:
            advice.append("dns")
        elif provider_median >= SLOW_PROVIDER_MS:
            advice.append("provider")
    if buffer_median is not None and buffer_median >= SLOW_BUFFER_MS:
        uses_ffmpeg = any("ffmpeg" in (item["profile"] or "").lower() for item in by_profile)
        advice.append("ffmpeg" if uses_ffmpeg else "buffer")
    return {
        "starts": len(starts),
        "median_ms": _median(totals),
        "p90_ms": _p90(totals),
        "provider_median_ms": provider_median,
        "buffer_median_ms": buffer_median,
        "slowest": slowest,
        "profiles": by_profile,
        "advice": advice,
    }
