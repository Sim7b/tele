"""Order of the streams of the Dispatcharr channels: the available ones first, then the best quality (core.stream_order).

Dispatcharr plays the first stream of a channel and moves to the next one when it fails: with a dead stream in
first place, every start waits for its startup timeout. The channels enabled in a channel profile are sorted every
STREAM_SORT_EVERY in the background (setting "sort_streams"), or on request from the Télé page:

- available: not failing lately (health.py, from the Dispatcharr events), provider account active, stream still in
  its playlist;
- quality: the resolution Dispatcharr measured when the stream played, else the one its name tells.

Only the channels whose order changes are written; a channel being watched is left as it is (the failover of
Dispatcharr follows the order it started with) and sorted at the next run.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Callable, Dict, Iterator, List, Optional

from . import core, data, health

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.streamsort")

NEXT_KEY = "tele_api:streamsort:next"
LAST_KEY = "tele_api:streamsort:last"
LOCK_KEY = "tele_api:streamsort:lock"
LOCK_TTL = 10 * 60
CHUNK = 2000


def due(redis_client) -> bool:
    """Whether a background run is due: one process at a time takes it, every STREAM_SORT_EVERY."""
    if redis_client is None:
        return False
    try:
        return bool(redis_client.set(NEXT_KEY, "1", nx=True, ex=core.STREAM_SORT_EVERY))
    except Exception:
        return False


def last(redis_client) -> Optional[Dict[str, Any]]:
    try:
        raw = redis_client.get(LAST_KEY) if redis_client is not None else None
        value = json.loads(core.to_text(raw)) if raw else None
    except (TypeError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def describe(summary: Dict[str, Any]) -> str:
    """One line on a run, for the action of the plugin."""
    text = f"{summary.get('changed', 0)} of {summary.get('channels', 0)} channels with several streams put in order"
    if summary.get("playing"):
        text += f", {summary['playing']} being watched left for the next run"
    return text


def playing_channels(redis_client) -> set:
    """Uuids of the channels being watched now (they keep their order until the next run)."""
    if redis_client is None:
        return set()
    found = set()
    for key in data._scan(redis_client, "live:channel:*:clients"):
        channel_key = key[len("live:channel:"): -len(":clients")]
        if channel_key and ":" not in channel_key:
            found.add(channel_key)
    return found


def _rows() -> Iterator[List[Dict[str, Any]]]:
    """The streams of each channel enabled in a channel profile (every channel when there is no profile), in their
    current order, channel after channel."""
    from apps.channels.models import ChannelProfileMembership, ChannelStream

    queryset = ChannelStream.objects.all()
    if ChannelProfileMembership.objects.exists():
        queryset = queryset.filter(
            channel_id__in=ChannelProfileMembership.objects.filter(enabled=True).values("channel_id")
        )
    rows = queryset.order_by("channel_id", "order", "id").values(
        "id", "channel_id", "channel__uuid", "stream_id", "stream__name", "stream__stream_stats",
        "stream__is_stale", "stream__m3u_account__is_active",
    ).iterator(chunk_size=CHUNK)
    current: List[Dict[str, Any]] = []
    for row in rows:
        if current and current[0]["channel_id"] != row["channel_id"]:
            yield current
            current = []
        current.append(row)
    if current:
        yield current


def _write(rows: List[Dict[str, Any]], order: List[int]) -> None:
    from apps.channels.models import ChannelStream
    from django.db import transaction

    position = {stream_id: index for index, stream_id in enumerate(order)}
    links = list(ChannelStream.objects.filter(id__in=[row["id"] for row in rows]))
    for link in links:
        link.order = position.get(link.stream_id, len(order))
    with transaction.atomic():
        ChannelStream.objects.bulk_update(links, ["order"])


def run(redis_client, should_stop: Callable[[], bool] = lambda: False) -> Dict[str, Any]:
    """Sorts the streams of the channels; returns what was done (also kept for the Télé page)."""
    started = time.time()
    summary: Dict[str, Any] = {"at": started, "channels": 0, "changed": 0, "playing": 0, "outcome": "done"}
    if redis_client is not None and not redis_client.set(LOCK_KEY, "1", nx=True, ex=LOCK_TTL):
        summary["outcome"] = "running"
        return summary
    try:
        playing = playing_channels(redis_client)
        for rows in _rows():
            if should_stop():
                summary["outcome"] = "stopped"
                break
            if len(rows) < 2:
                continue
            summary["channels"] += 1
            ids = [row["stream_id"] for row in rows]
            scores = health.scores(redis_client, ids) if redis_client is not None else {}
            streams = [
                {
                    "id": row["stream_id"],
                    "height": core.stream_height(row["stream__stream_stats"], row["stream__name"]),
                    "failing": bool((scores.get(str(row["stream_id"])) or {}).get("unhealthy")),
                    "active": row["stream__m3u_account__is_active"] is not False,
                    "stale": bool(row["stream__is_stale"]),
                }
                for row in rows
            ]
            order = core.stream_order(streams)
            if order == ids:
                continue
            if str(rows[0]["channel__uuid"]) in playing:
                summary["playing"] += 1
                continue
            _write(rows, order)
            summary["changed"] += 1
    except Exception as error:
        logger.exception("Télé API: streams not sorted")
        summary["outcome"] = f"error: {type(error).__name__}"
    finally:
        if redis_client is not None:
            try:
                redis_client.delete(LOCK_KEY)
            except Exception:
                pass
    summary["seconds"] = round(time.time() - started, 1)
    if summary["changed"] or summary["outcome"] != "done":
        logger.info("Télé API: streams sorted %s", summary)
    if redis_client is not None:
        try:
            redis_client.setex(LAST_KEY, 30 * 86400, json.dumps(summary))
        except Exception:
            pass
    return summary
