"""Settings of the plugin read from the database, for its background runners (runner.py), which have no request.

The plugin registers no Celery task any more: the Celery workers only know the tasks of the plugins installed
before they started, and registering it again at every load of the plugin made Celery warn of a duplicate.
"""

import logging

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.tasks")


def plugin_settings():
    """Enabled flag and settings of the plugin, read from the database (a worker has no request)."""
    try:
        from apps.plugins.models import PluginConfig

        from .views import PLUGIN_KEY

        row = PluginConfig.objects.filter(key=PLUGIN_KEY).values("enabled", "settings").first()
        if not row or not row["enabled"]:
            return None
        return row["settings"] or {}
    except Exception:
        logger.exception("Télé API: plugin settings unavailable")
        return None
