"""Télé API: a Dispatcharr plugin serving the Télé player (https://github.com/Sim7b/tele).

Dispatcharr only imports the code of an enabled plugin; importing it installs the /api/tele/ routes in the
current web process (see routes.py) and starts the runner of its background work (runner.py).
"""

import json
import logging
import os
import time

from . import probe, routes, runner
from .core import PLUGIN_VERSION

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api")

# What happened when this worker loaded the plugin, for the logs and the Status action
LOAD = {"pid": os.getpid(), "package": __name__, "loaded_at": time.time()}
LOAD["routes"] = routes.install()
LOAD["routes_error"] = routes.LAST_ERROR
LOAD["schedule"] = probe.schedule()
LOAD["runner"] = runner.start()
logger.warning(
    "Télé API %s loaded in process %s (package %s): routes %s%s, probe schedule %s, probe runner %s",
    PLUGIN_VERSION, LOAD["pid"], __name__,
    "installed under /api/tele/" if LOAD["routes"] else "NOT installed",
    f" ({LOAD['routes_error']})" if LOAD["routes_error"] else "",
    LOAD["schedule"],
    "started" if LOAD["runner"] else "not in this process",
)

# Settings and actions: those of the manifest, read by Dispatcharr without running this code
with open(os.path.join(os.path.dirname(__file__), "plugin.json"), encoding="utf-8") as _manifest:
    _MANIFEST = json.load(_manifest)


class Plugin:
    name = "Télé API"
    version = PLUGIN_VERSION
    description = "Endpoints and a page for the Télé Android TV player, open to standard users."
    author = "Télé"
    help_url = "/api/tele/ui/"
    fields = _MANIFEST.get("fields", [])
    actions = _MANIFEST.get("actions", [])

    def run(self, action, params, context):
        if action == "open_page":
            return {"status": "ok", "message": page_link(context), "url": "/api/tele/ui/"}
        if action == "status":
            return {"status": "ok", "message": status_message()}
        if action == "stream_health":
            from . import data, health

            client = data.redis_client()
            if params and "event" in params:
                health.record(client, params.get("event"), params.get("payload"))
                if params.get("event") == "channel_start":
                    from . import stats

                    stats.record_view(client, params.get("payload"))
                return {"status": "ok"}
            return {"status": "ok", "message": f"{health.tracked(client) if client else 0} streams with recent failures or plays"}
        if action == "sort_streams":
            from . import data, streamsort

            done = streamsort.run(data.redis_client())
            if done["outcome"] == "running":
                return {"status": "ok", "message": "A sort is already running"}
            if done["outcome"] != "done":
                return {"status": "error", "message": f"Streams not sorted ({done['outcome']})"}
            return {"status": "ok", "message": streamsort.describe(done)}
        if action == "probe_one":
            return {"status": "ok", "message": probe_one_now(context.get("settings") or {})}
        if action == "probe_now":
            from . import data

            client = data.redis_client()
            if client is not None:
                runner.wake(client)
            return {"status": "queued", "message": "VOD language probes asked to start: within 15 seconds in the web workers"}
        if action == "reload":
            return {"status": "ok", "message": reload_everywhere()}
        if action == "export_languages":
            path = os.path.join(probe.data_dir(), "vod-language-cases.json")
            os.makedirs(probe.data_dir(), exist_ok=True)
            data = probe.export()
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=1)
            return {"status": "ok", "message": f"{len(data['cases'])} cases exported", "file": path}
        if action == "reset_probes":
            probe.forget()
            return {"status": "ok", "message": "Measured languages forgotten"}
        return {"status": "error", "message": f"Unknown action: {action}"}

    def stop(self, context):
        # Only this worker: the others refuse the requests once the plugin is disabled (views.PluginEnabled), and
        # their runners stop once Dispatcharr unloads the plugin there
        routes.uninstall()
        runner.stop()


def page_link(context) -> str:
    """Where the Télé page is: the plugin cannot know the address the admin uses for Dispatcharr."""
    return "Télé page: add /api/tele/ui/ to the address you use for Dispatcharr, e.g. http://192.168.1.10:9191/api/tele/ui/"


