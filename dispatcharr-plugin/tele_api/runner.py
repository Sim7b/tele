"""Runs the probes inside the Dispatcharr web processes, so that they never depend on the Celery workers.

A Celery worker only knows the task of a plugin installed before it started: without a restart of Dispatcharr,
the probes would never run. Each web process that loads the plugin starts a background runner (a greenlet
under the gevent of uWSGI); the Redis lock of probe.run lets one run at a time on the whole server, the other
runners finding the lock taken. A newer load of the plugin (update, reload) stops the runners of the older one.
"""

from __future__ import annotations

import logging
import sys
import threading
import time

from . import probe

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.runner")

EVERY = 10 * 60
FIRST_DELAY = 60
WAKE_KEY = "tele_api:probe:wake"
CHECK_WAKE_EVERY = 15
_HOLDER = "_tele_api_runner_token"


def wake(redis_client) -> None:
    """Asks the runners to start a run now (the Probe now action)."""
    try:
        redis_client.setex(WAKE_KEY, 15 * 60, "1")
    except Exception:
        logger.debug("Télé API: wake not set", exc_info=True)


def _settings():
    from .tasks import plugin_settings

    return plugin_settings()


def _loop(token: object) -> None:
    from . import data

    next_run = time.monotonic() + FIRST_DELAY
    while getattr(sys, _HOLDER, None) is token:
        time.sleep(CHECK_WAKE_EVERY)
        try:
            client = data.redis_client()
            woken = bool(client is not None and client.delete(WAKE_KEY))
            if not woken and time.monotonic() < next_run:
                continue
            next_run = time.monotonic() + EVERY
            settings = _settings()
            if settings is None:
                continue  # Plugin disabled: the runner waits, a disable stops nothing else
            from . import stats

            stats.sample_peaks(client)
            probe.run(settings)
            from . import tmdb

            tmdb.run(settings, client, lambda: getattr(sys, _HOLDER, None) is not token)
        except Exception:
            logger.exception("Télé API: probe runner error")
        finally:
            try:
                from django.db import close_old_connections

                close_old_connections()
            except Exception:
                pass
    logger.info("Télé API: probe runner of an older load stopped")


def start() -> bool:
    """Starts the runner of this load of the plugin in a web process (not in Celery nor in tests)."""
    if "uwsgi" not in sys.modules and "daphne" not in sys.argv[0:1]:
        return False
    token = object()
    setattr(sys, _HOLDER, token)  # The runners of an older load see it and stop
    thread = threading.Thread(target=_loop, args=(token,), name="tele-api-probes", daemon=True)
    thread.start()
    return True
