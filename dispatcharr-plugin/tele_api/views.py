"""HTTP endpoints of the Télé API (/api/tele/v1/...).

Authentication is the one of the Dispatcharr API (X-API-Key or JWT). Every endpoint is open to standard
users and only returns what the caller may see: their channels (level, adult filter, channel profiles),
their own sessions. Admins can also list and close the sessions of every user.
"""

from __future__ import annotations

import logging
import os
import threading
import time

from django.utils import timezone
from rest_framework import permissions, status
from rest_framework.response import Response
from rest_framework.throttling import BaseThrottle
from rest_framework.views import APIView

from . import core, data, probe

logger = logging.getLogger(__name__)

PLUGIN_KEY = os.path.basename(os.path.dirname(os.path.abspath(__file__))).lower().replace(" ", "_")

FEATURES = ("channels", "slots", "sessions", "vod_languages", "vod_media", "stream_health", "guide")
MAX_RELEASED = 50
_ENABLED_TTL = 5.0
ADMIN_LEVEL = 10
DEFAULT_RATE_LIMIT = 120
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


class InfoView(TeleView):
    """Plugin presence, protocol version and rights of the caller: the first call of Télé."""

    def get(self, request):
        user = request.user
        return Response(
            {
                "plugin": PLUGIN_KEY,
                "version": core.PLUGIN_VERSION,
                "api": core.API_VERSION,
                "features": list(FEATURES),
                "server_time": data.server_time(),
                "user": {"username": user.username, "admin": is_admin(user), "hide_adult": hides_adult(user)},
                "release_allowed": bool(self.setting("allow_release", True)),
                "message": (core.to_text(self.setting("tv_message", "")) or "").strip()[:500] or None,
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
        start, end = core.guide_window(time.time(), request.query_params.get("hours"))
        results = data.guide(request.user, start, end)
        tag = core.etag([start, end, results])
        if core.etag_matches(request.headers.get("If-None-Match"), tag):
            return Response(status=status.HTTP_304_NOT_MODIFIED, headers={"ETag": tag})
        return Response({"api": core.API_VERSION, "start": start, "end": end, "results": results}, headers={"ETag": tag})


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
            from . import tasks

            tasks.probe_vod_languages.delay()
        return Response({"api": core.API_VERSION, "status": queued})


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
        return Response({"api": core.API_VERSION, "devices": core.devices_json(raw, users, sessions, time.time())})


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
        return Response(
            {
                "api": core.API_VERSION,
                "summary": probe.summary(),
                "settings": {
                    "enabled": bool(self.setting("probe_enabled", True)),
                    "scope": self.setting("probe_scope", "uncertain"),
                    "min_free": self.setting("probe_min_free", 1),
                    "pause_seconds": self.setting("probe_pause_seconds", 5),
                },
                "categories": categories,
            }
        )


class AdminProbeRunView(AdminView):
    def post(self, request):
        from . import tasks

        tasks.probe_vod_languages.delay()
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