def status_message() -> str:
    """Everything worth checking when the plugin seems to do nothing, from the worker that answers."""
    import shutil

    from . import data
    from .views import PLUGIN_KEY

    lines = [f"Télé API {PLUGIN_VERSION}, key {PLUGIN_KEY}, worker {os.getpid()} (loaded by {LOAD['pid']})"]
    installed = routes.installed() or routes.install()
    lines.append("Routes: installed under /api/tele/v1/" if installed else f"Routes: NOT installed ({routes.LAST_ERROR})")
    try:
        from django.urls import resolve

        match = resolve("/api/tele/v1/info/")
        lines.append(f"/api/tele/v1/info/ resolves to {getattr(match.func, 'view_class', match.func).__name__}")
    except Exception as error:
        lines.append(f"/api/tele/v1/info/ does not resolve: {type(error).__name__}")
    lines.append(f"Former Celery schedule: {LOAD['schedule']}")
    lines.append(f"Probe runner in this worker: {'started' if LOAD.get('runner') else 'no'}")
    lines.append(f"ffprobe: {shutil.which('ffprobe') or 'NOT found'}")
    folder = probe.data_dir()
    try:
        os.makedirs(folder, exist_ok=True)
        lines.append(f"Data folder: {folder} ({'writable' if os.access(folder, os.W_OK) else 'NOT writable'})")
    except OSError as error:
        lines.append(f"Data folder: {folder} NOT usable ({error.strerror})")
    client = data.redis_client()
    lines.append("Redis: " + ("ok" if client is not None else "NOT available"))
    sessions = data.all_sessions()
    if sessions is not None:
        lines.append(f"Sessions open: {len(sessions)}")
    if client is not None:
        last = probe.last_run(client)
        if last:
            ago = int((time.time() - (last.get("at") or 0)) // 60)
            lines.append(
                f"Last probe run: {ago} min ago, {last.get('outcome')}"
                + (f" (probed {last.get('probed')}, candidates {last.get('candidates')}, no free connection {last.get('no_room')})"
                   if "probed" in last else "")
            )
        else:
            lines.append("Last probe run: never")
    try:
        from apps.plugins.models import PluginConfig

        settings = PluginConfig.objects.filter(key=PLUGIN_KEY).values_list("settings", flat=True).first() or {}
        counts_db = probe.category_counts(settings)
        lines.append(
            f"VOD categories: {counts_db['enabled']} enabled, {counts_db['xtream']} of Xtream accounts (the only ones probed), "
            f"{counts_db['uncertain']} uncertain, {counts_db['to_probe']} to probe now (scope: {settings.get('probe_scope', 'uncertain')})"
        )
    except Exception as error:
        lines.append(f"VOD categories: unknown ({type(error).__name__})")
    try:
        from . import tmdb, vault

        tmdb_counts = tmdb.counts()
        lines.append(
            f"TMDB: key {'set' if vault.tmdb_key() else 'NOT set (Télé page)'}, "
            f"{tmdb_counts['matched']} titles matched, {tmdb_counts['unmatched']} without a sure match"
        )
        missed = tmdb.unmatched(5)
        if missed:
            lines.append("Without a sure match, e.g.: " + "; ".join(f"{m['name']} → \"{m['query']}\" {m['year'] or ''}".strip() for m in missed))
    except Exception as error:
        lines.append(f"TMDB: unknown ({type(error).__name__})")
    counts = probe.summary()
    lines.append(f"VOD languages: {counts['measured']} categories measured, {counts['failed']} failed, {counts['retrying']} to retry")
    message = "\n".join(lines)
    logger.warning("Télé API status:\n%s", message)
    return message


def probe_one_now(settings) -> str:
    """Probes the first category still to probe, here and now (not through Celery), and says what happened."""
    from . import data

    client = data.redis_client()
    if client is None:
        return "Redis not available"
    found = next(iter(probe.candidates(settings, probe.load_results(), time.time())), None)
    if found is None:
        return "No category to probe (all measured, or none uncertain: try the scope Every category)"
    category, relation = found
    min_free = core_query_int(settings.get("probe_min_free"), 1)
    profile = probe.free_profile(relation.m3u_account, client, min_free)
    if profile is None:
        return f"{category.name}: no free provider connection now (keeps {min_free} free)"
    started = time.monotonic()
    reserved = probe.probe_one(category, relation, profile, client, probe.load_results().get(str(category.id)) or {})
    if not reserved:
        return f"{category.name}: the connection could not be reserved"
    entry = probe.load_results().get(str(category.id)) or {}
    took = round(time.monotonic() - started, 1)
    if entry.get("status") == "ok":
        return f"{category.name}: measured in {took} s, audio {entry.get('audio')}, subtitles {entry.get('subtitles')} (file: {entry.get('sample')})"
    return f"{category.name}: probe failed after {took} s ({entry.get('attempts')} attempt(s)): {probe.LAST_FAILURE or 'unreadable ffprobe answer'}"


def core_query_int(value, default):
    from .core import query_int

    return query_int(value, default, 1, 50)


def reload_everywhere() -> str:
    """Has every Dispatcharr process load this version of the plugin, without restarting Dispatcharr.

    The web processes reload it at their next plugin lookup or system event (the reload token of Dispatcharr);
    the Celery workers are asked to restart their pool, which reloads the plugins, when they allow it.
    """
    lines = []
    try:
        from apps.plugins.loader import PluginManager

        manager = PluginManager.get()
        manager._touch_reload_token()
        manager.discover_plugins(sync_db=False, use_cache=True)
        lines.append("Web workers: reload asked (each one reloads the plugin at its next request on the plugins or system event)")
    except Exception as error:
        lines.append(f"Web workers: reload not asked ({type(error).__name__})")
    try:
        from celery import current_app

        replies = current_app.control.pool_restart(reload=True, reply=True, timeout=3) or []
        lines.append(f"Celery workers: pool restart asked, {len(replies)} answered")
    except Exception as error:
        lines.append(f"Celery workers: not restarted ({type(error).__name__}), not needed: the probes run in the web workers")
    return "\n".join(lines)
