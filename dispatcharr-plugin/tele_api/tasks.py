"""Celery task of the plugin: one run of VOD language probes (probe.run), every 10 minutes."""

import logging

from . import probe

logger = logging.getLogger(__name__)

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


@shared_task(name=probe.TASK_NAME, ignore_result=True)
def probe_vod_languages():
    settings = plugin_settings()
    if settings is None:
        return 0
    return probe.run(settings)
