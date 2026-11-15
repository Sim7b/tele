"""Reads Dispatcharr data for the views: database models and the live state kept in Redis.

Dispatcharr internals are imported inside the functions, so that loading the plugin never fails on a server
version that moved one of them; each function documents its fallback.
"""

from __future__ import annotations

import logging
import time
from datetime import timedelta
from typing import Any, Dict, Iterable, Iterator, List, Mapping, Optional

from . import core

logger = logging.getLogger(__name__)

# Programs read after "now" to find the current and the next one of each channel
PROGRAM_WINDOW = timedelta(hours=8)
# Bounds of the Redis scans: a server never has this many viewers, a corrupted keyspace must not hang a request
MAX_SCANNED_KEYS = 5000
MAX_CLIENTS_PER_CHANNEL = 200

_CHANNEL_FIELDS = (
    "id",
    "uuid",
    "name",
    "channel_number",
    "channel_group_id",
    "channel_group__name",
    "logo_id",
    "tvg_id",
    "epg_data_id",
    "epg_data__tvg_id",
    "is_radio",
    "is_catchup",
    "catchup_days",
)
_OVERRIDE_FIELDS = (
    "name",
    "channel_number",
    "channel_group_id",
    "channel_group__name",
    "logo_id",
    "tvg_id",
    "epg_data_id",
    "epg_data__tvg_id",
    "is_radio",
)


def redis_client():
    try:
        from core.utils import RedisClient
    except ImportError:
        return None  # Not inside Dispatcharr (tests)
    try:
        return RedisClient.get_client()
    except Exception:
        logger.exception("Télé API: Redis unavailable")
        return None


# ---------------------------------------------------------------------------------------------------------
# Channels


def visible_channels(user):
    """Channels the user may watch, in the order of the Dispatcharr guide (number, then name)."""
    from django.db.models import F
    from django.db.models.functions import Coalesce

    from apps.channels.access import channels_queryset_for_user
    from apps.channels.models import Channel

    queryset = channels_queryset_for_user(Channel.objects.filter(hidden_from_output=False), user)
    return queryset.annotate(
        tele_number=Coalesce("override__channel_number", "channel_number"),
        tele_name=Coalesce("override__name", "name"),
    ).order_by(F("tele_number").asc(nulls_last=True), "tele_name", "id")


def channel_rows(queryset, start: int, end: int) -> List[Dict[str, Any]]:
    fields = list(_CHANNEL_FIELDS) + [f"override__{f}" for f in _OVERRIDE_FIELDS]
    return list(queryset.values(*fields)[start:end])


def channel_json(row: Mapping[str, Any]) -> Dict[str, Any]:
    def value(field: str) -> Any:
        return core.effective(row.get(field), row.get(f"override__{field}"))

    has_group_override = row.get("override__channel_group_id") is not None
    group_id = value("channel_group_id")
    group_name = row.get("override__channel_group__name") if has_group_override else row.get("channel_group__name")
    has_epg_override = row.get("override__epg_data_id") is not None
    epg_tvg_id = row.get("override__epg_data__tvg_id") if has_epg_override else row.get("epg_data__tvg_id")
    number = core.channel_number(value("channel_number"))
    uuid = str(row["uuid"])
    data: Dict[str, Any] = {
        "id": row["id"],
        "uuid": uuid,
        "number": number,
        "name": value("name") or f"#{number or uuid[:8]}",
        "group": {"id": group_id, "name": group_name} if group_id is not None and group_name else None,
        "logo_id": value("logo_id"),
        # Key of the guide: tvg_id of the linked EPG data, else the tvg_id of the channel
        "tvg_id": epg_tvg_id or value("tvg_id") or None,
        "epg_data_id": value("epg_data_id"),
        "is_radio": bool(value("is_radio")),
    }
    if row.get("is_catchup"):
        data["catchup_days"] = row.get("catchup_days") or 0
    return data


def programs_now_and_next(epg_ids: Iterable[int], now) -> Dict[int, Dict[str, Any]]:
    ids = sorted({i for i in epg_ids if i is not None})
    if not ids:
        return {}
    from apps.epg.models import ProgramData

    rows = (
        ProgramData.objects.filter(epg_id__in=ids, end_time__gt=now, start_time__lt=now + PROGRAM_WINDOW)
        .order_by("epg_id", "start_time")
        .values("epg_id", "title", "sub_title", "start_time", "end_time")
        .iterator(chunk_size=2000)
    )
    return core.now_and_next(
        ({"epg_id": r["epg_id"], "title": r["title"], "sub_title": r["sub_title"], "start": r["start_time"],
          "end": r["end_time"]} for r in rows),
        now,
    )


# ---------------------------------------------------------------------------------------------------------
# Provider connection slots


def profile_limits() -> List[core.ProfileLimit]:
    from apps.m3u.models import M3UAccountProfile

    rows = M3UAccountProfile.objects.filter(m3u_account__is_active=True).values(
        "id", "m3u_account_id", "m3u_account__name", "max_streams", "is_active"
    )
    return [
        core.ProfileLimit(
            profile_id=r["id"],
            account_id=r["m3u_account_id"],
            account_name=r["m3u_account__name"] or "",
            max_streams=r["max_streams"] or 0,
            active=bool(r["is_active"]),
        )
        for r in rows
    ]


def _profile_key(profile_id: int) -> str:
    try:
        from apps.m3u.connection_pool import profile_connections_key

        return profile_connections_key(profile_id)
    except Exception:
        # Key used by Dispatcharr before the connection pool module
        return f"profile_connections:{profile_id}"


