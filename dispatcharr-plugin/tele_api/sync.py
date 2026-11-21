"""Storage of the sync between the TVs of a user: one JSON file per Dispatcharr user in the data folder, and one per
profile of the TVs other than their main one ("<user>-<profile>.json", the profile named the same on every TV)."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from typing import Any, Dict, Optional, Tuple

from . import core, probe

_lock = threading.Lock()


MAX_PROFILES = 8


def path(user_id: Any, profile: Optional[str] = None) -> str:
    """The file of [user_id], or of one of its profiles ([profile]: checked by core.sync_profile)."""
    name = f"{int(user_id)}-{profile}.json" if profile else f"{int(user_id)}.json"
    return os.path.join(probe.data_dir(), "sync", name)


def profile_allowed(user_id: Any, profile: Optional[str]) -> bool:
    """A profile already kept, or room for one more ([MAX_PROFILES] per user)."""
    if not profile or os.path.exists(path(user_id, profile)):
        return True
    prefix = f"{int(user_id)}-"
    try:
        kept = [name for name in os.listdir(os.path.dirname(path(user_id))) if name.startswith(prefix) and name.endswith(".json")]
    except OSError:
        kept = []
    return len(kept) < MAX_PROFILES


def load(user_id: Any, profile: Optional[str] = None) -> Dict[str, Dict[str, Any]]:
    try:
        with open(path(user_id, profile), encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    merged, _ = core.sync_merge(data.get("items") if isinstance(data, dict) else None, None, int(time.time() * 1000))
    return merged


def merge(user_id: Any, changes: Any, profile: Optional[str] = None) -> Tuple[Dict[str, Dict[str, Any]], int]:
    """Merges the changes of a TV into the items of the user (or of one of its profiles), written atomically;
    (items, changes kept)."""
    with _lock:
        merged, kept = core.sync_merge(load(user_id, profile), changes, int(time.time() * 1000))
        if kept:
            folder = os.path.dirname(path(user_id, profile))
            os.makedirs(folder, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=folder, suffix=".tmp")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    json.dump({"version": 1, "items": merged}, fh, ensure_ascii=False, separators=(",", ":"))
                os.replace(tmp, path(user_id, profile))
            except Exception:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
        return merged, kept
