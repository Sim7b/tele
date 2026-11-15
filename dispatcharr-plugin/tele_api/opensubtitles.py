"""Subtitles from OpenSubtitles for every Télé TV, with one key kept on the server (vault.py).

The TVs search and download through the plugin: they need no key of their own, and the key never leaves the
server. An OpenSubtitles account (optional) raises the daily downloads of the key; its token is kept in Redis.
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional, Tuple

from . import core, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.opensubtitles")

API = "https://api.opensubtitles.com/api/v1"
USER_AGENT = f"Tele API v{core.PLUGIN_VERSION}"
TOKEN_KEY = "tele_api:opensubtitles:token"
TOKEN_TTL = 12 * 3600
MAX_ANSWER = 2 * 1024 * 1024
MAX_SUBTITLE = 4 * 1024 * 1024
EXTENSIONS = ("srt", "vtt", "ass", "ssa", "ttml")


class Refused(Exception):
    """OpenSubtitles refused: [status] is what the TV is answered (401 key, 429 quota, 502 other)."""

    def __init__(self, status: int):
        super().__init__(status)
        self.status = status


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _request(method: str, path: str, key: str, body: Optional[Dict[str, Any]] = None, token: Optional[str] = None,
             query: str = "") -> Dict[str, Any]:
    headers = {"Api-Key": key, "User-Agent": USER_AGENT, "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(f"{API}{path}{'?' + query if query else ''}", data=data, headers=headers, method=method)
    try:
        with urllib.request.build_opener(_NoRedirect).open(request, timeout=15) as response:
            answer = json.loads(response.read(MAX_ANSWER))
    except urllib.error.HTTPError as error:
        raise Refused(401 if error.code in (401, 403) else 429 if error.code in (406, 429) else 502) from None
    except (OSError, ValueError):
        raise Refused(502) from None
    if not isinstance(answer, dict):
        raise Refused(502)
    return answer


def _token(redis_client, key: str) -> Optional[str]:
    """Token of the OpenSubtitles account of the plugin (none: downloads of the key alone)."""
    account = vault.opensubtitles_account()
    if account is None:
        return None
    if redis_client is not None:
        cached = core.to_text(redis_client.get(TOKEN_KEY))
        if cached:
            return cached
    try:
        token = core.to_text(_request("POST", "/login", key, {"username": account[0], "password": account[1]}).get("token"))
    except Refused:
        logger.warning("Télé API: OpenSubtitles refused the account, downloads go on without it")
        return None
    if token and redis_client is not None:
        redis_client.set(TOKEN_KEY, token, ex=TOKEN_TTL)
    return token


def search(params: Any) -> Dict[str, Any]:
    """The search of a TV (its parameters checked), answered with the fields Télé reads."""
    key = vault.opensubtitles_key()
    if key is None:
        raise Refused(401)
    query = core.subtitle_search_query(params)
    if query is None:
        raise Refused(400)
    answer = _request("GET", "/subtitles", key, query=query)
    return {"data": core.subtitle_results(answer.get("data"))}


def download(redis_client, file_id: int) -> Tuple[bytes, str, Optional[int]]:
    """(file, extension, downloads left today) of [file_id]: a link from the API, then the file from its CDN."""
    key = vault.opensubtitles_key()
    if key is None:
        raise Refused(401)
    ticket = _request("POST", "/download", key, {"file_id": file_id}, token=_token(redis_client, key))
    link = core.to_text(ticket.get("link")) or ""
    parts = urllib.parse.urlsplit(link)
    from .probe import resolve

    if parts.scheme != "https" or not parts.hostname or not core.host_allowed(resolve(parts.hostname)):
        raise Refused(502)
    name = core.to_text(ticket.get("file_name")) or ""
    extension = name.rsplit(".", 1)[-1].lower() if "." in name else "srt"
    if extension not in EXTENSIONS:
        extension = "srt"
    request = urllib.request.Request(link, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.build_opener(_NoRedirect).open(request, timeout=20) as response:
            content = response.read(MAX_SUBTITLE + 1)
    except (OSError, ValueError):
        raise Refused(502) from None
    if len(content) > MAX_SUBTITLE or not content:
        raise Refused(502)
    return content, extension, core.to_int(ticket.get("remaining"))


def test(key: str) -> bool:
    """Whether OpenSubtitles accepts [key] (a small search)."""
    try:
        _request("GET", "/subtitles", key, query="languages=en&query=dune")
        return True
    except Refused as refused:
        return refused.status == 429  # Valid, out of requests for now
