"""HTTP endpoints of the Télé API (/api/tele/v1/...).

Authentication is the one of the Dispatcharr API (X-API-Key or JWT). Every endpoint is open to standard
users and only returns what the caller may see: their channels (level, adult filter, channel profiles),
their own sessions. Admins can also list and close the sessions of every user.
"""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time

from django.utils import timezone
from rest_framework import permissions, status
from rest_framework.response import Response
from rest_framework.throttling import BaseThrottle
from rest_framework.views import APIView

from . import core, data, messages, probe, quota, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.views")

PLUGIN_KEY = os.path.basename(os.path.dirname(os.path.abspath(__file__))).lower().replace(" ", "_")

FEATURES = ("channels", "slots", "sessions", "vod_languages", "vod_media", "stream_health", "guide", "sync", "tmdb_ids", "vod_groups", "vod_popular", "vod_facts", "vod_catalog", "vod_segments", "channel_state", "sync_profiles", "vod_hold", "vod_fresh", "handoff")
MAX_RELEASED = 50
_ENABLED_TTL = 5.0
ADMIN_LEVEL = 10
DEFAULT_RATE_LIMIT = 120
# Daily budgets of a standard user on the services the server pays for with its own quotas
SUBTITLE_SEARCHES = 300
SUBTITLE_DOWNLOADS = 40
EXTRAS_PER_DAY = 3000
# Light posters made for one address every 10 minutes (the route needs no key); the made ones are free
POSTERS_MADE = 600
STANDARD_LEVEL = 1


