"""Télé API: a Dispatcharr plugin serving the Télé player (https://github.com/Sim7b/tele).

Dispatcharr only imports the code of an enabled plugin; importing it installs the /api/tele/ routes in the
current web process (see routes.py), registers the Celery task of the VOD language probes and its schedule.
"""

import json
import logging
import os

from . import probe, routes, tasks  # noqa: F401  (tasks: registers the Celery task)
from .core import PLUGIN_VERSION

logger = logging.getLogger(__name__)

routes.install()
probe.schedule()

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
        if action == "status":
            return {"status": "ok", "message": status_message()}
        if action == "stream_health":
            from . import data, health

            client = data.redis_client()
            if params and "event" in params:
                health.record(client, params.get("event"), params.get("payload"))
                return {"status": "ok"}
            return {"status": "ok", "message": f"{health.tracked(client) if client else 0} streams with recent failures or plays"}
        if action == "probe_now":
            tasks.probe_vod_languages.delay()
            return {"status": "queued", "message": "VOD language probes started"}
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
        # Only this worker: the others refuse the requests once the plugin is disabled (views.PluginEnabled)
        routes.uninstall()


def status_message() -> str:
    from . import data

    lines = ["Routes installed under /api/tele/v1/" if (routes.installed() or routes.install()) else "Routes not installed, see the server logs"]
    sessions = data.all_sessions()
    if sessions is not None:
        lines.append(f"Sessions open: {len(sessions)}")
    counts = probe.summary()
    lines.append(f"VOD languages: {counts['measured']} categories measured, {counts['failed']} failed, {counts['retrying']} to retry")
    return "\n".join(lines)