def connections_by_profile(profile_ids: Iterable[int]) -> Optional[Dict[int, int]]:
    """Connections open by provider profile: the counters Dispatcharr checks before opening a stream."""
    ids = list(profile_ids)
    client = redis_client()
    if client is None:
        return None
    if not ids:
        return {}
    values = client.mget([_profile_key(i) for i in ids])
    return {i: core.to_int(v, 0) or 0 for i, v in zip(ids, values)}


# ---------------------------------------------------------------------------------------------------------
# Sessions


def _scan(client, pattern: str) -> Iterator[str]:
    for count, key in enumerate(client.scan_iter(match=pattern, count=500)):
        if count >= MAX_SCANNED_KEYS:
            logger.warning("Télé API: scan of %s stopped after %d keys", pattern, MAX_SCANNED_KEYS)
            return
        text = core.to_text(key)
        if text:
            yield text


def live_sessions(client, keys: Optional[Iterable[str]] = None) -> List[core.Session]:
    """Live viewers of every channel, or of the client sets [keys] only."""
    sessions = []
    for key in keys if keys is not None else _scan(client, "live:channel:*:clients"):
        channel_key = key[len("live:channel:"): -len(":clients")]
        if not channel_key or ":" in channel_key:
            continue
        channel_name, profile = client.hmget(f"live:channel:{channel_key}:metadata", "channel_name", "m3u_profile")
        members = list(client.smembers(key))[:MAX_CLIENTS_PER_CHANNEL]
        for member in members:
            client_id = core.to_text(member)
            if not client_id:
                continue
            ua, ip, connected_at, user_id = client.hmget(
                f"live:channel:{channel_key}:clients:{client_id}", "user_agent", "ip_address", "connected_at", "user_id"
            )
            if ua is None and ip is None and connected_at is None:
                continue  # Ghost entry: the client left without its set being cleaned
            sessions.append(
                core.Session(
                    kind="live",
                    client_id=client_id,
                    channel_key=channel_key,
                    title=core.to_text(channel_name),
                    user_id=core.to_text(user_id),
                    ip=core.to_text(ip),
                    user_agent=core.to_text(ua),
                    connected_at=core.to_float(connected_at),
                    profile_id=core.to_int(profile),
                )
            )
    return sessions


_VOD_FIELDS = ("user_id", "client_ip", "client_user_agent", "content_name", "created_at", "m3u_profile_id")


def vod_session(client, session: str) -> Optional[core.Session]:
    values = client.hmget(f"vod_persistent_connection:{session}", *_VOD_FIELDS)
    if all(v is None for v in values):
        return None
    user_id, ip, ua, name, created_at, profile = values
    return core.Session(
        kind="vod",
        client_id=session,
        channel_key=None,
        title=core.to_text(name),
        user_id=core.to_text(user_id),
        ip=core.to_text(ip),
        user_agent=core.to_text(ua),
        connected_at=core.to_float(created_at),
        profile_id=core.to_int(profile),
    )


def vod_sessions(client) -> List[core.Session]:
    sessions = []
    prefix = "vod_persistent_connection:"
    for key in _scan(client, prefix + "*"):
        session = vod_session(client, key[len(prefix):])
        if session is not None:
            sessions.append(session)
    return sessions


def all_sessions() -> Optional[List[core.Session]]:
    client = redis_client()
    if client is None:
        return None
    return live_sessions(client) + vod_sessions(client)


def find_session(kind: str, channel_key: Optional[str], client_id: str) -> Optional[core.Session]:
    """The session behind a session id, read again from Redis (an id sent by a client proves nothing)."""
    client = redis_client()
    if client is None:
        return None
    if kind == "vod":
        return vod_session(client, client_id)
    if not channel_key:
        return None
    for session in live_sessions(client, [f"live:channel:{channel_key}:clients"]):
        if session.client_id == client_id:
            return session
    return None


def stop_session(session: core.Session) -> bool:
    """Asks Dispatcharr to close the connection, as its own stats page does."""
    try:
        if session.kind == "live":
            from apps.proxy.live_proxy.services.channel_service import ChannelService

            result = ChannelService.stop_client(session.channel_key, session.client_id)
            return (result or {}).get("status") != "error"
        client = redis_client()
        if client is None:
            return False
        try:
            from apps.proxy.vod_proxy.multi_worker_connection_manager import get_vod_client_stop_key

            stop_key = get_vod_client_stop_key(session.client_id)
        except Exception:
            stop_key = f"vod_proxy:client:{session.client_id}:stop"
        client.setex(stop_key, 60, "true")
        return True
    except Exception:
        logger.exception("Télé API: session %s not stopped", session.kind)
        return False


def usernames(user_ids: Iterable[Optional[str]]) -> Dict[str, str]:
    ids = sorted({core.to_int(i) for i in user_ids if core.to_int(i)})
    if not ids:
        return {}
    from django.contrib.auth import get_user_model

    return {str(pk): name for pk, name in get_user_model().objects.filter(id__in=ids).values_list("id", "username")}


def adult_categories(category_ids: Iterable[int]) -> set:
    """VOD categories holding adult movies (Dispatcharr marks movies only)."""
    ids = [i for i in category_ids if i is not None]
    if not ids:
        return set()
    from apps.vod.models import M3UMovieRelation

    return set(
        M3UMovieRelation.objects.filter(category_id__in=ids, movie__is_adult=True)
        .values_list("category_id", flat=True).distinct()
    )


def client_ip(request) -> Optional[str]:
    try:
        from dispatcharr.utils import get_client_ip

        return get_client_ip(request)
    except Exception:
        return request.META.get("REMOTE_ADDR")


def server_time() -> float:
    return time.time()
