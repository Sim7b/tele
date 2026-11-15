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
from rest_framework.views import APIView

from . import core, data

logger = logging.getLogger(__name__)

PLUGIN_KEY = os.path.basename(os.path.dirname(os.path.abspath(__file__))).lower().replace(" ", "_")

FEATURES = ("channels", "slots", "sessions")
MAX_RELEASED = 50
_ENABLED_TTL = 5.0
ADMIN_LEVEL = 10
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


class IsStandardUser(permissions.BasePermission):
    """Same rule as the Dispatcharr channel API: a logged-in user of the standard level or above."""

    def has_permission(self, request, view):
        user = request.user
        return bool(user and user.is_authenticated and getattr(user, "user_level", 0) >= STANDARD_LEVEL)


class PluginEnabled(permissions.BasePermission):
    message = "Télé API plugin is disabled"

    def has_permission(self, request, view):
        return plugin_state.get()[0]


class TeleView(APIView):
    # Authentication classes: the defaults of the server (JWT and API key)
    permission_classes = [IsStandardUser, PluginEnabled]

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
                "user": {"username": user.username, "admin": is_admin(user)},
                "release_allowed": bool(self.setting("allow_release", True)),
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


class SlotsView(TeleView):
    """Free provider connections, by account and in total, read live (never cached)."""

    def get(self, request):
        limits = data.profile_limits()
        used = data.connections_by_profile(p.profile_id for p in limits if p.active and p.max_streams > 0)
        if used is None:
            return Response({"error": "redis_unavailable"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response({"api": core.API_VERSION, **core.slots(limits, used)})


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

        released = [sid for sid, session in targets.items() if data.stop_session(session)]
        if released:
            logger.info("Télé API: %d session(s) released by user %s", len(released), user.pk)
        return Response({"api": core.API_VERSION, "released": released})
