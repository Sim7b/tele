"""Télé API: a Dispatcharr plugin serving the Télé player (https://github.com/Sim7b/tele).

Dispatcharr only imports the code of an enabled plugin; importing it installs the /api/tele/ routes in the
current web process (see routes.py).
"""

import logging

from . import routes
from .core import PLUGIN_VERSION

logger = logging.getLogger(__name__)

routes.install()


class Plugin:
    name = "Télé API"
    version = PLUGIN_VERSION
    description = "Endpoints for the Télé Android TV player, open to standard users."
    author = "Télé"
    help_url = "https://github.com/Sim7b/tele"

    fields = [
        {"id": "allow_release", "label": "Let players close their own sessions", "type": "boolean", "default": True},
    ]

    actions = [
        {"id": "status", "label": "Status", "description": "Checks that the /api/tele/ routes are installed."},
    ]

    def run(self, action, params, context):
        if action == "status":
            if routes.installed() or routes.install():
                return {"status": "ok", "message": "Routes installed under /api/tele/v1/"}
            return {"status": "error", "message": "Routes not installed, see the server logs"}
        return {"status": "error", "message": f"Unknown action: {action}"}

    def stop(self, context):
        # Only this worker: the others refuse the requests once the plugin is disabled (views.PluginEnabled)
        routes.uninstall()
