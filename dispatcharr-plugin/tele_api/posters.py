"""Posters of the movies and series made light for the TVs: the image of a VOD logo of Dispatcharr (often a full-size
poster of the provider) fetched once, reduced to the width of a card, saved as JPEG and kept on disk.

Only images Dispatcharr knows (a VODLogo, by id) are served: no address comes from the request. Fetched from public
hosts only, without following a redirect to the local network, 15 MB at most. Needs Pillow; without it, the
feature is not announced and the TVs use the images of Dispatcharr.
"""

from __future__ import annotations

import io
import logging
import os
import tempfile
import threading
import urllib.parse
import urllib.request
from typing import Optional

from . import core, probe

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.posters")

WIDTHS = (185, 240, 342, 500)
MAX_SOURCE = 15 * 1024 * 1024
MAX_FOLDER = 400 * 1024 * 1024
QUALITY = 80
_writes = 0
_lock = threading.Lock()


def available() -> bool:
    try:
        import PIL.Image  # noqa: F401
    except ImportError:
        return False
    return True


def width_of(value) -> int:
    """The served width closest to the asked one (a few sizes only: the cache stays small)."""
    asked = core.to_int(value, 342) or 342
    return min(WIDTHS, key=lambda w: (abs(w - asked), w))


def folder() -> str:
    return os.path.join(probe.data_dir(), "posters")


def shrink(content: bytes, width: int) -> Optional[bytes]:
    """[content] (any image Pillow reads) as a JPEG [width] pixels wide at most; None when unreadable."""
    from PIL import Image

    try:
        Image.MAX_IMAGE_PIXELS = 50_000_000
        with Image.open(io.BytesIO(content)) as image:
            image.draft("RGB", (width * 2, width * 3))
            image = image.convert("RGB")
            if image.width > width:
                image = image.resize((width, max(1, round(image.height * width / image.width))), Image.LANCZOS)
            out = io.BytesIO()
            image.save(out, "JPEG", quality=QUALITY, optimize=True, progressive=True)
            return out.getvalue()
    except Exception:
        return None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch(url: str) -> Optional[bytes]:
    """The image at [url]: http(s) on a public host, no redirect followed, MAX_SOURCE at most."""
    parts = urllib.parse.urlsplit(url or "")
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    if not core.host_allowed(probe.resolve(parts.hostname)):
        return None
    request = urllib.request.Request(url, headers={"User-Agent": f"Tele API v{core.PLUGIN_VERSION}"})
    try:
        with urllib.request.build_opener(_NoRedirect).open(request, timeout=10) as response:
            content = response.read(MAX_SOURCE + 1)
    except (OSError, ValueError):
        return None
    return content if 0 < len(content) <= MAX_SOURCE else None


def local(path: str) -> Optional[bytes]:
    """An image Dispatcharr keeps in its data folder (uploaded logos): only under /data, MAX_SOURCE at most."""
    real = os.path.realpath(path)
    if not real.startswith("/data/") or not os.path.isfile(real) or os.path.getsize(real) > MAX_SOURCE:
        return None
    try:
        with open(real, "rb") as fh:
            return fh.read()
    except OSError:
        return None


def poster(logo_id: int, width: int) -> Optional[bytes]:
    """The light poster of the VOD logo [logo_id], from the disk or made now; None when there is none."""
    path = os.path.join(folder(), f"{logo_id}-{width}.jpg")
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        pass
    from apps.vod.models import VODLogo

    url = VODLogo.objects.filter(id=logo_id).values_list("url", flat=True).first()
    content = (local(url) if url.startswith("/data/") else fetch(url)) if url else None
    light = shrink(content, width) if content else None
    if light is None:
        return None
    os.makedirs(folder(), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=folder(), suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(light)
        os.replace(tmp, path)
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass
    _trim_now_and_then()
    return light


def _trim_now_and_then() -> None:
    """Every 200 posters made: the oldest ones removed past MAX_FOLDER (down to 80 %)."""
    global _writes
    with _lock:
        _writes += 1
        if _writes % 200:
            return
    try:
        files = [os.path.join(folder(), name) for name in os.listdir(folder()) if name.endswith(".jpg")]
        stats = sorted(((os.stat(f).st_atime, os.path.getsize(f), f) for f in files))
        total = sum(size for _, size, _ in stats)
        for _, size, path in stats:
            if total <= MAX_FOLDER * 0.8:
                break
            os.unlink(path)
            total -= size
    except OSError:
        logger.debug("Télé API: posters not trimmed", exc_info=True)
