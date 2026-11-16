"""Episodes of the series being watched or popular on the server, loaded from the provider in advance, so their sheet
opens on a TV without waiting for the provider.

Dispatcharr loads the episodes of a series when its sheet is first opened (and again after a day): the provider-info
action of its series API. The plugin calls that very action, in the background, for a few series per run, so
Dispatcharr marks them fresh exactly as if a TV had opened them. No stream connection is used: it is an API call to
the provider.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List

from . import core, stats

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.prefetch")

PER_RUN = 12
PAUSE = 2.0
FRESH_FOR = 20 * 3600
WATCHED_DAYS = 3
POPULAR_DAYS = 30


def candidates(redis_client) -> List[int]:
    """Series ids: the ones of the episodes watched lately first, then the most watched ones."""
    from apps.vod.models import Episode

    uuids: List[str] = []
    for day in stats.vod_counts(redis_client, WATCHED_DAYS)[1]:
        for key in day:
            key = core.to_text(key) or ""
            if key.startswith("episode:"):
                uuids.append(key.split(":", 1)[1])
    watched = list(Episode.objects.filter(uuid__in=uuids[:500]).values_list("series_id", flat=True).distinct())
    popular = [t["id"] for t in stats.popular(redis_client, POPULAR_DAYS, limit=40) if t["kind"] == "series"]
    return list(dict.fromkeys(i for i in watched + popular if i))


def _admin():
    from django.contrib.auth import get_user_model

    model = get_user_model()
    return model.objects.filter(is_active=True, user_level__gte=10).order_by("id").first()


def refresh(series_id: int, relation, admin) -> bool:
    """Has Dispatcharr load the episodes of [relation] as its series sheet does; False when it failed."""
    try:
        from apps.vod.api_views import SeriesViewSet
        from rest_framework.test import APIRequestFactory, force_authenticate

        request = APIRequestFactory().get(
            f"/api/vod/series/{series_id}/provider-info/", {"relation_id": relation.id, "include_episodes": "true"}
        )
        force_authenticate(request, user=admin)
        response = SeriesViewSet.as_view({"get": "series_info"})(request, pk=series_id)
        return 200 <= getattr(response, "status_code", 500) < 300
    except Exception:
        logger.debug("Télé API: series %s not loaded through the API, directly then", series_id, exc_info=True)
    try:
        from apps.vod.tasks import refresh_series_episodes

        refresh_series_episodes(relation.m3u_account, relation.series, relation.external_series_id)
        return True
    except Exception:
        logger.debug("Télé API: episodes of series %s not loaded", series_id, exc_info=True)
        return False


def run(redis_client, should_stop) -> Dict[str, Any]:
    """Loads the episodes of up to PER_RUN series whose episodes are older than FRESH_FOR."""
    if redis_client is None:
        return {"loaded": 0}
    from datetime import datetime, timedelta, timezone

    from apps.vod.models import M3USeriesRelation

    admin = _admin()
    if admin is None:
        return {"loaded": 0}
    stale_before = datetime.now(timezone.utc) - timedelta(seconds=FRESH_FOR)
    loaded = 0
    for series_id in candidates(redis_client):
        if loaded >= PER_RUN or should_stop():
            break
        relations = (
            M3USeriesRelation.objects.filter(series_id=series_id, m3u_account__is_active=True)
            .select_related("m3u_account", "series")
        )
        for relation in relations:
            if relation.last_episode_refresh and relation.last_episode_refresh > stale_before:
                continue
            if refresh(series_id, relation, admin):
                loaded += 1
            time.sleep(PAUSE)
    if loaded:
        logger.info("Télé API: episodes of %d series loaded in advance", loaded)
    return {"loaded": loaded}
