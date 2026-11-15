"""Secrets of the plugin (the TMDB key), kept in a file of the data folder readable by the server only.

Not in the plugin settings: Dispatcharr gives those to every standard user (the list of the plugins). The key is
never sent back: the admin page only learns whether one is set.
"""

from __future__ import annotations

import json
import os
import tempfile
from typing import Optional

from . import probe


def _path() -> str:
    return os.path.join(probe.data_dir(), "secrets.json")


def _read() -> dict:
    try:
        with open(_path(), encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def tmdb_key() -> Optional[str]:
    key = _read().get("tmdb_key")
    return key if isinstance(key, str) and key.strip() else None


def set_tmdb_key(key: Optional[str]) -> None:
    data = _read()
    if key:
        data["tmdb_key"] = key.strip()
    else:
        data.pop("tmdb_key", None)
    os.makedirs(probe.data_dir(), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=probe.data_dir(), suffix=".tmp")
    try:
        os.chmod(tmp, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh)
        os.replace(tmp, _path())
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
