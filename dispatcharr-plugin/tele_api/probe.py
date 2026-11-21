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

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.probe")

TASK_NAME = "tele_api.probe_vod_languages"
SCHEDULE_NAME = "Télé API: VOD languages"
LOCK_KEY = "tele_api:probe:lock"
# Titles asked for by a sheet of Télé: probed before the categories
TITLE_QUEUE = "tele_api:probe:titles"
MAX_QUEUED_TITLES = 50
MAX_KEPT_TITLES = 5000
YIELD_KEY = "tele_api:probe:yield"
LOCK_TTL = 15 * 60
# A run stops after this long, the next one goes on (every 10 minutes)
RUN_BUDGET = 8 * 60
PROBE_TIMEOUT = 25
MAX_REDIRECTS = 5
PROTOCOLS = "http,https,tcp,tls,crypto"
# Why the last probe of this process gave nothing (no address: they carry the provider credentials)
LAST_FAILURE: Optional[str] = None
_URL = __import__("re").compile(r"[a-z][a-z0-9+.-]*://\S+", __import__("re").IGNORECASE)


def _fail(reason: str) -> None:
    global LAST_FAILURE
    LAST_FAILURE = _URL.sub("<url>", reason)[:300]
    logger.warning("Télé API: probe failed: %s", LAST_FAILURE)
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


def titles_path() -> str:
    return os.path.join(data_dir(), "vod_media.json")


def _read(path: str) -> Dict[str, Dict[str, Any]]:
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    categories = data.get("categories") if isinstance(data, dict) else None
    return categories if isinstance(categories, dict) else {}


