"""Backup of what an admin set on the Télé page: the VOD groups served, the languages set by hand or excluded, and
the TMDB ids. Restored on the same server (by id) or on another one (by name and type): ids differ between servers.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, Iterable, List, Optional, Tuple

from . import core, probe, tmdb

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.backup")

NAME_CHUNK = 500


def _categories() -> Dict[int, Tuple[str, str]]:
    """Category id -> (name, type) of the VOD categories of the server."""
    from apps.vod.models import M3UVODCategoryRelation

    rows = M3UVODCategoryRelation.objects.values_list("category_id", "category__name", "category__category_type").distinct()
    return {row[0]: (row[1], row[2]) for row in rows.iterator(chunk_size=2000) if row[0] and row[1]}


def export() -> Dict[str, Any]:
    categories = _categories()
    selection = probe.load_selection()
    groups = [
        {"id": category_id, "name": categories[category_id][0], "type": categories[category_id][1]}
        for category_id in selection["ids"] if category_id in categories
    ]
    languages = []
    for key, entry in probe.load_results().items():
        if not isinstance(entry, dict) or entry.get("status") not in ("manual", "excluded"):
            continue
        languages.append({
            "id": core.to_int(key), "name": entry.get("name"), "type": entry.get("type"), "status": entry.get("status"),
            "audio": list(entry.get("audio") or []), "subtitles": list(entry.get("subtitles") or []),
        })
    return {
        "format": core.BACKUP_FORMAT,
        "version": 1,
        "plugin": core.PLUGIN_VERSION,
        "exported_at": int(time.time()),
        "groups": {"mode": selection["mode"], "groups": groups},
        "languages": languages,
        "tmdb": tmdb.export_rows(),
    }


def _category_ids(items: Iterable[Dict[str, Any]], categories: Dict[int, Tuple[str, str]]) -> List[Tuple[Dict[str, Any], int]]:
    """(item, category id here): the same id when it names the same category, else the category of that name and type."""
    by_name = {(name, kind): category_id for category_id, (name, kind) in categories.items()}
    found = []
    for item in items:
        same = categories.get(item.get("id")) if item.get("id") else None
        if same == (item["name"], item["type"]):
            found.append((item, item["id"]))
        elif (item["name"], item["type"]) in by_name:
            found.append((item, by_name[(item["name"], item["type"])]))
    return found


def _title_ids(rows: List[Dict[str, Any]]) -> List[Tuple[Dict[str, Any], int]]:
    """(row, movie or series id here) of the TMDB rows: the same id when it has the same name, else a title of that name."""
    from apps.vod.models import Movie, Series

    found = []
    for kind, model in (("movie", Movie), ("series", Series)):
        of_kind = [row for row in rows if row["kind"] == kind]
        names: Dict[int, str] = {}
        ids = [row["item"] for row in of_kind if row["item"]]
        for start in range(0, len(ids), NAME_CHUNK):
            names.update(dict(model.objects.filter(id__in=ids[start:start + NAME_CHUNK]).values_list("id", "name")))
        missing = []
        for row in of_kind:
            if row["item"] and row["item"] in names and (not row["name"] or names[row["item"]] == row["name"]):
                found.append((row, row["item"]))
            elif row["name"]:
                missing.append(row)
        for start in range(0, len(missing), NAME_CHUNK):
            chunk = missing[start:start + NAME_CHUNK]
            by_name: Dict[str, int] = {}
            for item_id, name in model.objects.filter(name__in=[row["name"] for row in chunk]).values_list("id", "name"):
                by_name.setdefault(name, item_id)
            found.extend((row, by_name[row["name"]]) for row in chunk if row["name"] in by_name)
    return found


def restore(data: Any) -> Optional[Dict[str, int]]:
    """Restores a backup; None when it is none. Counts what was restored and what has no match on this server."""
    parsed = core.parse_backup(data)
    if parsed is None:
        return None
    categories = _categories()
    groups = _category_ids(parsed["groups"], categories)
    if parsed["mode"] == "all":
        probe.save_selection({"mode": "all", "ids": []})
    elif parsed["mode"] == "selected":
        probe.save_selection({"mode": "selected", "ids": sorted({category_id for _, category_id in groups})})
    languages = _category_ids(parsed["languages"], categories)
    entries = {}
    for item, category_id in languages:
        entry = {"name": item["name"], "type": item["type"], "status": item["status"], "at": time.time()}
        if item["status"] == "manual":
            entry.update(audio=item["audio"], subtitles=item["subtitles"])
        entries[category_id] = entry
    probe.save_results(entries)
    titles = _title_ids(parsed["tmdb"])
    tmdb.import_rows([(row["kind"], item_id, row["tmdb"], row["name"], row["manual"]) for row, item_id in titles])
    counts = {
        "groups": len(groups), "groups_missing": len(parsed["groups"]) - len(groups),
        "languages": len(languages), "languages_missing": len(parsed["languages"]) - len(languages),
        "tmdb": len(titles), "tmdb_missing": len(parsed["tmdb"]) - len(titles),
    }
    logger.info("Télé API: backup restored: %s", counts)
    return counts
