"""Secrets of the plugin (the TMDB, OpenSubtitles and TheIntroDB keys, an OpenSubtitles account), kept in a file of the data folder readable by the server only.

Not in the plugin settings: Dispatcharr gives those to every standard user (the list of the plugins). The key is
never sent back: the admin page only learns whether one is set.
"""

from __future__ import annotations

import json
import os
import tempfile
from typing import Optional, Tuple

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
    _set("tmdb_key", key.strip() if key else None)


def opensubtitles_key() -> Optional[str]:
    key = _read().get("opensubtitles_key")
    return key if isinstance(key, str) and key.strip() else None


def set_opensubtitles_key(key: Optional[str]) -> None:
    _set("opensubtitles_key", key.strip() if key else None)


def opensubtitles_account() -> Optional[Tuple[str, str]]:
    account = _read().get("opensubtitles_account")
    if isinstance(account, list) and len(account) == 2 and all(isinstance(v, str) and v for v in account):
        return account[0], account[1]
    return None


def set_opensubtitles_account(username: Optional[str], password: Optional[str]) -> None:
    _set("opensubtitles_account", [username, password] if username and password else None)


def introdb_key() -> Optional[str]:
    key = _read().get("introdb_key")
    return key if isinstance(key, str) and key.strip() else None


def set_introdb_key(key: Optional[str]) -> None:
    _set("introdb_key", key.strip() if key else None)


def _set(name: str, value) -> None:
    data = _read()
    if value:
        data[name] = value
    else:
        data.pop(name, None)
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
