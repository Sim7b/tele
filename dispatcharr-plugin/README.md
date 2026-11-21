# Télé API: Dispatcharr plugin

A [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) plugin with endpoints for the
[Télé](https://github.com/Sim7b/tele) Android TV player. They work with the API key of a **standard user**,
no admin key needed:

- **Compact guide**: the programs of the user's channels only, not sent again while unchanged (304).
- **Channels in one call**: the channels of the user with their group, guide key and current / next program,
  by pages, in place of the channels, groups and EPG data lists.
- **Free provider connections**: by account and in total, read from the counters Dispatcharr checks before
  opening a stream (`/proxy/stats/` needs an admin key).
- **Playback sessions**: the sessions of the user, the ones of this TV marked, and closing them (a preview left
  open, the previous channel) so the TV never holds two provider connections.

- **VOD languages measured**: `ffprobe` reads the audio and subtitle tracks of one file per category whose
  name leaves its languages uncertain, without ever taking a connection a viewer needs (below). Télé uses the
  measure in place of what the category name tells.
- **Sync between TVs**: the favorites, movie and episode positions, my list, collections and reminders of a
  Dispatcharr user, shared by all their Télé TVs (the most recent change wins, item by item).
  Each profile of the TVs other than their main one has its own items (`?profile=`, the same name on every
  TV, 8 per user at most).
- **Start of a channel explained**: while a TV waits for a channel, it reads what Dispatcharr is doing with it
  (preparing, waiting for the provider, switching to the next stream) and tells it under the spinner.
- **Paused movies keep their connection**: a movie or an episode paused for a few seconds on a TV keeps its
  provider connection (setting *Keep the provider connection of a paused movie*, 30 minutes by default):
  Dispatcharr frees it as soon as the paused stream closes, and another viewer could take it before the resume. It
  is given back at the resume, when the TV leaves the player, or after that time.
- **Stream health**: the failures of each provider stream (errors, failovers, buffering) are counted from
  the Dispatcharr events and fade away in a few hours; Télé tries the streams that failed lately last, and
  marks them in its stream picker.
- **Streams of the channels in order**: every 30 minutes (setting *Sort the streams of the channels*, or *Sort
  now* on the Télé page), the streams of each enabled channel are put in order in Dispatcharr: the available ones
  first (not failing lately, provider account active, still in the playlist), then the best quality (the
  resolution Dispatcharr measured, else the one of the name). Dispatcharr plays the first stream: a dead one in
  first place made every start wait for its startup timeout. A channel being watched is sorted at the next run.
- **A page for admins** (`/api/tele/ui/`, the link of the plugin card): free connections, every open session
  (with a button to close it), the Télé TVs seen (install id, user, version of Télé, address, last request,
  with a button closing all their sessions), statistics of the last 7 or 30 days (most started channels,
  highest provider connections used per day, sampled every 10 minutes), and the probed categories with
  their languages and quality.

Télé uses the plugin when it is installed and falls back to the standard Dispatcharr API otherwise.

## Installation

1. Download `tele_api-<version>.zip` from the latest [Télé release](https://github.com/Sim7b/tele/releases/latest)
   (or the artifact `tele-api-plugin` of a CI run, or `scripts/build_zip.sh`).
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
- the "Hide mature content" preference of a Dispatcharr user applies: adult channels are left out (as in
  Dispatcharr), and so are the VOD categories holding adult movies in the measured languages;
- a standard user only sees and closes their own sessions, without the address and player of other TVs;
  each session id sent is read again from Redis before anything is closed;
- admins can list (`?all=1`) and close the sessions of every user.

Adding routes this way is not part of the plugin API: a Dispatcharr update could change it. Télé checks
`/api/tele/v1/info/` first and uses the standard API when it does not answer.

## In Dispatcharr

The plugin card (**Plugins**) has the settings and the actions; Dispatcharr shows nothing else for a plugin.

| Setting | Default | |
|---|---|---|
| Let players close their own sessions | on | Télé closes the preview or the channel it left open |
| Sync the TVs of a user | on | Favorites, positions, my list, collections, reminders |
| Requests per minute and user | 120 | Beyond, 429 for the rest of the minute; 0: no limit; admins not limited |
| Show the provider account names to players | off | Standard users get the counts only |
| Message for the TVs | empty | Shown on the home screen of the Télé TVs, read again every 5 minutes |
| Measure VOD languages with ffprobe | on | Every 10 minutes, in the background |
| Categories to probe | uncertain ones | Or every category |
| Free connections to keep | 1 | On the provider profile, before a probe starts |
| Pause between two probes (seconds) | 5 | |

| Action | |
|---|---|
| Status | Routes, open sessions, categories measured / failed / to retry |
| Probe VOD languages now | A run at once |
| Export the measured languages | Writes `vod-language-cases.json` in the data folder (see below) |
| Forget measured languages | Every category probed again (asks first) |

The Télé page (`/api/tele/ui/`) is served by the plugin on the address of Dispatcharr and uses the session of
its web interface: open it from a browser signed in to Dispatcharr with an **admin** account. Like the routes,
it is not part of the plugin API.

## VOD language probes

A category is **uncertain** when its name says nothing about the language ("NETFLIX", "Action"), names several
without saying which (MULTI, MULTI-SUB, VOST, SUB), the original version (VO), or is Arabic (written in Arabic,
"ARABIC", "AR |": often English subtitled in Arabic). One of its files (the latest one) is probed with
`ffprobe -show_streams`; the language tags of its tracks give the languages of the whole category.

A probe opens a real provider connection, so it never comes before a viewer:

- it only starts when the profile keeps the free connections set (1 by default), and takes its connection
  through the same reservation as playback, so the limit of the provider is never exceeded;
- the address of the file and its redirects are checked by hand before ffprobe opens it: a redirect to the
  local network (other than the provider server set in Dispatcharr) is refused, and ffprobe only gets the
  network protocols (no `file:`), so a provider answer cannot reach the services or files of the server;
- one probe at a time on the server, 25 seconds at most, a pause between two, runs of 8 minutes at most;
- when Télé finds no free connection (HTTP 503), it asks the plugin to close its own sessions: a running probe
  stops at once, gives its connection back, and probes pause for 2 minutes.

A file that cannot be probed is tried again at the next runs, 3 times at most; a measure is redone after 30
days. The quality of the probed file (height, HDR, codecs, audio channels) is kept too, shown on the Télé page
and exported, but not given to the titles of the category: unlike the language, it changes from one file to
the next (a sheet asks for its own file, below). Results are kept in `/data/tele_api/vod_languages.json`. The provider address of the file (it holds the
credentials) is never logged nor kept, only the name of the file.

### Export for Télé

**Export as Télé test cases** (Télé page) or the **Export** action writes the measures as test cases: the
category name, the file name and the languages read, nothing else. Dropped in
`app/src/test/resources/vod-language-cases/` of Télé, they are compared with what Télé estimates from the
names, so that the estimate rules come from real playlists (see the README there).

## API (v1)

All responses are JSON with `"api": 1`. Errors: 401 / 403 (key, level, plugin disabled), 400 (malformed
request), 503 (`redis_unavailable`).

### `GET /api/tele/v1/info/`

```json
{"plugin": "tele_api", "version": "0.2.0", "api": 1, "features": ["channels", "slots", "sessions", "vod_languages"],
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

### `GET /api/tele/v1/guide/?hours=24&past=1`

The programs of the caller's channels from `past` hours ago (1-24: Télé asks for a day when channels have
catch-up, for the replay) to `hours` ahead (1-48), in the format of the
Dispatcharr grid: `{"results": [{"uuid": "<channel>", "programs": [{"id", "title", "sub_title",
"description", "start", "end", "rating", "icon"}]}]}`. Only the channels the user may watch, descriptions cut at
1000 characters. The window moves by whole hours and the answer has an `ETag`: `If-None-Match` answers 304
while the guide did not change, and Télé keeps the guide it has.

### `GET|POST /api/tele/v1/sync/`

GET: every synced item of the caller. POST `{"changes": {"favorites": {"<channel>": {"v": [0], "t": <ms>}},
"my_list": {"<title>": {"d": true, "t": <ms>}}}}`: each change is kept when more recent than the stored one
(a time ahead of the server clock is taken as now), and the answer gives every item. Collections:
`favorites`, `vod_progress`, `my_list`, `collections`, `reminders`; at most 5000 items each, values of 4 KB;
deletions are forgotten after 60 days. One file per user in `/data/tele_api/sync/`. The **Sync the TVs of a
user** setting turns it off (403).

### `GET /api/tele/v1/slots/`

```json
{"api": 1, "total": 4, "used": 3, "free": 1, "limited": true, "unlimited": false,
 "accounts": [{"id": 1, "name": "Provider", "total": 4, "used": 3, "free": 1, "unlimited": false}]}
```

Account names are `null` for standard users unless the plugin shows them (setting). Only the active
profiles of the active accounts count; a profile with no limit makes its account `unlimited`
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

`this_device`: same address and same `User-Agent` as the request. Télé adds a random install id to its
User-Agent for Dispatcharr (`TV-AndroidTV/1.0 (Android TV; Tele-1a2b3c4d5e6f)`), so two TVs behind the same
address never look the same; with a custom User-Agent, Télé does not close sessions on its own. Admins also get `ip` and `user_agent`, and with `?all=1` the sessions of
every user with their `user`.

### `GET /api/tele/v1/streams/health/?ids=7,8`

```json
{"api": 1, "streams": {"7": {"score": 1.4, "unhealthy": true, "last_failure": 1762720000.0},
                       "8": {"score": 0.0, "unhealthy": false, "last_failure": null}}}
```

At most 100 ids. The score is the number of failures weighted by age (half every 6 hours; a buffering stall
counts 0.3); a stream is unhealthy from 0.8. The counts come from the **Stream health** action, which
Dispatcharr runs on its `channel_error`, `channel_failover`, `channel_buffering` and `channel_start` events.

### `GET /api/tele/v1/vod/languages/`

```json
{"api": 1, "categories": [
  {"id": 12, "name": "FR | FILMS MULTI-SUB", "type": "movie", "audio": ["en"], "subtitles": ["en", "fr"],
   "probed_at": 1762720000.0}
]}
```

Measured categories only.

### `GET /api/tele/v1/vod/media/?kind=movie&uuid=<uuid>`

The technical description of one movie or episode, measured with ffprobe, in the format of the Xtream
`get_vod_info` "info" (`video`, `audio`, `subtitles`, `bitrate`, `duration_secs`), so Télé reads it like the
provider ones. Unknown yet: `{"status": "queued"}`, the title is probed before the categories at the next
run (same rules: a free connection, after the viewers); `{"status": "failed"}` after 3 attempts. Télé asks for
it when a movie sheet has no description from the provider, and builds the sheet again a few times.

Admin endpoints of the Télé page: `GET admin/probes/` (every result and the counts),
`POST admin/probes/run/`, `POST admin/probes/reset/` (`{"id": <category>}` or `{}` for all),
`GET admin/probes/export/` (the test case file), `GET admin/devices/` (the TVs seen in the last 90 days),
`POST admin/devices/release/` (`{"device": <install id>}`: closes every session of that TV),
`GET admin/stats/?days=7` (top channels, connection peaks per account and day; kept 30 days in Redis).

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
`views.py` holds the endpoints, `probe.py` and `tasks.py` the VOD language probes, `ui.html` the Télé page.
The tests patch the Dispatcharr data layer; the ffprobe test runs a fake `ffprobe` script.
