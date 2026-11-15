"""Registers the /api/tele/ routes in the Dispatcharr URL configuration.

The plugin API of Dispatcharr only offers POST /api/plugins/plugins/<key>/run/, restricted to admins. Télé
needs read endpoints for standard users, so the plugin adds its own routes when it loads: Dispatcharr imports
the code of an enabled plugin in every web process at startup, before any request is resolved.

The routes go first in the root URL patterns (before the catch-all of the web interface). Installing again
(plugin reload) replaces them, and every view also checks that the plugin is still enabled, since a disable
only reaches the process that handled it.
"""

from __future__ import annotations

import importlib
import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)

PREFIX = "api/tele/"
ROUTES_NAME = "tele_api_routes"


def _is_ours(pattern: Any) -> bool:
    return getattr(pattern, "tele_api_routes", False) is True


def install(urlconf: Optional[str] = None) -> bool:
    """Adds (or replaces) the plugin routes; False when Django is not configured or the URLs are unknown."""
    try:
        from django.conf import settings
        from django.urls import clear_url_caches, include, path

        if not settings.configured:
            return False
        module_name = urlconf or getattr(settings, "ROOT_URLCONF", None)
        if not module_name:
            return False
        module = importlib.import_module(module_name)
        patterns = getattr(module, "urlpatterns", None)
        if not isinstance(patterns, list):
            logger.warning("Télé API: %s has no urlpatterns list, routes not installed", module_name)
            return False

        from . import urls

        entry = path(PREFIX, include((urls.urlpatterns, "tele_api"), namespace="tele_api"))
        entry.tele_api_routes = True
        patterns[:] = [entry] + [p for p in patterns if not _is_ours(p)]
        clear_url_caches()
        logger.info("Télé API: routes installed under /%s", PREFIX)
        return True
    except Exception:
        logger.exception("Télé API: routes not installed")
        return False


def uninstall(urlconf: Optional[str] = None) -> None:
    try:
        from django.conf import settings
        from django.urls import clear_url_caches

        module = importlib.import_module(urlconf or settings.ROOT_URLCONF)
        patterns = getattr(module, "urlpatterns", None)
        if isinstance(patterns, list):
            patterns[:] = [p for p in patterns if not _is_ours(p)]
            clear_url_caches()
    except Exception:
        logger.exception("Télé API: routes not removed")


def installed(urlconf: Optional[str] = None) -> bool:
    try:
        from django.conf import settings

        module = importlib.import_module(urlconf or settings.ROOT_URLCONF)
        return any(_is_ours(p) for p in getattr(module, "urlpatterns", []))
    except Exception:
        return False
