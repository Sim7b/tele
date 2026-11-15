# Télé API: Dispatcharr plugin

A [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) plugin with endpoints for the
[Télé](https://github.com/Sim7b/tele) Android TV player. They work with the API key of a **standard user**,
no admin key needed:

- **Channels in one call**: the channels of the user with their group, guide key and current / next program,
  by pages, in place of the channels, groups and EPG data lists.
- **Free provider connections**: by account and in total, read from the counters Dispatcharr checks before
  opening a stream (`/proxy/stats/` needs an admin key).
- **Playback sessions**: the sessions of the user, the ones of this TV marked, and closing them (a preview left
  open, the previous channel) so the TV never holds two provider connections.

Télé uses the plugin when it is installed and falls back to the standard Dispatcharr API otherwise.

## Installation

1. Download `tele_api-<version>.zip` (artifact `tele-api-plugin` of the CI run, or `scripts/build_zip.sh`).
2. In Dispatcharr: **Plugins → Import**, choose the zip, then enable **Télé API**.
3. Restart Dispatcharr once (`docker restart dispatcharr`), so that every web worker loads the routes.
   **Status** on the plugin card checks the routes of the worker that answers.

## How it works

The plugin API of Dispatcharr only has `POST /api/plugins/plugins/<key>/run/`, which needs an admin. So the
plugin adds its own routes under `/api/tele/` when Dispatcharr loads it (`tele_api/routes.py`), with the
authentication of the Dispatcharr API (`X-API-Key` or JWT) and these rules:

- every endpoint needs a standard user or above, and answers 403 once the plugin is disabled;
- channels go through `channels_queryset_for_user` of Dispatcharr: level, adult content filter and channel
  profiles of the user apply, and hidden channels are left out, as in the Dispatcharr channel list;
- a standard user only sees and closes their own sessions, without the address and player of other TVs;
  each session id sent is read again from Redis before anything is closed;
- admins can list (`?all=1`) and close the sessions of every user.

Adding routes this way is not part of the plugin API: a Dispatcharr update could change it. Télé checks
`/api/tele/v1/info/` first and uses the standard API when it does not answer.

## API (v1)

All responses are JSON with `"api": 1`. Errors: 401 / 403 (key, level, plugin disabled), 400 (malformed
request), 503 (`redis_unavailable`).

### `GET /api/tele/v1/info/`

```json
{"plugin": "tele_api", "version": "0.1.0", "api": 1, "features": ["channels", "slots", "sessions"],
 "server_time": 1762720000.0, "user": {"username": "salon", "admin": false}, "release_allowed": true}
```

### `GET /api/tele/v1/channels/?page=1&page_size=1000&programs=1`

`page_size` from 1 to 5000; a page past the end is empty. With `programs=0` the page has an `ETag`; `If-None-Match` then answers 304 while
nothing changed.

```json
{"api": 1, "count": 2450, "page": 1, "pages": 3, "page_size": 1000, "results": [
  {"id": 12, "uuid": "…", "number": 1, "name": "TF1", "group": {"id": 3, "name": "TNT"}, "logo_id": 40,
   "tvg_id": "TF1.fr", "epg_data_id": 8, "is_radio": false, "catchup_days": 7,
   "now": {"title": "Journal", "start": "2026-11-09T20:00:00+00:00", "end": "2026-11-09T20:40:00+00:00"},
   "next": {"title": "Film", "sub_title": "…", "start": "…", "end": "…"}}
]}
```

Channel overrides are applied (name, number, group, logo, guide). The logo is
`/api/channels/logos/<logo_id>/cache/`, the stream `/proxy/ts/stream/<uuid>`.

### `GET /api/tele/v1/slots/`

```json
{"api": 1, "total": 4, "used": 3, "free": 1, "limited": true, "unlimited": false,
 "accounts": [{"id": 1, "name": "Provider", "total": 4, "used": 3, "free": 1, "unlimited": false}]}
```

Only the active profiles of the active accounts count; a profile with no limit makes its account `unlimited`
and is left out of the total.

### `GET /api/tele/v1/sessions/`

```json
{"api": 1, "sessions": [
  {"id": "live:<channel>:<client>", "kind": "live", "channel": "<channel>", "title": "TF1",
   "connected_at": 1762720000.0, "mine": true, "this_device": true},
  {"id": "vod:<session>", "kind": "vod", "title": "Film", "connected_at": 1762719000.0, "mine": true,
   "this_device": false}
]}
```

`this_device`: same address and same `User-Agent` as the request (best effort: two TVs behind the same address
with the same player look the same). Admins also get `ip` and `user_agent`, and with `?all=1` the sessions of
every user with their `user`.

### `POST /api/tele/v1/sessions/release/`

```json
{"ids": ["live:<channel>:<client>"], "this_device": true, "keep": ["live:<channel>:<other client>"]}
```

Closes the sessions listed in `ids` (50 at most) and, with `this_device`, every session of this TV except
those in `keep`. Answers `{"api": 1, "released": [...]}`, the ids actually closed. Sessions of other users
(for a standard user) and unknown ids are skipped silently. The **Let players close their own sessions**
setting turns it off (403 `release_disabled`).

## Development

From this folder (`dispatcharr-plugin/`), with Python 3.12 or newer:

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m unittest discover -s tests -t .
scripts/build_zip.sh
```

`tele_api/core.py` holds the pure logic (no Django); `data.py` reads the Dispatcharr models and Redis;
`views.py` holds the endpoints. The tests patch the Dispatcharr data layer.
