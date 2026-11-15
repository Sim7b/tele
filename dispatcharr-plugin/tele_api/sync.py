"""Storage of the sync between the TVs of a user: one JSON file per Dispatcharr user in the data folder."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from typing import Any, Dict, Tuple

from . import core, probe

_lock = threading.Lock()


def path(user_id: Any) -> str:
    return os.path.join(probe.data_dir(), "sync", f"{int(user_id)}.json")


def load(user_id: Any) -> Dict[str, Dict[str, Any]]:
    try:
        with open(path(user_id), encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    merged, _ = core.sync_merge(data.get("items") if isinstance(data, dict) else None, None, int(time.time() * 1000))
    return merged


def merge(user_id: Any, changes: Any) -> Tuple[Dict[str, Dict[str, Any]], int]:
    """Merges the changes of a TV into the items of the user, written atomically; (items, changes kept)."""
    with _lock:
        merged, kept = core.sync_merge(load(user_id), changes, int(time.time() * 1000))
        if kept:
            folder = os.path.dirname(path(user_id))
            os.makedirs(folder, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=folder, suffix=".tmp")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    json.dump({"version": 1, "items": merged}, fh, ensure_ascii=False, separators=(",", ":"))
                os.replace(tmp, path(user_id))
            except Exception:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
        return merged, kept
