"""Measured audio and subtitle languages of the VOD categories, with ffprobe on one file of each.

A category whose name leaves its languages uncertain (core.category_needs_probe) gets one of its files
probed: ffprobe reads the language tags of its audio and subtitle tracks, kept for the whole category. A
probe opens a real provider connection, so it never comes before a viewer:
- it only starts when the profile keeps the free connections set in the plugin settings (1 by default),
  through the same slot reservation as playback (the limit is never exceeded);
- one probe at a time on the server (Redis lock), a pause between two;
- a viewer short of a connection (Télé on HTTP 503) asks to yield: the running probe is stopped at once,
  its connection given back, and probes pause for a while.

Results are kept in a JSON file under the Dispatcharr data folder, so they survive restarts and updates of
the plugin. Provider URLs carry credentials: never logged, ffprobe output on errors is not logged either.
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import tempfile
import threading
import time
from typing import Any, Dict, Iterator, List, Optional, Tuple

from . import core

logger = logging.getLogger(__name__)

TASK_NAME = "tele_api.probe_vod_languages"
SCHEDULE_NAME = "Télé API: VOD languages"
LOCK_KEY = "tele_api:probe:lock"
YIELD_KEY = "tele_api:probe:yield"
LOCK_TTL = 15 * 60
# A run stops after this long, the next one goes on (every 10 minutes)
RUN_BUDGET = 8 * 60
PROBE_TIMEOUT = 25
MAX_REDIRECTS = 5
PROTOCOLS = "http,https,tcp,tls,crypto"
YIELD_PAUSE = 120
MAX_ATTEMPTS = 3
REPROBE_AFTER = 30 * 24 * 3600
FILE_VERSION = 1

_file_lock = threading.Lock()


def data_dir() -> str:
    for base in ("/data", "/app/data"):
        if os.path.isdir(base):
            return os.path.join(base, "tele_api")
    return os.path.join(tempfile.gettempdir(), "tele_api")


def results_path() -> str:
    return os.path.join(data_dir(), "vod_languages.json")


def load_results() -> Dict[str, Dict[str, Any]]:
    """Results by category id; empty when the file is missing or damaged."""
    try:
        with open(results_path(), encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    categories = data.get("categories") if isinstance(data, dict) else None
    return categories if isinstance(categories, dict) else {}


def save_result(category_id: int, entry: Dict[str, Any]) -> None:
    """Writes one result (atomic replace of the file)."""
    with _file_lock:
        results = load_results()
        results[str(category_id)] = entry
        os.makedirs(data_dir(), exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=data_dir(), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump({"version": FILE_VERSION, "categories": results}, fh, ensure_ascii=False)
            os.replace(tmp, results_path())
        except Exception:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise


def measured_categories() -> List[Dict[str, Any]]:
    """Categories with measured languages, for the API."""
    measured = []
    for key, entry in load_results().items():
        if not isinstance(entry, dict) or entry.get("status") != "ok":
            continue
        measured.append(
            {
                "id": core.to_int(key),
                "name": entry.get("name"),
                "type": entry.get("type"),
                "audio": list(entry.get("audio") or []),
                "subtitles": list(entry.get("subtitles") or []),
                "probed_at": entry.get("at"),
            }
        )
    measured.sort(key=lambda c: (str(c["name"] or ""), c["id"] or 0))
    return measured


# ---------------------------------------------------------------------------------------------------------
# Yielding to viewers


def ask_to_yield(redis_client) -> None:
    """A viewer lacks a connection: the running probe stops and probes pause."""
    try:
        redis_client.setex(YIELD_KEY, YIELD_PAUSE, "1")
    except Exception:
        logger.exception("Télé API: probe yield not set")


def must_yield(redis_client) -> bool:
    try:
        return bool(redis_client.exists(YIELD_KEY))
    except Exception:
        return True


# ---------------------------------------------------------------------------------------------------------
# Choice of the categories and their sample file


def candidates(settings: Dict[str, Any], results: Dict[str, Dict[str, Any]], now: float) -> Iterator[Tuple[Any, Any]]:
    """(category, relation of one of its files) still to probe, uncertain categories only unless set otherwise."""
    from apps.vod.models import M3UEpisodeRelation, M3UMovieRelation, M3UVODCategoryRelation

    probe_all = settings.get("probe_scope") == "all"
    links = (
        M3UVODCategoryRelation.objects.filter(enabled=True, m3u_account__is_active=True, m3u_account__account_type="XC")
        .select_related("category")
        .order_by("category_id")
    )
    seen = set()
    for link in links.iterator(chunk_size=500):
        category = link.category
        if category is None or category.id in seen:
            continue
        seen.add(category.id)
        previous = results.get(str(category.id)) or {}
        if previous.get("status") == "ok" and now - (previous.get("at") or 0) < REPROBE_AFTER:
            continue
        if previous.get("status") == "failed" and (previous.get("attempts") or 0) >= MAX_ATTEMPTS:
            continue
        if not probe_all and not core.category_needs_probe(category.name):
            continue
        if category.category_type == "series":
            relation = (
                M3UEpisodeRelation.objects.filter(
                    series_relation__category=category, m3u_account=link.m3u_account
                ).select_related("m3u_account").order_by("-updated_at").first()
            )
        else:
            relation = (
                M3UMovieRelation.objects.filter(category=category, m3u_account=link.m3u_account)
                .select_related("m3u_account").order_by("-updated_at").first()
            )
        if relation is not None:
            yield category, relation


def free_profile(account, redis_client, min_free: int):
    """An active profile of the account with room for a probe (core.profile_has_room), else None."""
    from apps.m3u.models import M3UAccountProfile

    from . import data

    profiles = list(M3UAccountProfile.objects.filter(m3u_account=account, is_active=True).order_by("-is_default", "id"))
    used = data.connections_by_profile(p.id for p in profiles) or {}
    for profile in profiles:
        if core.profile_has_room(profile.max_streams or 0, used.get(profile.id, 0), min_free):
            return profile
    return None


# ---------------------------------------------------------------------------------------------------------
# Probe


def run_ffprobe(url: str, user_agent: Optional[str], redis_client) -> Optional[str]:
    """ffprobe output for [url], None on error, timeout, or when a viewer asks to yield (process killed)."""
    command = [
        "ffprobe", "-v", "error", "-hide_banner",
        # Network protocols only: a provider answer (an HLS playlist, a redirect) must never make ffprobe read
        # local files of the server (file:, concat:, subfile:)
        "-protocol_whitelist", PROTOCOLS,
        "-rw_timeout", "15000000",
        "-probesize", "5000000", "-analyzeduration", "5000000",
        "-show_streams", "-of", "json",
    ]
    if user_agent:
        command += ["-user_agent", user_agent]
    command.append(url)
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        logger.warning("Télé API: ffprobe not available")
        return None
    started = time.monotonic()
    try:
        while process.poll() is None:
            if must_yield(redis_client) or time.monotonic() - started > PROBE_TIMEOUT:
                process.kill()
                process.wait(timeout=5)
                return None
            time.sleep(0.5)
        output = process.stdout.read(core.MAX_PROBE_OUTPUT + 1) if process.stdout else b""
        return output.decode("utf-8", errors="replace") if process.returncode == 0 else None
    finally:
        if process.poll() is None:
            process.kill()
        if process.stdout:
            process.stdout.close()


def resolve(host: str) -> List[str]:
    import socket

    try:
        return sorted({info[4][0] for info in socket.getaddrinfo(host, None)})
    except (OSError, UnicodeError):
        return []


def safe_url(url: str, trusted_host: Optional[str], user_agent: Optional[str]) -> Optional[str]:
    """[url] after its redirects, followed by hand: None when one of them leads to the local network.

    A provider could otherwise send ffprobe to a service of the network of the server (a redirect to
    192.168.x.x). The host of the provider set in Dispatcharr stays allowed, local or not. ffprobe is then given
    the final address, so it has no redirect left to follow.
    """
    import urllib.error
    import urllib.request
    from urllib.parse import urljoin, urlsplit

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    trusted = resolve(trusted_host) if trusted_host else []
    current = url
    for _ in range(MAX_REDIRECTS + 1):
        parts = urlsplit(current)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            return None
        if parts.hostname != trusted_host and not core.host_allowed(resolve(parts.hostname), trusted):
            logger.warning("Télé API: probe refused, a redirect leads to the local network")
            return None
        request = urllib.request.Request(current, headers={"Range": "bytes=0-0", **({"User-Agent": user_agent} if user_agent else {})})
        try:
            with opener.open(request, timeout=10):
                return current
        except urllib.error.HTTPError as error:
            location = error.headers.get("Location") if error.code in (301, 302, 303, 307, 308) else None
            if not location:
                return current if error.code < 500 else None
            current = urljoin(current, location)
        except (OSError, ValueError):
            return None
    return None


def probe_one(category, relation, profile, redis_client, previous: Dict[str, Any]) -> bool:
    """Probes one file of [category] on [profile]; False when the slot could not be reserved."""
    from apps.m3u.connection_pool import release_profile_slot, reserve_profile_slot

    reserved, _, _ = reserve_profile_slot(profile, redis_client)
    if not reserved:
        return False
    try:
        url = relation.get_stream_url(profile)
        account = relation.m3u_account
        user_agent = account.get_user_agent_string() if hasattr(account, "get_user_agent_string") else None
        from urllib.parse import urlsplit

        trusted = urlsplit(account.server_url or "").hostname if getattr(account, "server_url", None) else None
        url = safe_url(url, trusted, user_agent) if url else None
        output = run_ffprobe(url, user_agent, redis_client) if url else None
    finally:
        release_profile_slot(profile.id, redis_client)

    languages = core.parse_probe(output) if output else None
    entry = {
        "name": category.name,
        "type": category.category_type,
        "at": time.time(),
        # The file probed, without its address (it carries the provider credentials): for the export
        "sample": sample_name(relation),
    }
    if languages is None:
        if must_yield(redis_client):
            return True  # Stopped for a viewer: tried again later, not counted as a failure
        entry.update(status="failed", attempts=(previous.get("attempts") or 0) + 1)
    else:
        entry.update(status="ok", attempts=0, **languages)
    save_result(category.id, entry)
    logger.info("Télé API: category %s probed (%s)", category.id, entry["status"])
    return True


def sample_name(relation) -> Optional[str]:
    """Name of the probed file as the provider lists it (movie, or series and episode)."""
    try:
        if hasattr(relation, "movie"):
            movie = relation.movie
            return f"{movie.name} ({movie.year})" if movie.year else movie.name
        episode = relation.episode
        series = episode.series.name if episode.series_id else ""
        number = f"S{episode.season_number or 0:02d}E{episode.episode_number or 0:02d}"
        return f"{series} {number}".strip()
    except Exception:
        return None


def forget(category_id: Optional[int] = None) -> None:
    """Forgets one result, or all of them: probed again at the next runs."""
    with _file_lock:
        results = {} if category_id is None else load_results()
        results.pop(str(category_id), None)
        os.makedirs(data_dir(), exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=data_dir(), suffix=".tmp")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump({"version": FILE_VERSION, "categories": results}, fh, ensure_ascii=False)
        os.replace(tmp, results_path())


def summary() -> Dict[str, int]:
    """Numbers of categories measured and failed (given up after MAX_ATTEMPTS, or still to retry)."""
    counts = {"measured": 0, "failed": 0, "retrying": 0}
    for entry in load_results().values():
        if not isinstance(entry, dict):
            continue
        if entry.get("status") == "ok":
            counts["measured"] += 1
        elif (entry.get("attempts") or 0) >= MAX_ATTEMPTS:
            counts["failed"] += 1
        else:
            counts["retrying"] += 1
    return counts


def export() -> Dict[str, Any]:
    """Measured categories in the case format of the Télé tests (app/src/test/resources/vod-language-cases/)."""
    return core.export_cases(load_results(), time.time())


def run(settings: Dict[str, Any]) -> int:
    """One run: probes categories one at a time until done, the time budget is spent or a viewer needs room."""
    from django.db import close_old_connections

    from . import data

    if not settings.get("probe_enabled", True):
        return 0
    redis_client = data.redis_client()
    if redis_client is None or must_yield(redis_client):
        return 0
    if not redis_client.set(LOCK_KEY, "1", nx=True, ex=LOCK_TTL):
        return 0  # Another run is going on
    min_free = core.query_int(settings.get("probe_min_free"), 1, 1, 50)
    pause = core.query_int(settings.get("probe_pause_seconds"), 5, 1, 600)
    started = time.monotonic()
    probed = 0
    try:
        results = load_results()
        for category, relation in candidates(settings, results, time.time()):
            if time.monotonic() - started > RUN_BUDGET or must_yield(redis_client):
                break
            profile = free_profile(relation.m3u_account, redis_client, min_free)
            if profile is None:
                continue  # No room on this account now; another account may have some
            if probe_one(category, relation, profile, redis_client, results.get(str(category.id)) or {}):
                probed += 1
                time.sleep(pause)
    finally:
        redis_client.delete(LOCK_KEY)
        close_old_connections()
    return probed


def schedule() -> None:
    """Runs every 10 minutes through Celery Beat (created once, kept in step with the plugin enabled flag)."""
    try:
        from core.scheduling import create_or_update_periodic_task
    except ImportError:
        return  # Not inside Dispatcharr (tests)
    try:
        create_or_update_periodic_task(task_name=SCHEDULE_NAME, celery_task_path=TASK_NAME, cron_expression="*/10 * * * *")
    except Exception:
        # Database not ready yet (first start before the migrations): set at the next start
        logger.warning("Télé API: probe schedule not set yet")
