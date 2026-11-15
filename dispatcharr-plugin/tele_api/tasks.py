"""Celery task of the plugin: one run of VOD language probes (probe.run), every 10 minutes."""

import logging

from . import probe

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.tasks")

try:
    from celery import shared_task
except ImportError:  # Tests without Celery: the function stays callable
    def shared_task(*args, **kwargs):
        def wrap(function):
            function.delay = function
            return function

        return wrap


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


TASK_SEEN_KEY = "tele_api:probe:task_seen"


@shared_task(name=probe.TASK_NAME, ignore_result=True)
def probe_vod_languages():
    import json
    import os
    import time

    from . import data

    client = data.redis_client()
    settings = plugin_settings()
    # Proof that the Celery worker runs the task (read by the Status action)
    if client is not None:
        try:
            client.setex(TASK_SEEN_KEY, 7 * 86400, json.dumps({"at": time.time(), "pid": os.getpid(), "enabled": settings is not None}))
        except Exception:
            pass
    logger.info("Télé API: probe task received (plugin %s)", "enabled" if settings is not None else "disabled")
    if settings is None:
        return 0
    # Every 10 minutes: the provider connections used, for the peaks of the statistics
    from . import data, stats

    stats.sample_peaks(data.redis_client())
    return probe.run(settings)