class _EnabledCache:
    """Enabled flag and settings of the plugin, read again every few seconds (a disable reaches every worker)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._read_at = 0.0
        self._value = (False, {})

    def get(self):
        now = time.monotonic()
        with self._lock:
            if now - self._read_at < _ENABLED_TTL:
                return self._value
        try:
            from apps.plugins.models import PluginConfig

            row = PluginConfig.objects.filter(key=PLUGIN_KEY).values("enabled", "settings").first()
            value = (bool(row and row["enabled"]), (row or {}).get("settings") or {})
        except Exception:
            logger.exception("Télé API: plugin state unavailable")
            value = (False, {})
        with self._lock:
            self._read_at, self._value = now, value
        return value


plugin_state = _EnabledCache()


def is_admin(user) -> bool:
    return getattr(user, "user_level", 0) >= ADMIN_LEVEL


def hides_adult(user) -> bool:
    """The Dispatcharr preference "Hide mature content" of a standard user (admins always see everything)."""
    return not is_admin(user) and bool((getattr(user, "custom_properties", None) or {}).get("hide_adult_content"))


class IsStandardUser(permissions.BasePermission):
    """Same rule as the Dispatcharr channel API: a logged-in user of the standard level or above."""

    def has_permission(self, request, view):
        user = request.user
        return bool(user and user.is_authenticated and getattr(user, "user_level", 0) >= STANDARD_LEVEL)


class PluginEnabled(permissions.BasePermission):
    message = "Télé API plugin is disabled"

    def has_permission(self, request, view):
        return plugin_state.get()[0]


class PerUserLimit(BaseThrottle):
    """Requests of a user per minute (setting rate_limit_per_minute, 0 = no limit): a leaked standard key
    cannot keep the database busy. Admins are not limited; without Redis, no limit (never a lockout)."""

    def __init__(self):
        self.wait_seconds = None

    def allow_request(self, request, view):
        user = request.user
        if not user or not user.is_authenticated or is_admin(user):
            return True
        limit = core.query_int(plugin_state.get()[1].get("rate_limit_per_minute"), DEFAULT_RATE_LIMIT, 0, 100_000)
        if limit == 0:
            return True
        client = data.redis_client()
        if client is None:
            return True
        now = time.time()
        key = f"tele_api:rate:{user.pk}:{int(now // 60)}"
        try:
            count = client.incr(key)
            if count == 1:
                client.expire(key, 61)
        except Exception:
            return True
        if core.over_limit(count, limit):
            self.wait_seconds = 60 - int(now % 60)
            return False
        return True

    def wait(self):
        return self.wait_seconds


class TeleView(APIView):
    # Authentication classes: the defaults of the server (JWT and API key)
    permission_classes = [IsStandardUser, PluginEnabled]
    throttle_classes = [PerUserLimit]

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        # A Télé TV (install id in its User-Agent): recorded for the list of the TVs of the admin page
        if core.device_id(request.headers.get("User-Agent")):
            from . import devices

            devices.seen(data.redis_client(), request.headers.get("User-Agent"), request.user.pk,
                         request.headers.get("X-Tele-Version"), data.client_ip(request))

    @staticmethod
    def setting(name, default):
        return plugin_state.get()[1].get(name, default)


def _posters_available() -> bool:
    from . import posters

    return posters.available()


class InfoView(TeleView):
    """Plugin presence, protocol version and rights of the caller: the first call of Télé."""

    def get(self, request):
        user = request.user
        return Response(
            {
                "plugin": PLUGIN_KEY,
                "version": core.PLUGIN_VERSION,
                "api": core.API_VERSION,
                "features": list(FEATURES) + (["subtitles"] if vault.opensubtitles_key() else [])
                + (["vod_posters"] if _posters_available() else [])
                + (["vod_related"] if vault.tmdb_key() else []),
                "server_time": data.server_time(),
                "user": {"username": user.username, "admin": is_admin(user), "hide_adult": hides_adult(user)},
                "release_allowed": bool(self.setting("allow_release", True)),
                "message": messages.for_tv(data.redis_client(), self.setting("tv_message", ""),
                                           request.headers.get("User-Agent"), user.id),
            }
        )


class ChannelsView(TeleView):
    """A page of the channels of the caller, with their group, guide key and current / next program.

    Query: page (from 1), page_size (1-5000, default 1000), programs=0 to leave out the programs (then the
    page has a stable ETag: If-None-Match answers 304 while nothing changed).
    """

    def get(self, request):
        with_programs = request.query_params.get("programs", "1") != "0"
        queryset = data.visible_channels(request.user)
        page = core.page_of(queryset.count(), request.query_params.get("page"), request.query_params.get("page_size"))
        results = [data.channel_json(row) for row in data.channel_rows(queryset, page.start, page.end)]

        tag = core.etag([page.number, page.size, page.count, results])
        if not with_programs and core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})

        if with_programs:
            programs = data.programs_now_and_next((c["epg_data_id"] for c in results), timezone.now())
            for channel in results:
                slot = programs.get(channel["epg_data_id"]) or {}
                channel["now"] = slot.get("now")
                channel["next"] = slot.get("next")

        headers = {} if with_programs else {"ETag": tag}
        return Response(
            {
                "api": core.API_VERSION,
                "count": page.count,
                "page": page.number,
                "pages": page.pages,
                "page_size": page.size,
                "results": results,
            },
            headers=headers,
        )


class GuideView(TeleView):
    """Programs of the caller's channels, from an hour ago to [hours] ahead (1-48, default 24), in the format of
    the Dispatcharr grid (channel objects with their programs). The window moves by whole hours and the answer
    has an ETag: If-None-Match answers 304 while the guide did not change."""

    def get(self, request):
        start, end = core.guide_window(time.time(), request.query_params.get("hours"), request.query_params.get("past"))
        results = data.guide(request.user, start, end)
        tag = core.etag([start, end, results])
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response({"api": core.API_VERSION, "start": start, "end": end, "results": results}, headers={"ETag": tag})


class SyncView(TeleView):
    """Favorites, VOD positions, my list, collections and reminders of the caller, shared by their TVs.

    GET: every item; POST {"changes": {collection: {key: {"v", "t", "d"}}}}: merged item by item (the most recent
    change wins), answers every item. ?profile=<name>: the items of that profile of the TVs (8 per user at most).
    Turned off by the plugin setting allow_sync.
    """

    MAX_BODY = 2 * 1024 * 1024

    def get(self, request):
        from . import sync

        if not self.setting("allow_sync", True):
            return Response({"error": "sync_disabled"}, status=status.HTTP_403_FORBIDDEN)
        profile = core.sync_profile(request.query_params.get("profile"))
        if profile is False:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        return Response({"api": core.API_VERSION, "items": sync.load(request.user.pk, profile)})

    def post(self, request):
        from . import sync

        if not self.setting("allow_sync", True):
            return Response({"error": "sync_disabled"}, status=status.HTTP_403_FORBIDDEN)
        if int(request.META.get("CONTENT_LENGTH") or 0) > self.MAX_BODY:
            return Response({"error": "too_large"}, status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE)
        # A profile of the TVs other than the main one: its own items (?profile=, the same name on every TV)
        profile = core.sync_profile(request.query_params.get("profile"))
        if profile is False:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        if not sync.profile_allowed(request.user.pk, profile):
            return Response({"error": "too_many_profiles"}, status=status.HTTP_409_CONFLICT)
        body = request.data if isinstance(request.data, dict) else {}
        items, kept = sync.merge(request.user.pk, body.get("changes"), profile)
        return Response({"api": core.API_VERSION, "items": items, "kept": kept})


class SlotsView(TeleView):
    """Free provider connections, by account and in total, read live (never cached)."""

    def get(self, request):
        limits = data.profile_limits()
        used = data.connections_by_profile(p.profile_id for p in limits if p.active and p.max_streams > 0)
        if used is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        result = core.slots(limits, used)
        if not is_admin(request.user) and not self.setting("show_provider_names", False):
            # Provider accounts are the business of the admins: counts only for the players
            for account in result["accounts"]:
                account["name"] = None
        return Response({"api": core.API_VERSION, **result})


class SessionsView(TeleView):
    """Open playback sessions of the caller (live channels, movies and episodes).

    this_device marks the sessions opened from the same address with the same player as the request.
    all=1 lists the sessions of every user (admins only).
    """

    def get(self, request):
        sessions = data.all_sessions()
        if sessions is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        admin = is_admin(request.user)
        everyone = admin and core.query_bool(request.query_params.get("all"))
        return Response(
            {
                "api": core.API_VERSION,
                "sessions": core.visible_sessions(
                    sessions,
                    request.user.pk,
                    admin=admin,
                    everyone=everyone,
                    ip=data.client_ip(request),
                    user_agent=request.headers.get("User-Agent"),
                    usernames=data.usernames(s.user_id for s in sessions) if everyone else None,
                ),
            }
        )


class ReleaseSessionsView(TeleView):
    """Closes sessions: {"ids": [...]} and / or {"this_device": true, "keep": [...]}.

    A standard user can only close their own sessions; each id is checked against Redis, not trusted.
    """

    def post(self, request):
        if not self.setting("allow_release", True):
            return Response({"error": "release_disabled"}, status=status.HTTP_403_FORBIDDEN)
        body = request.data if isinstance(request.data, dict) else {}
        ids = body.get("ids") or []
        keep = body.get("keep") or []
        if not isinstance(ids, list) or not isinstance(keep, list) or len(ids) > MAX_RELEASED:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)

        user = request.user
        admin = is_admin(user)
        targets = {}
        for raw in ids:
            parsed = core.parse_session_id(raw)
            if parsed is None:
                return Response({"error": "invalid_session_id"}, status=status.HTTP_400_BAD_REQUEST)
            session = data.find_session(*parsed)
            if core.may_release(session, user.pk, admin=admin):
                targets[session.id] = session

        if body.get("this_device") is True:
            sessions = data.all_sessions()
            if sessions is None:
                return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
            keep_ids = {core.to_text(k) for k in keep}
            ip, agent = data.client_ip(request), request.headers.get("User-Agent")
            for session in sessions:
                if (
                    session.user_id == str(user.pk)
                    and core.is_same_device(session, ip, agent)
                    and session.id not in keep_ids
                ):
                    targets[session.id] = session

        if body.get("this_device") is True:
            # A viewer short of a connection (Télé on HTTP 503): a running VOD language probe gives its own back
            client = data.redis_client()
            if client is not None:
                probe.ask_to_yield(client)

        released = [sid for sid, session in targets.items() if data.stop_session(session)]
        if released:
            logger.info("Télé API: %d session(s) released by user %s", len(released), user.pk)
        return Response({"api": core.API_VERSION, "released": released})


class StreamHealthView(TeleView):
    """Recent health of provider streams (?ids=1,2,3, at most 100): decayed failures, unhealthy flag."""

    def get(self, request):
        from . import health

        ids = core.parse_ids(request.query_params.get("ids"))
        if ids is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response({"api": core.API_VERSION, "streams": health.scores(client, ids)})


class ChannelStateView(TeleView):
    """What Dispatcharr is doing with a channel a TV is starting (?key=channel uuid or stream hash of the proxy
    address): {"state", "since", "stream", "chunks"}, state null when the channel is not running. Only for a channel
    the user may watch; no name nor address."""

    def get(self, request):
        import uuid as uuidlib

        from django.db.models import Q

        key = core.proxy_key(request.query_params.get("key"))
        if key is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        try:
            channel_uuid = str(uuidlib.UUID(key))
            allowed = Q(uuid=channel_uuid)
        except ValueError:
            allowed = Q(streams__stream_hash=key)
        if not data.visible_channels(request.user).filter(allowed).exists():
            return Response({"error": "not_found"}, status=status.HTTP_404_NOT_FOUND)
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        metadata = client.hgetall(f"live:channel:{key}:metadata")
        chunks = client.get(f"live:channel:{key}:input:buffer:index")
        return Response({"api": core.API_VERSION, **core.channel_state(metadata, chunks, time.time())})


class VodFreshView(TeleView):
    """What is new in the VOD (fresh.py): {"added": [{"kind", "id", "at"}] (?days= 1-30, 7 by default), "episodes":
    {series id: {"at", "season", "episode"}}} for the series of ?series=1,2,3 (the ones the TV follows, 100 at most)."""

    def get(self, request):
        from . import fresh

        days = core.query_int(request.query_params.get("days"), 7, 1, 30)
        series = request.query_params.get("series")
        ids = core.parse_ids(series) if series else []
        if ids is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        fresh.follow(data.redis_client(), ids)
        answer = {"api": core.API_VERSION, "days": days, "added": fresh.added(days), "episodes": fresh.latest_episodes(ids) if ids else {}}
        tag = core.etag(answer)
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response(answer, headers={"ETag": tag})


class HandoffView(TeleView):
    """What the TVs of the caller play, to resume it on another one (handoff.py), told by the User-Agent.

    GET: {"playing": [{"device", "name", "kind", "key", "title", "position_ms", "duration_ms", "at"}], "tvs":
    [{"device", "name"}], "incoming": what another TV sent here (read once) or null}. POST {"name", "playing": item
    or null}: what this TV plays now.
    """

    def _device(self, request):
        return core.device_id(request.headers.get("User-Agent"))

    def get(self, request):
        from . import handoff

        device = self._device(request)
        client = data.redis_client()
        if device is None or client is None:
            return Response({"error": "unknown_device" if device is None else "redis_unavailable"}, status=status.HTTP_400_BAD_REQUEST)
        answer = handoff.others(client, request.user.pk, device)
        return Response({"api": core.API_VERSION, **answer, "incoming": handoff.incoming(client, device)})

    def post(self, request):
        from . import handoff

        device = self._device(request)
        client = data.redis_client()
        if device is None or client is None:
            return Response({"error": "unknown_device" if device is None else "redis_unavailable"}, status=status.HTTP_400_BAD_REQUEST)
        body = request.data if isinstance(request.data, dict) else {}
        item = core.handoff_item(body.get("playing")) if body.get("playing") is not None else None
        if body.get("playing") is not None and item is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        handoff.report(client, request.user.pk, device, core.device_name(body.get("name")), item)
        return Response({"api": core.API_VERSION, "ok": True})


class HandoffSendView(TeleView):
    """POST {"to": install id, "item": what to play}: sent to that TV of the same user, which offers it."""

    def post(self, request):
        from . import handoff

        device = core.device_id(request.headers.get("User-Agent"))
        client = data.redis_client()
        body = request.data if isinstance(request.data, dict) else {}
        to = core.to_text(body.get("to")) or ""
        item = core.handoff_item(body.get("item"))
        if device is None or client is None or item is None or not re.match(r"^[0-9a-f]{12}$", to):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        if not handoff.send(client, request.user.pk, device, to, item):
            return Response({"error": "not_found"}, status=status.HTTP_404_NOT_FOUND)
        return Response({"api": core.API_VERSION, "sent": True})


class VodHoldView(TeleView):
    """Provider connection of the movie or episode a TV paused, kept for its resume (holds.py).

    POST {"hold": true}: held (again) for the setting vod_pause_hold_minutes; {"hold": false}: given back. The TV is
    told by its User-Agent (install id); 0 minutes in the settings turn it off.
    """

    def post(self, request):
        from . import holds

        device = core.device_id(request.headers.get("User-Agent"))
        if device is None:
            return Response({"error": "unknown_device"}, status=status.HTTP_400_BAD_REQUEST)
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        body = request.data if isinstance(request.data, dict) else {}
        if body.get("hold") is not True:
            return Response({"api": core.API_VERSION, "held": False, "released": holds.release(client, device)})
        minutes = core.query_int(self.setting("vod_pause_hold_minutes", holds.DEFAULT_MINUTES), holds.DEFAULT_MINUTES, 0, holds.MAX_MINUTES)
        if minutes <= 0:
            return Response({"api": core.API_VERSION, "held": False})
        return Response({"api": core.API_VERSION, **holds.hold(client, device, request.user.pk, minutes)})


class VodLanguagesView(TeleView):
    """Audio and subtitle languages measured by category (ffprobe on one of its files)."""

    def get(self, request):
        categories = probe.measured_categories()
        if hides_adult(request.user):
            # Mature content hidden for this user: the categories holding adult movies are left out too
            adult = data.adult_categories(c["id"] for c in categories if c["id"] is not None)
            categories = [c for c in categories if c["id"] not in adult]
        return Response({"api": core.API_VERSION, "categories": categories})


class VodMediaView(TeleView):
    """Technical description of a movie or an episode measured with ffprobe (?kind=movie|episode&uuid=...).

    Unknown yet: the title is queued for a probe (same rules as the category probes: a free connection, after
    the viewers) and the answer says "queued"; Télé asks again a little later.
    """

    def get(self, request):
        kind = request.query_params.get("kind")
        uuid = core.valid_uuid(request.query_params.get("uuid"))
        if kind not in core.MEDIA_KINDS or uuid is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        result = probe.title_result(kind, uuid) or {}
        if result.get("status") == "ok":
            return Response({"api": core.API_VERSION, "status": "ok", "info": result.get("info") or {}})
        if (result.get("attempts") or 0) >= probe.MAX_ATTEMPTS or not self.setting("probe_enabled", True):
            return Response({"api": core.API_VERSION, "status": "failed"})
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        queued = probe.request_title(client, kind, uuid)
        if queued == "queued":
            # The runners of the web processes measure it now (not Celery: its workers ignore the tasks of a
            # plugin installed after they started)
            from . import runner

            runner.wake(client)
        return Response({"api": core.API_VERSION, "status": queued})


class TmdbIdsView(TeleView):
    """TMDB ids the plugin found for the movies or series Dispatcharr has none for (?kind=movie|series):
    {"ids": {"<Dispatcharr id>": "<TMDB id>"}}, with an ETag (304 while unchanged)."""

    def get(self, request):
        from . import tmdb

        kind = request.query_params.get("kind")
        if kind not in ("movie", "series"):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        found = tmdb.ids(kind)
        tag = core.etag(found)
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response({"api": core.API_VERSION, "ids": found}, headers={"ETag": tag})


class VodGroupsView(TeleView):
    """The VOD groups served to the TVs, chosen by an admin on the Télé page: {"mode": "all"}, or {"mode":
    "selected", "groups": [{"name", "type"}]} (Télé loads only those), with an ETag (304 while unchanged)."""

    def get(self, request):
        answer = {"api": core.API_VERSION, **probe.served_groups()}
        tag = core.etag(answer)
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response(answer, headers={"ETag": tag})


class VodPopularView(TeleView):
    """Movies and series most played on the server ("?days=" 1-30, 30 by default), adult ones left out:
    {"titles": [{"kind": "movie" | "series", "id": <Dispatcharr id>, "plays"}]}, with an ETag."""

    def get(self, request):
        from . import stats

        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        days = core.query_int(request.query_params.get("days"), 30, 1, core.STATS_DAYS)
        answer = {"api": core.API_VERSION, "days": days, "titles": stats.popular(client, days)}
        tag = core.etag(answer)
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response(answer, headers={"ETag": tag})


def _within(request, scope: str, limit: int) -> bool:
    """[request] of a standard user within its daily budget of [scope] (admins are not limited)."""
    return is_admin(request.user) or quota.allow(data.redis_client(), scope, request.user.pk, limit, 86400)


def _over_budget() -> Response:
    return Response({"error": "daily_limit"}, status=status.HTTP_429_TOO_MANY_REQUESTS)


class SubtitleSearchView(TeleView):
    """OpenSubtitles search with the key of the server (same parameters as the API: tmdb_id, imdb_id, parent_tmdb_id,
    parent_imdb_id, season_number, episode_number, query, year, languages), answered like it."""

    def get(self, request):
        from . import opensubtitles

        if not _within(request, "subtitle_search", SUBTITLE_SEARCHES):
            return _over_budget()
        try:
            return Response(opensubtitles.search(request.query_params))
        except opensubtitles.Refused as refused:
            return Response({"error": "opensubtitles", "status": refused.status}, status=refused.status)


class SubtitleDownloadView(TeleView):
    """{"file_id"}: the subtitle file itself, its extension and the downloads left today in X-Subtitle-* headers."""

    def post(self, request):
        from django.http import HttpResponse

        from . import opensubtitles

        body = request.data if isinstance(request.data, dict) else {}
        file_id = core.to_int(body.get("file_id"))
        if file_id is None or file_id <= 0:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        # The downloads of OpenSubtitles are counted per day for the whole server: shared fairly
        if not _within(request, "subtitle_download", SUBTITLE_DOWNLOADS):
            return _over_budget()
        try:
            content, extension, remaining = opensubtitles.download(data.redis_client(), file_id)
        except opensubtitles.Refused as refused:
            return Response({"error": "opensubtitles", "status": refused.status}, status=refused.status)
        response = HttpResponse(content, content_type="application/octet-stream")
        response["X-Subtitle-Extension"] = extension
        if remaining is not None:
            response["X-Subtitle-Remaining"] = str(remaining)
        response["Cache-Control"] = "no-store"
        return response


class VodFactsView(TeleView):
    """TMDB facts of the titles served (?kind=movie|series, ?since=<until of a previous answer> for the changes only):
    {"facts": {"<TMDB id>": {"v", "n", "p", "l", "c", "s"}}, "until"}, gzipped when the TV accepts it, with an ETag."""

    def get(self, request):
        import gzip

        from django.http import HttpResponse

        from . import facts

        kind = request.query_params.get("kind")
        if kind not in ("movie", "series"):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        since = core.to_float(request.query_params.get("since"))
        answer = {"api": core.API_VERSION, **facts.served(kind, since if since and since > 0 else None)}
        body = json.dumps(answer, ensure_ascii=False, separators=(",", ":")).encode()
        import hashlib

        tag = '"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        response = HttpResponse(content_type="application/json")
        if "gzip" in (request.headers.get("Accept-Encoding") or ""):
            response.content = gzip.compress(body, compresslevel=6)
            response["Content-Encoding"] = "gzip"
        else:
            response.content = body
        response["ETag"] = tag
        response["Vary"] = "Accept-Encoding"
        return response


class VodCatalogView(TeleView):
    """The movies or series of the groups served (?kind=movie|series, ?since=<X-Tele-Until of a previous answer>
    for the changes only): {"results": [items of the Dispatcharr VOD API with their "category"]}, gzipped, with the
    time to ask the changes from in X-Tele-Until and an ETag."""

    def get(self, request):
        import gzip
        import hashlib

        from django.http import HttpResponse

        from . import catalog

        kind = request.query_params.get("kind")
        if kind not in ("movie", "series"):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        since = core.to_float(request.query_params.get("since"))
        body, until, count = catalog.answer(kind, hides_adult(request.user), since if since and since > 0 else None)
        tag = '"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        headers = {"ETag": tag, "X-Tele-Until": f"{until:.3f}", "X-Tele-Count": str(count), "Vary": "Accept-Encoding"}
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers=headers)
        if "gzip" in (request.headers.get("Accept-Encoding") or ""):
            response = HttpResponse(body, content_type="application/json")
            response["Content-Encoding"] = "gzip"
        else:
            response = HttpResponse(gzip.decompress(body), content_type="application/json")
        for name, value in headers.items():
            response[name] = value
        return response


class PosterView(APIView):
    """A light poster (JPEG, ?w= width of the card) of a VOD logo of Dispatcharr, by id: served without a key like the
    images of Dispatcharr (a card loads dozens of them), never from an address of the request. When it cannot be made,
    a redirect to the image of Dispatcharr."""

    authentication_classes = []
    permission_classes = [PluginEnabled]

    def get(self, request, logo_id):
        from django.http import HttpResponse, HttpResponseRedirect

        from . import posters

        width = posters.width_of(request.query_params.get("w"))
        content = posters.kept(int(logo_id), width)
        # A poster to make: at most POSTERS_MADE every 10 minutes for an address (the route needs no key)
        if content is None and posters.available() and quota.allow(
            data.redis_client(), "posters", request.META.get("REMOTE_ADDR") or "?", POSTERS_MADE, 600
        ):
            content = posters.poster(int(logo_id), width)
        if content is None:
            return HttpResponseRedirect(f"/api/vod/vodlogos/{int(logo_id)}/cache/")
        response = HttpResponse(content, content_type="image/jpeg")
        response["Cache-Control"] = "public, max-age=604800"
        response["X-Content-Type-Options"] = "nosniff"
        return response


class VodSegmentsView(TeleView):
    """Intro and end credits of a movie or an episode (?tmdb_id, ?season and ?episode for an episode, ?duration_ms),
    from TheIntroDB through the cache of the server: {"segments": {"intro": [{"start_ms", "end_ms"}], ...}}."""

    def get(self, request):
        from . import extras

        tmdb_id = core.to_int(request.query_params.get("tmdb_id"))
        season = core.to_int(request.query_params.get("season"))
        episode = core.to_int(request.query_params.get("episode"))
        duration = core.to_int(request.query_params.get("duration_ms"))
        if not tmdb_id or tmdb_id <= 0 or (season is None) != (episode is None) or (season is not None and not 0 <= season <= 500) \
                or (episode is not None and not 0 <= episode <= 10000):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        duration = duration if duration and 0 < duration < 24 * 3600 * 1000 else None
        if not _within(request, "segments", EXTRAS_PER_DAY):
            return _over_budget()
        return Response({"api": core.API_VERSION, "segments": extras.segments(tmdb_id, season, episode, duration)})


class VodRelatedView(TeleView):
    """TMDB ids of the titles that may also please (?kind=movie|series, ?tmdb_id): {"ids": [...]}, from TMDB with the
    key of the plugin, kept two weeks; 404 without key."""

    def get(self, request):
        from . import extras

        kind = request.query_params.get("kind")
        tmdb_id = core.to_int(request.query_params.get("tmdb_id"))
        if kind not in ("movie", "series") or not tmdb_id or tmdb_id <= 0:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        if not _within(request, "related", EXTRAS_PER_DAY):
            return _over_budget()
        found = extras.related(kind, tmdb_id, core.to_text(self.setting("tmdb_language", "fr-FR")) or "fr-FR")
        if found is None:
            return Response({"error": "no_tmdb_key"}, status=status.HTTP_404_NOT_FOUND)
        return Response({"api": core.API_VERSION, "ids": found})


class IsAdminUser(permissions.BasePermission):
    def has_permission(self, request, view):
        user = request.user
        return bool(user and user.is_authenticated and is_admin(user))


class AdminView(TeleView):
    permission_classes = [IsAdminUser, PluginEnabled]


class AdminDevicesView(AdminView):
    """Télé TVs seen lately, with their user, version, address and open sessions."""

    def get(self, request):
        from . import devices

        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        raw = devices.all_raw(client)
        sessions = data.all_sessions() or []
        users = data.usernames(core.device_users(raw))
        from . import updates

        listed = core.devices_json(raw, users, sessions, time.time())
        newest = updates.latest(client)
        for device in listed:
            device["behind"] = core.is_behind(device.get("version"), newest and newest["version"])
        return Response({"api": core.API_VERSION, "devices": listed, "latest_app": newest})


class AdminMessagesView(AdminView):
    """Messages for one TV or one user: GET the ones shown; POST {"kind": "device" | "user", "target", "text" (empty or
    null removes it), "hours" (1 to 720, 24 by default)}."""

    def get(self, request):
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response({"api": core.API_VERSION, "messages": messages.active(client)})

    def post(self, request):
        body = request.data if isinstance(request.data, dict) else {}
        key = core.message_target(body.get("kind"), body.get("target"))
        text = (core.to_text(body.get("text")) or "").strip()
        hours = core.query_int(body.get("hours"), 24, 1, core.MESSAGE_MAX_HOURS)
        if key is None or len(text) > core.MAX_MESSAGE:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        messages.send(client, key, text or None, hours)
        return Response({"api": core.API_VERSION, "ok": True})


class AdminDeviceReleaseView(AdminView):
    """Closes every session of one TV ({"device": install id})."""

    def post(self, request):
        body = request.data if isinstance(request.data, dict) else {}
        device = core.to_text(body.get("device"))
        if not device or core.device_id(f"; Tele-{device})") != device:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        sessions = data.all_sessions()
        if sessions is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        released = [s.id for s in sessions if core.device_id(s.user_agent) == device and data.stop_session(s)]
        logger.info("Télé API: %d session(s) of a TV closed by an admin", len(released))
        return Response({"api": core.API_VERSION, "released": released})


class AdminStatsView(AdminView):
    """Most started channels and provider connection peaks of the last days (?days=1-30, default 7)."""

    def get(self, request):
        from . import stats

        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        days = core.query_int(request.query_params.get("days"), 7, 1, core.STATS_DAYS)
        return Response({"api": core.API_VERSION, **stats.report(client, days)})


class AdminTmdbView(AdminView):
    """TMDB of the plugin: GET whether a key is set (never the key) and the counts; POST {"key": "..."} sets it after
    TMDB accepted it, {"key": null} removes it."""

    def get(self, request):
        from . import facts, tmdb, vault

        return Response({
            "api": core.API_VERSION,
            "key_set": vault.tmdb_key() is not None,
            "enabled": bool(self.setting("tmdb_enabled", True)),
            "language": self.setting("tmdb_language", "fr-FR"),
            "min_confidence": core.tmdb_min_confidence(self.setting("tmdb_min_confidence", core.TMDB_MIN_CONFIDENCE)),
            "unmatched_titles": tmdb.unmatched(200),
            "facts": facts.counts(),
            **tmdb.counts(),
        })

    def post(self, request):
        from . import tmdb, vault

        body = request.data if isinstance(request.data, dict) else {}
        key = body.get("key")
        if key is None:
            vault.set_tmdb_key(None)
            return Response({"api": core.API_VERSION, "key_set": False})
        key = core.to_text(key) or ""
        if not 16 <= len(key.strip()) <= 512 or any(c.isspace() for c in key.strip()):
            return Response({"error": "invalid_key"}, status=status.HTTP_400_BAD_REQUEST)
        if not tmdb.test_key(key):
            return Response({"error": "key_refused"}, status=status.HTTP_400_BAD_REQUEST)
        vault.set_tmdb_key(key)
        logger.info("Télé API: TMDB key set by an admin")
        return Response({"api": core.API_VERSION, "key_set": True})


class AdminSubtitlesView(AdminView):
    """OpenSubtitles of the plugin: GET whether a key and an account are set (never them); POST {"key"} sets the key
    after OpenSubtitles accepted it ({"key": null} removes it), {"username", "password"} the account (null: removed)."""

    def get(self, request):
        account = vault.opensubtitles_account()
        return Response({
            "api": core.API_VERSION,
            "key_set": vault.opensubtitles_key() is not None,
            "account": account[0] if account else None,
        })

    def post(self, request):
        from . import opensubtitles

        body = request.data if isinstance(request.data, dict) else {}
        if "username" in body:
            username, password = core.to_text(body.get("username")), core.to_text(body.get("password"))
            if username and (len(username) > 100 or not password or len(password) > 200):
                return Response({"error": "invalid_account"}, status=status.HTTP_400_BAD_REQUEST)
            vault.set_opensubtitles_account(username, password)
            client = data.redis_client()
            if client is not None:
                client.delete(opensubtitles.TOKEN_KEY)
            return Response({"api": core.API_VERSION, "account": username or None})
        key = body.get("key")
        if key is None:
            vault.set_opensubtitles_key(None)
            return Response({"api": core.API_VERSION, "key_set": False})
        key = (core.to_text(key) or "").strip()
        if not 16 <= len(key) <= 128 or any(c.isspace() for c in key):
            return Response({"error": "invalid_key"}, status=status.HTTP_400_BAD_REQUEST)
        if not opensubtitles.test(key):
            return Response({"error": "key_refused"}, status=status.HTTP_400_BAD_REQUEST)
        vault.set_opensubtitles_key(key)
        logger.info("Télé API: OpenSubtitles key set by an admin")
        return Response({"api": core.API_VERSION, "key_set": True})


class AdminIntroDbView(AdminView):
    """TheIntroDB key of the plugin (optional): GET whether one is set (never the key); POST {"key"} sets it after
    TheIntroDB accepted it, {"key": null} removes it."""

    def get(self, request):
        return Response({"api": core.API_VERSION, "key_set": vault.introdb_key() is not None})

    def post(self, request):
        from . import extras

        body = request.data if isinstance(request.data, dict) else {}
        key = body.get("key")
        if key is None:
            vault.set_introdb_key(None)
            return Response({"api": core.API_VERSION, "key_set": False})
        key = (core.to_text(key) or "").strip()
        if not 8 <= len(key) <= 512 or any(c.isspace() for c in key):
            return Response({"error": "invalid_key"}, status=status.HTTP_400_BAD_REQUEST)
        if not extras.test_introdb_key(key):
            return Response({"error": "key_refused"}, status=status.HTTP_400_BAD_REQUEST)
        vault.set_introdb_key(key)
        logger.info("Télé API: TheIntroDB key set by an admin")
        return Response({"api": core.API_VERSION, "key_set": True})


class AdminCategoriesView(AdminView):
    """Every enabled VOD category of the Xtream accounts with its probe state (filtered on the page)."""

    def get(self, request):
        return Response({
            "api": core.API_VERSION,
            "selection_mode": probe.load_selection()["mode"],
            "categories": probe.all_categories(),
        })


class AdminCategorySelectionView(AdminView):
    """The VOD groups the probes and TMDB process: {"mode": "all"} or {"mode": "selected", "ids": [...]}."""

    def post(self, request):
        selection = core.parse_selection(request.data)
        if selection is None:
            return Response({"error": "invalid_selection"}, status=status.HTTP_400_BAD_REQUEST)
        probe.save_selection(selection)
        logger.info("Télé API: %s", "every VOD group processed" if selection["mode"] == "all" else f"{len(selection['ids'])} VOD groups processed")
        return Response({"api": core.API_VERSION, "mode": selection["mode"], "selected": len(selection["ids"])})


class AdminCategoryActionView(AdminView):
    """One category: {"id", "action": "probe" | "manual" | "clear" | "exclude" | "include", "audio", "subtitles"}."""

    def post(self, request):
        body = request.data if isinstance(request.data, dict) else {}
        category_id = core.to_int(body.get("id"))
        action = body.get("action")
        if category_id is None or action not in ("probe", "manual", "clear", "exclude", "include"):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        if action == "probe":
            ok, message = probe.probe_category_now(category_id, plugin_state.get()[1])
            return Response({"api": core.API_VERSION, "ok": ok, "message": message})
        if action == "manual":
            audio, subtitles = core.language_codes(body.get("audio")), core.language_codes(body.get("subtitles"))
            if audio is None or subtitles is None or not (audio or subtitles):
                return Response({"error": "invalid_languages"}, status=status.HTTP_400_BAD_REQUEST)
            ok = probe.set_manual(category_id, audio, subtitles)
        elif action == "clear":
            probe.forget(category_id)
            ok = True
        else:
            ok = probe.set_excluded(category_id, action == "exclude")
        if not ok:
            return Response({"error": "unknown_category"}, status=status.HTTP_404_NOT_FOUND)
        return Response({"api": core.API_VERSION, "ok": True})


class AdminProbeRetryView(AdminView):
    def post(self, request):
        return Response({"api": core.API_VERSION, "forgotten": probe.retry_failed()})


class AdminTmdbItemView(AdminView):
    """{"kind": "movie" | "series", "item": <Dispatcharr id>, "tmdb": <TMDB id> | null}, the same with {"accept": true}
    to keep its candidate, {"accept_above": <confidence>} to keep every candidate from that one, or {"retry": true}."""

    def post(self, request):
        from . import tmdb

        body = request.data if isinstance(request.data, dict) else {}
        if body.get("retry") is True:
            return Response({"api": core.API_VERSION, "forgotten": tmdb.retry_unmatched()})
        if "accept_above" in body:
            above = core.to_int(body.get("accept_above"))
            if above is None or not 1 <= above <= 100:
                return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
            return Response({"api": core.API_VERSION, "accepted": tmdb.accept_candidates(above)})
        kind, item = body.get("kind"), core.to_int(body.get("item"))
        if body.get("accept") is True:
            if kind not in ("movie", "series") or item is None:
                return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
            if not tmdb.accept(kind, item):
                return Response({"error": "no_candidate"}, status=status.HTTP_404_NOT_FOUND)
            return Response({"api": core.API_VERSION, "ok": True})
        tmdb_id = None if body.get("tmdb") is None else core.to_int(body.get("tmdb"))
        if kind not in ("movie", "series") or item is None or (body.get("tmdb") is not None and (tmdb_id is None or tmdb_id <= 0)):
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        tmdb.set_manual(kind, item, tmdb_id)
        return Response({"api": core.API_VERSION, "ok": True})


class AdminDeviceForgetView(AdminView):
    def post(self, request):
        from . import devices

        body = request.data if isinstance(request.data, dict) else {}
        device = core.to_text(body.get("device"))
        if not device or core.device_id(f"; Tele-{device})") != device:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response({"api": core.API_VERSION, "forgotten": devices.forget(client, device)})


class AdminProbesView(AdminView):
    """Every probe result (measured, retrying, failed) and the counts, for the Télé page."""

    def get(self, request):
        results = probe.load_results()
        categories = []
        for key, entry in results.items():
            if not isinstance(entry, dict):
                continue
            categories.append(
                {
                    "id": core.to_int(key),
                    "name": entry.get("name"),
                    "type": entry.get("type"),
                    "status": entry.get("status"),
                    "attempts": entry.get("attempts") or 0,
                    "audio": list(entry.get("audio") or []),
                    "subtitles": list(entry.get("subtitles") or []),
                    "sample": entry.get("sample"),
                    "quality": entry.get("quality"),
                    "probed_at": entry.get("at"),
                }
            )
        categories.sort(key=lambda c: (str(c["name"] or "").lower(), c["id"] or 0))
        client = data.redis_client()
        return Response(
            {
                "api": core.API_VERSION,
                "summary": probe.summary(),
                "last_run": probe.last_run(client) if client is not None else None,
                "settings": {
                    "enabled": bool(self.setting("probe_enabled", True)),
                    "scope": self.setting("probe_scope", "uncertain"),
                    "min_free": self.setting("probe_min_free", 1),
                    "pause_seconds": self.setting("probe_pause_seconds", 5),
                },
                "categories": categories,
            }
        )


class AdminStreamSortView(AdminView):
    """Sort of the streams of the channels (streamsort.py): the last run and the setting; POST sorts them now."""

    def _answer(self, client, last):
        return Response({"api": core.API_VERSION, "enabled": bool(self.setting("sort_streams", True)), "last": last})

    def get(self, request):
        from . import streamsort

        client = data.redis_client()
        return self._answer(client, streamsort.last(client))

    def post(self, request):
        from . import streamsort

        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        done = streamsort.run(client)
        return self._answer(client, done if done["outcome"] == "running" else streamsort.last(client) or done)


class AdminProbeRunView(AdminView):
    def post(self, request):
        from . import runner

        client = data.redis_client()
        if client is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        runner.wake(client)
        return Response({"api": core.API_VERSION, "queued": True})


class AdminProbeResetView(AdminView):
    """Forgets one result ({"id": category id}) or all of them ({})."""

    def post(self, request):
        body = request.data if isinstance(request.data, dict) else {}
        category_id = body.get("id")
        if category_id is not None and core.to_int(category_id) is None:
            return Response({"error": "invalid_request"}, status=status.HTTP_400_BAD_REQUEST)
        probe.forget(core.to_int(category_id) if category_id is not None else None)
        return Response({"api": core.API_VERSION, "forgotten": category_id if category_id is not None else "all"})


class AdminExportView(AdminView):
    """The measured languages as Télé test cases, downloaded as a JSON file."""

    def get(self, request):
        response = Response(probe.export())
        response["Content-Disposition"] = 'attachment; filename="vod-language-cases.json"'
        return response


class AdminBackupView(AdminView):
    """GET: a backup of the Télé page (groups, languages set by hand, TMDB ids) as a JSON file; POST: restores one."""

    def get(self, request):
        from . import backup

        response = Response(backup.export())
        response["Content-Disposition"] = 'attachment; filename="tele-api-backup.json"'
        return response

    def post(self, request):
        from . import backup

        restored = backup.restore(request.data)
        if restored is None:
            return Response({"error": "invalid_backup"}, status=status.HTTP_400_BAD_REQUEST)
        return Response({"api": core.API_VERSION, **restored})


class UiView(APIView):
    """The Télé page: static HTML, no data in it. Its script calls the admin endpoints with the session token
    of the Dispatcharr web interface (same address), so only an admin signed in to Dispatcharr sees anything."""

    authentication_classes = []
    permission_classes = [permissions.AllowAny]

    def get(self, request):
        from django.http import HttpResponse

        if not plugin_state.get()[0]:
            return HttpResponse("Télé API plugin is disabled", status=404, content_type="text/plain; charset=utf-8")
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui.html")
        with open(path, encoding="utf-8") as fh:
            response = HttpResponse(fh.read(), content_type="text/html; charset=utf-8")
        # No framing by other sites, no inline content from elsewhere
        response["X-Frame-Options"] = "SAMEORIGIN"
        response["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data:; frame-ancestors 'self'"
        )
        response["Cache-Control"] = "no-store"
        return response