def _write(path: str, results: Dict[str, Dict[str, Any]]) -> None:
    os.makedirs(data_dir(), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=data_dir(), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump({"version": FILE_VERSION, "categories": results}, fh, ensure_ascii=False)
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def title_key(kind: str, uuid: str) -> str:
    return f"{kind}:{uuid}"


def title_result(kind: str, uuid: str) -> Optional[Dict[str, Any]]:
    return _read(titles_path()).get(title_key(kind, uuid))


def save_title(kind: str, uuid: str, entry: Dict[str, Any]) -> None:
    """Keeps the result of a title probe; the oldest ones go beyond MAX_KEPT_TITLES."""
    with _file_lock:
        results = _read(titles_path())
        results[title_key(kind, uuid)] = entry
        if len(results) > MAX_KEPT_TITLES:
            for key, _ in sorted(results.items(), key=lambda item: item[1].get("at") or 0)[: len(results) - MAX_KEPT_TITLES]:
                results.pop(key, None)
        _write(titles_path(), results)


def request_title(redis_client, kind: str, uuid: str) -> str:
    """Asks for a title to be probed: "queued", or "full" when too many titles wait already."""
    key = title_key(kind, uuid)
    queued = [core.to_text(k) for k in redis_client.lrange(TITLE_QUEUE, 0, -1)]
    if key in queued:
        return "queued"
    if len(queued) >= MAX_QUEUED_TITLES:
        return "full"
    redis_client.rpush(TITLE_QUEUE, key)
    return "queued"


def selection_path() -> str:
    return os.path.join(data_dir(), "groups.json")


def load_selection() -> Dict[str, Any]:
    """The VOD groups to process (probes and TMDB): every one, or the ones an admin selected on the Télé page."""
    try:
        with open(selection_path(), encoding="utf-8") as fh:
            parsed = core.parse_selection(json.load(fh))
    except (OSError, ValueError):
        parsed = None
    return parsed or {"mode": "all", "ids": []}


def save_selection(selection: Dict[str, Any]) -> None:
    os.makedirs(data_dir(), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=data_dir(), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(selection, fh)
        os.replace(tmp, selection_path())
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def selected_ids() -> Optional[set]:
    """Ids of the groups to process, None when every group is."""
    selection = load_selection()
    return None if selection["mode"] == "all" else set(selection["ids"])


def served_groups() -> Dict[str, Any]:
    """The groups chosen on the Télé page, by name and type, as the TVs know them (the standard API names them so)."""
    selected = selected_ids()
    if selected is None:
        return {"mode": "all"}
    from apps.vod.models import M3UVODCategoryRelation

    rows = (
        M3UVODCategoryRelation.objects.filter(category_id__in=selected)
        .values_list("category__name", "category__category_type").distinct()
    )
    groups = sorted({(name, kind) for name, kind in rows if name and kind in ("movie", "series")})
    return {"mode": "selected", "groups": [{"name": name, "type": kind} for name, kind in groups]}


def load_results() -> Dict[str, Dict[str, Any]]:
    """Results by category id; empty when the file is missing or damaged."""
    return _read(results_path())


def save_result(category_id: int, entry: Dict[str, Any]) -> None:
    """Writes one result (atomic replace of the file)."""
    with _file_lock:
        results = load_results()
        results[str(category_id)] = entry
        _write(results_path(), results)


def save_results(entries: Dict[int, Dict[str, Any]]) -> None:
    """Writes several results at once (a restored backup)."""
    if not entries:
        return
    with _file_lock:
        results = load_results()
        for category_id, entry in entries.items():
            results[str(category_id)] = entry
        _write(results_path(), results)


def measured_categories() -> List[Dict[str, Any]]:
    """Categories with measured languages, for the API."""
    measured = []
    for key, entry in load_results().items():
        if not isinstance(entry, dict) or entry.get("status") not in ("ok", "manual"):
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
    selected = selected_ids()
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
        if selected is not None and category.id not in selected:
            continue  # Not among the groups an admin chose to process
        previous = results.get(str(category.id)) or {}
        if previous.get("status") in ("manual", "excluded"):
            continue  # Set by an admin on the Télé page
        if previous.get("status") == "ok" and now - (previous.get("at") or 0) < REPROBE_AFTER:
            continue
        if previous.get("status") == "failed" and (previous.get("attempts") or 0) >= MAX_ATTEMPTS:
            continue
        if not probe_all and not core.category_needs_probe(category.name):
            continue
        relation = sample_relation(category, link.m3u_account)
        if relation is not None:
            yield category, relation


def sample_relation(category, account):
    """The latest file of [category] at [account]: the one probed."""
    from apps.vod.models import M3UEpisodeRelation, M3UMovieRelation

    if category.category_type == "series":
        return (
            M3UEpisodeRelation.objects.filter(series_relation__category=category, m3u_account=account)
            .select_related("m3u_account").order_by("-updated_at").first()
        )
    return (
        M3UMovieRelation.objects.filter(category=category, m3u_account=account)
        .select_related("m3u_account").order_by("-updated_at").first()
    )


def state_of(entry: Dict[str, Any]) -> str:
    """measured, manual, excluded, failed (given up), retrying, or pending (never probed)."""
    status = entry.get("status")
    if status == "ok":
        return "measured"
    if status in ("manual", "excluded"):
        return status
    if status == "failed":
        return "failed" if (entry.get("attempts") or 0) >= MAX_ATTEMPTS else "retrying"
    return "pending"


def all_categories() -> List[Dict[str, Any]]:
    """Every enabled VOD category of the Xtream accounts with its probe state, for the Télé page."""
    from apps.vod.models import M3UVODCategoryRelation

    results = load_results()
    selected = selected_ids()
    listed: Dict[int, Dict[str, Any]] = {}
    links = (
        M3UVODCategoryRelation.objects.filter(enabled=True, m3u_account__is_active=True, m3u_account__account_type="XC")
        .select_related("category", "m3u_account")
    )
    for link in links.iterator(chunk_size=500):
        category = link.category
        if category is None or category.id in listed:
            continue
        entry = results.get(str(category.id)) or {}
        listed[category.id] = {
            "id": category.id,
            "name": category.name,
            "type": category.category_type,
            "account": link.m3u_account.name,
            "uncertain": core.category_needs_probe(category.name),
            "state": state_of(entry),
            "audio": list(entry.get("audio") or []),
            "subtitles": list(entry.get("subtitles") or []),
            "quality": entry.get("quality"),
            "sample": entry.get("sample"),
            "attempts": entry.get("attempts") or 0,
            "probed_at": entry.get("at"),
            "estimated": core.group_languages(category.name),
            "selected": selected is None or category.id in selected,
            "titles": 0,
        }
    for category_id, count in title_counts().items():
        if category_id in listed:
            listed[category_id]["titles"] = count
    return sorted(listed.values(), key=lambda c: (str(c["name"]).lower(), c["id"]))


def title_counts() -> Dict[int, int]:
    """Movies and series of each VOD group at the active accounts (one grouped query per kind)."""
    from django.db.models import Count

    from apps.vod.models import M3UMovieRelation, M3USeriesRelation

    counts: Dict[int, int] = {}
    for model in (M3UMovieRelation, M3USeriesRelation):
        try:
            rows = (
                model.objects.filter(m3u_account__is_active=True, category__isnull=False)
                .values("category_id").annotate(n=Count("id"))
            )
            for row in rows:
                counts[row["category_id"]] = counts.get(row["category_id"], 0) + row["n"]
        except Exception:
            logger.debug("Télé API: titles not counted", exc_info=True)
    return counts


def category_link(category_id: int):
    from apps.vod.models import M3UVODCategoryRelation

    return (
        M3UVODCategoryRelation.objects.filter(
            category_id=category_id, enabled=True, m3u_account__is_active=True, m3u_account__account_type="XC"
        ).select_related("category", "m3u_account").first()
    )


def probe_category_now(category_id: int, settings: Dict[str, Any]) -> Tuple[bool, str]:
    """Probes one category here and now (an admin asked on the Télé page): (measured, what happened)."""
    from . import data

    client = data.redis_client()
    if client is None:
        return False, "Redis not available"
    link = category_link(category_id)
    if link is None:
        return False, "Unknown category"
    category = link.category
    relation = sample_relation(category, link.m3u_account)
    if relation is None:
        return False, f"{category.name}: no file to probe"
    min_free = core.query_int(settings.get("probe_min_free"), 1, 1, 50)
    profile = free_profile(link.m3u_account, client, min_free)
    if profile is None:
        return False, f"{category.name}: no free provider connection now (keeps {min_free} free)"
    previous = load_results().get(str(category.id)) or {}
    if previous.get("status") in ("manual", "excluded"):
        previous = {}
    started = time.monotonic()
    if not probe_one(category, relation, profile, client, previous):
        return False, f"{category.name}: the connection could not be reserved"
    entry = load_results().get(str(category.id)) or {}
    took = round(time.monotonic() - started, 1)
    if entry.get("status") == "ok":
        return True, f"{category.name}: measured in {took} s"
    return False, f"{category.name}: probe failed after {took} s: {LAST_FAILURE or 'unreadable ffprobe answer'}"


def set_manual(category_id: int, audio: List[str], subtitles: List[str]) -> bool:
    """Languages set by hand by an admin: given to Télé like a measure, and never probed again."""
    link = category_link(category_id)
    if link is None:
        return False
    category = link.category
    save_result(category.id, {
        "name": category.name, "type": category.category_type, "status": "manual", "at": time.time(),
        "audio": audio, "subtitles": subtitles,
    })
    return True


def set_excluded(category_id: int, excluded: bool) -> bool:
    link = category_link(category_id)
    if link is None:
        return False
    if excluded:
        save_result(link.category.id, {"name": link.category.name, "type": link.category.category_type, "status": "excluded", "at": time.time()})
    else:
        forget(link.category.id)
    return True


def retry_failed() -> int:
    """Forgets the categories given up after MAX_ATTEMPTS: probed again from the next run."""
    with _file_lock:
        results = load_results()
        failed = [key for key, entry in results.items() if isinstance(entry, dict) and entry.get("status") == "failed"]
        for key in failed:
            results.pop(key, None)
        if failed:
            _write(results_path(), results)
    return len(failed)


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
        "-show_streams", "-show_format", "-of", "json",
    ]
    if user_agent:
        command += ["-user_agent", user_agent]
    command.append(url)
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError:
        _fail("ffprobe not available")
        return None
    started = time.monotonic()
    try:
        while process.poll() is None:
            if must_yield(redis_client) or time.monotonic() - started > PROBE_TIMEOUT:
                process.kill()
                process.wait(timeout=5)
                _fail("stopped for a viewer" if must_yield(redis_client) else f"ffprobe gave no answer in {PROBE_TIMEOUT} s")
                return None
            time.sleep(0.5)
        output = process.stdout.read(core.MAX_PROBE_OUTPUT + 1) if process.stdout else b""
        if process.returncode != 0:
            error = (process.stderr.read(4000) if process.stderr else b"").decode("utf-8", errors="replace").strip()
            _fail(f"ffprobe exit {process.returncode}: {error.splitlines()[-1] if error else 'no message'}")
            return None
        return output.decode("utf-8", errors="replace")
    finally:
        if process.poll() is None:
            process.kill()
        if process.stdout:
            process.stdout.close()
        if process.stderr:
            process.stderr.close()


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
            _fail(f"the file address is not http(s) ({parts.scheme or 'no scheme'})")
            return None
        if parts.hostname != trusted_host and not core.host_allowed(resolve(parts.hostname), trusted):
            _fail("refused: a redirect leads to the local network (or a host that does not resolve)")
            return None
        request = urllib.request.Request(current, headers={"Range": "bytes=0-0", **({"User-Agent": user_agent} if user_agent else {})})
        try:
            with opener.open(request, timeout=10):
                return current
        except urllib.error.HTTPError as error:
            location = error.headers.get("Location") if error.code in (301, 302, 303, 307, 308) else None
            if not location:
                if error.code >= 500:
                    _fail(f"the provider answered HTTP {error.code}")
                    return None
                return current
            current = urljoin(current, location)
        except (OSError, ValueError) as error:
            _fail(f"the provider did not answer ({type(error).__name__})")
            return None
    _fail("too many redirects")
    return None


def probe_file(relation, profile, redis_client) -> Tuple[bool, Optional[Dict[str, Any]]]:
    """(slot reserved, media info) of the file of [relation] probed on [profile]."""
    from apps.m3u.connection_pool import release_profile_slot, reserve_profile_slot

    reserved, _, _ = reserve_profile_slot(profile, redis_client)
    if not reserved:
        return False, None
    try:
        url = relation.get_stream_url(profile)
        if not url:
            _fail("Dispatcharr gave no address for the file (credentials of the profile?)")
        account = relation.m3u_account
        user_agent = account.get_user_agent_string() if hasattr(account, "get_user_agent_string") else None
        from urllib.parse import urlsplit

        trusted = urlsplit(account.server_url or "").hostname if getattr(account, "server_url", None) else None
        url = safe_url(url, trusted, user_agent) if url else None
        output = run_ffprobe(url, user_agent, redis_client) if url else None
    finally:
        release_profile_slot(profile.id, redis_client)
    return True, core.media_info(output) if output else None


def probe_one(category, relation, profile, redis_client, previous: Dict[str, Any]) -> bool:
    """Probes one file of [category] on [profile]; False when the slot could not be reserved."""
    reserved, info = probe_file(relation, profile, redis_client)
    if not reserved:
        return False
    languages = {"audio": core.languages_of(info["audio"]), "subtitles": core.languages_of(info["subtitles"])} if info else None
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
        entry.update(status="ok", attempts=0, quality=core.quality_summary(info), **languages)
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
        _write(results_path(), results)


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


def title_relation(kind: str, uuid: str):
    """A file of the movie or episode [uuid] at an active Xtream account, None when unknown."""
    from apps.vod.models import M3UEpisodeRelation, M3UMovieRelation

    model, field = (M3UMovieRelation, "movie__uuid") if kind == "movie" else (M3UEpisodeRelation, "episode__uuid")
    return (
        model.objects.filter(**{field: uuid}, m3u_account__is_active=True, m3u_account__account_type="XC")
        .select_related("m3u_account").order_by("-updated_at").first()
    )


def probe_titles(redis_client, min_free: int, pause: int, started: float) -> int:
    """Probes the titles asked for by Télé sheets, oldest first, then removes them from the queue."""
    probed = 0
    while time.monotonic() - started <= RUN_BUDGET and not must_yield(redis_client):
        key = core.to_text(redis_client.lindex(TITLE_QUEUE, 0))
        if not key:
            break
        kind, _, uuid = key.partition(":")
        relation = title_relation(kind, uuid) if kind in core.MEDIA_KINDS and core.valid_uuid(uuid) else None
        if relation is None:
            redis_client.lrem(TITLE_QUEUE, 1, key)
            continue
        profile = free_profile(relation.m3u_account, redis_client, min_free)
        if profile is None:
            break  # No room now: the title waits for the next run
        reserved, info = probe_file(relation, profile, redis_client)
        if not reserved:
            break
        if info is None and must_yield(redis_client):
            break  # Stopped for a viewer: probed at the next run
        previous = title_result(kind, uuid) or {}
        if info is None:
            save_title(kind, uuid, {"status": "failed", "at": time.time(), "attempts": (previous.get("attempts") or 0) + 1})
        else:
            save_title(kind, uuid, {"status": "ok", "at": time.time(), "info": info})
        redis_client.lrem(TITLE_QUEUE, 1, key)
        probed += 1
        time.sleep(pause)
    return probed


LAST_RUN_KEY = "tele_api:probe:last_run"


def _remember_run(redis_client, **summary) -> None:
    """Outcome of a run, read by the Status action (the runs happen in the Celery worker)."""
    summary["at"] = time.time()
    # Every 10 minutes in each web worker: logged only when it did something
    idle = summary.get("outcome") == "done" and not summary.get("probed")
    (logger.debug if idle else logger.info)("Télé API: probe run %s", summary)
    if redis_client is None:
        return
    try:
        redis_client.setex(LAST_RUN_KEY, 7 * 86400, json.dumps(summary))
    except Exception:
        pass


def last_run(redis_client) -> Optional[Dict[str, Any]]:
    try:
        raw = redis_client.get(LAST_RUN_KEY)
        return json.loads(core.to_text(raw)) if raw else None
    except Exception:
        return None


def run(settings: Dict[str, Any]) -> int:
    """One run: probes categories one at a time until done, the time budget is spent or a viewer needs room."""
    from django.db import close_old_connections

    from . import data

    redis_client = data.redis_client()
    if not settings.get("probe_enabled", True):
        _remember_run(redis_client, outcome="probes turned off in the settings")
        return 0
    if redis_client is None:
        return 0
    if must_yield(redis_client):
        _remember_run(redis_client, outcome="paused: a viewer lacked a connection")
        return 0
    if not redis_client.set(LOCK_KEY, "1", nx=True, ex=LOCK_TTL):
        return 0  # Another run is going on
    min_free = core.query_int(settings.get("probe_min_free"), 1, 1, 50)
    pause = core.query_int(settings.get("probe_pause_seconds"), 5, 1, 600)
    started = time.monotonic()
    probed = seen = no_room = 0
    outcome = "done"
    try:
        probed += probe_titles(redis_client, min_free, pause, started)
        results = load_results()
        for category, relation in candidates(settings, results, time.time()):
            seen += 1
            if time.monotonic() - started > RUN_BUDGET:
                outcome = "time budget spent, goes on at the next run"
                break
            if must_yield(redis_client):
                outcome = "stopped: a viewer lacked a connection"
                break
            profile = free_profile(relation.m3u_account, redis_client, min_free)
            if profile is None:
                no_room += 1
                continue  # No room on this account now; another account may have some
            if probe_one(category, relation, profile, redis_client, results.get(str(category.id)) or {}):
                probed += 1
                time.sleep(pause)
    except Exception as error:
        outcome = f"error: {type(error).__name__}: {error}"[:300]
        logger.exception("Télé API: probe run failed")
    finally:
        redis_client.delete(LOCK_KEY)
        _remember_run(redis_client, outcome=outcome, probed=probed, candidates=seen, no_room=no_room)
        close_old_connections()
    return probed


def category_counts(settings: Dict[str, Any]) -> Dict[str, int]:
    """Enabled categories of the Xtream accounts, how many are uncertain, how many are still to probe."""
    from apps.vod.models import M3UVODCategoryRelation

    links = M3UVODCategoryRelation.objects.filter(enabled=True, m3u_account__is_active=True)
    all_enabled = links.values("category_id").distinct().count()
    xc = links.filter(m3u_account__account_type="XC").select_related("category")
    names = {link.category_id: link.category.name for link in xc if link.category is not None}
    uncertain = sum(1 for name in names.values() if core.category_needs_probe(name))
    to_probe = sum(1 for _ in candidates(settings, load_results(), time.time()))
    return {"enabled": all_enabled, "xtream": len(names), "uncertain": uncertain, "to_probe": to_probe}


def schedule() -> str:
    """Removes the Celery Beat schedule of earlier versions: the probes run in the web workers (runner.py), and
    the Celery workers, which only know the tasks of the plugins installed before they started, rejected it."""
    try:
        from core.scheduling import delete_periodic_task
    except ImportError:
        return "not inside Dispatcharr"
    try:
        # Asked only when it is there: Dispatcharr warns of every missing one, at every load of the plugin
        from django_celery_beat.models import PeriodicTask

        if not PeriodicTask.objects.filter(name=SCHEDULE_NAME).exists():
            return "none (the probes run in the web workers)"
        return "removed (the probes run in the web workers)" if delete_periodic_task(SCHEDULE_NAME) else "none (the probes run in the web workers)"
    except Exception as error:
        logger.warning("Télé API: former probe schedule not removed yet (%s)", type(error).__name__)
        return f"{type(error).__name__}: {error}"[:300]
