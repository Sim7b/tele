"""Calendars, scores and standings of the competitions the TVs follow, for their Sport section.

The TVs send the ids of the competitions of their sport catalog ("football/france/ligue1"); each one known here is
read from a public source, by this server for every TV, and kept a while in Redis:

- ESPN (site.api.espn.com, no key): most football leagues and cups, rugby, F1, the American sports;
- TheSportsDB (www.thesportsdb.com): the competitions ESPN does not have (Pro D2, MotoGP, French basketball and
  handball), with its free key, or the key of the admin in the vault (more events and standings).

Answers are trimmed to what the TVs show (names, logos, times, scores, standings) and treated as untrusted: sizes
are bounded, unknown shapes give empty lists, never an error.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from . import core, vault

# Under "apps": a logger Dispatcharr sends to its console and log browser
logger = logging.getLogger("apps.tele_api.sport")

ESPN = "https://site.api.espn.com/apis"
SPORTSDB = "https://www.thesportsdb.com/api/v1/json"
SPORTSDB_FREE_KEY = "123"

# Catalog id of the TVs -> where to read it
COMPETITIONS: Dict[str, tuple] = {
    "football/france/ligue1": ("espn", "soccer/fra.1"),
    "football/france/ligue2": ("espn", "soccer/fra.2"),
    "football/france/cdf": ("espn", "soccer/fra.coupe_de_france"),
    "football/france/d1": ("espn", "soccer/fra.w.1"),
    "football/england/pl": ("espn", "soccer/eng.1"),
    "football/england/facup": ("espn", "soccer/eng.fa"),
    "football/spain/liga": ("espn", "soccer/esp.1"),
    "football/italy/seriea": ("espn", "soccer/ita.1"),
    "football/germany/bundesliga": ("espn", "soccer/ger.1"),
    "football/europe/ucl": ("espn", "soccer/uefa.champions"),
    "football/europe/uel": ("espn", "soccer/uefa.europa"),
    "football/europe/uecl": ("espn", "soccer/uefa.europa.conf"),
    "football/international/worldcup": ("espn", "soccer/fifa.world"),
    "football/international/euro": ("espn", "soccer/uefa.euro"),
    "football/international/nations": ("espn", "soccer/uefa.nations"),
    "football/international/can": ("espn", "soccer/caf.nations"),
    "rugby/top14": ("espn", "rugby/270559"),
    "rugby/prod2": ("sportsdb", "5172"),
    "rugby/sixnations": ("espn", "rugby/180659"),
    "rugby/championscup": ("espn", "rugby/271937"),
    "rugby/worldcup": ("espn", "rugby/164205"),
    "motorsport/f1": ("espn", "racing/f1"),
    "motorsport/motogp": ("sportsdb", "4407"),
    "basketball/nba": ("espn", "basketball/nba"),
    "basketball/wnba": ("espn", "basketball/wnba"),
    "basketball/betclic": ("sportsdb", "4423"),
    "handball/starligue": ("sportsdb", "4536"),
    "usports/nfl": ("espn", "football/nfl"),
    "usports/nhl": ("espn", "hockey/nhl"),
    "usports/mlb": ("espn", "baseball/mlb"),
    "combat/ufc": ("espn", "mma/ufc"),
    "golf/pga": ("espn", "golf/pga"),
}

MAX_TOPICS = 8
MAX_EVENTS = 40
MAX_ROWS = 60
MAX_ANSWER = 4_000_000
KEEP = 15 * 60
KEEP_LIVE = 2 * 60
KEEP_FAILED = 5 * 60
# Results kept for the calendar: the last days, and what comes in the next weeks
PAST_DAYS = 4
AHEAD_DAYS = 21
CACHE_KEY = "tele_api:sport:{}"


def known(topic: str) -> bool:
    return topic in COMPETITIONS


def _get(url: str) -> Optional[Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": f"Tele API v{core.PLUGIN_VERSION}"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310 (fixed https hosts)
            return json.loads(response.read(MAX_ANSWER))
    except Exception as error:
        # The address holds no secret but the TheSportsDB key: only the host is logged
        logger.info("Télé API: sport source %s not read (%s)", urllib.parse.urlsplit(url).netloc, type(error).__name__)
        return None


def _text(value: Any, limit: int = 80) -> Optional[str]:
    text = core.to_text(value) if value is not None else None
    text = text.strip() if isinstance(text, str) else None
    return text[:limit] if text else None


def _url(value: Any) -> Optional[str]:
    text = _text(value, 400)
    return text if text and text.startswith("https://") else None


def _number(value: Any) -> Optional[str]:
    text = _text(value, 12)
    return text if text and text not in ("-", " ") else None


def _millis(value: Any) -> Optional[int]:
    text = _text(value, 40)
    if not text:
        return None
    try:
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        moment = datetime.fromisoformat(text)
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        return int(moment.timestamp() * 1000)
    except ValueError:
        return None


def _logo(item: Dict[str, Any]) -> Optional[str]:
    if not isinstance(item, dict):
        return None
    for logo in item.get("logos") or []:
        if isinstance(logo, dict) and _url(logo.get("href")):
            return _url(logo.get("href"))
    return _url(item.get("logo")) or _url((item.get("flag") or {}).get("href") if isinstance(item.get("flag"), dict) else None)


# ---------------------------------------------------------------------------------------------------- ESPN


def _espn_side(competitor: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not isinstance(competitor, dict):
        return None
    team = competitor.get("team") or competitor.get("athlete") or {}
    if not isinstance(team, dict):
        return None
    name = _text(team.get("displayName") or team.get("name"))
    if not name:
        return None
    return {
        "name": name,
        "short": _text(team.get("abbreviation") or team.get("shortDisplayName"), 12),
        "logo": _logo(team),
        "score": _number(competitor.get("score")),
        "form": _text(competitor.get("form"), 10),
        "home": competitor.get("homeAway") == "home",
    }


def _espn_events(answer: Any) -> List[Dict[str, Any]]:
    events = []
    for event in (answer or {}).get("events") or [] if isinstance(answer, dict) else []:
        if not isinstance(event, dict):
            continue
        competitions = [c for c in event.get("competitions") or [] if isinstance(c, dict)]
        state = (((event.get("status") or {}).get("type") or {}).get("state")) if isinstance(event.get("status"), dict) else None
        sides = [s for s in (_espn_side(c) for c in (competitions[0].get("competitors") or [] if competitions else [])) if s]
        # A race weekend: one entry per session (practice, qualifying, race)
        if len(competitions) > 1 and len(sides) != 2:
            for session in competitions:
                start = _millis(session.get("date"))
                kind = _text(((session.get("type") or {}).get("abbreviation")) if isinstance(session.get("type"), dict) else None, 12)
                session_state = (((session.get("status") or {}).get("type") or {}).get("state")) if isinstance(session.get("status"), dict) else state
                if start:
                    events.append({"id": f"{_text(event.get('id'), 20)}-{kind}", "start": start, "state": _text(session_state, 8) or "pre",
                                   "name": _text(event.get("name"), 120), "session": kind})
            continue
        start = _millis(event.get("date"))
        if not start:
            continue
        if state == "pre":
            # Not played yet: no score (ESPN writes 0)
            for side in sides:
                side["score"] = None
        home = next((s for s in sides if s["home"]), sides[0] if sides else None)
        away = next((s for s in sides if s is not home), None)
        venue = competitions[0].get("venue") if competitions else None
        events.append({
            "id": _text(event.get("id"), 20),
            "start": start,
            "state": _text(state, 8) or "pre",
            "name": _text(event.get("name"), 120),
            "home": home,
            "away": away,
            "venue": _text(venue.get("fullName")) if isinstance(venue, dict) else None,
        })
    return events


def _espn_calendar(answer: Any) -> List[Dict[str, Any]]:
    """The races of the season (F1): the weekends of the league calendar, past ones included."""
    leagues = (answer or {}).get("leagues") if isinstance(answer, dict) else None
    league = leagues[0] if isinstance(leagues, list) and leagues and isinstance(leagues[0], dict) else {}
    found = []
    for entry in league.get("calendar") or []:
        if not isinstance(entry, dict):
            continue
        start = _millis(entry.get("startDate"))
        end = _millis(entry.get("endDate"))
        name = _text(entry.get("label"), 120)
        if start and name:
            found.append({"id": f"cal-{start}", "start": end or start, "state": "pre", "name": name})
    return found


def _stat(entry: Dict[str, Any], *names: str) -> Optional[str]:
    stats = {s.get("name"): s for s in entry.get("stats") or [] if isinstance(s, dict)}
    for name in names:
        value = _number((stats.get(name) or {}).get("displayValue"))
        if value is not None:
            return value
    return None


# American leagues rank by wins: their "points" are not a total
_BY_WINS = ("basketball/", "football/", "hockey/", "baseball/")


def _espn_standings(answer: Any, path: str = "") -> List[Dict[str, Any]]:
    if not isinstance(answer, dict):
        return []
    groups = answer.get("children") if isinstance(answer.get("children"), list) and answer.get("children") else [answer]
    tables = []
    for group in groups[:6]:
        standings = group.get("standings") if isinstance(group, dict) else None
        entries = standings.get("entries") if isinstance(standings, dict) else None
        if not isinstance(entries, list):
            continue
        rows = []
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                continue
            who = entry.get("team") or entry.get("athlete") or {}
            name = _text(who.get("displayName") or who.get("name")) if isinstance(who, dict) else None
            if not name:
                continue
            rank = core.to_int(_stat(entry, "rank", "playoffSeed")) or index + 1
            wins, draws, losses = _stat(entry, "wins"), _stat(entry, "ties"), _stat(entry, "losses", "gamesLost")
            record = "-".join(v for v in (wins, draws, losses) if v is not None) if wins is not None else None
            rows.append({
                "rank": rank,
                "name": name,
                "short": _text(who.get("abbreviation") or who.get("shortDisplayName"), 12),
                "logo": _logo(who),
                "played": _stat(entry, "gamesPlayed"),
                "points": _stat(entry, "winPercent") if path.startswith(_BY_WINS) else _stat(entry, "points", "championshipPts"),
                "record": record,
                "diff": _stat(entry, "pointDifferential", "pointsDifference", "differential"),
            })
        rows.sort(key=lambda row: row["rank"])
        if rows:
            tables.append({"name": _text(group.get("name") or standings.get("displayName")), "rows": rows[:MAX_ROWS]})
    return tables


def _espn(path: str, now: float) -> Dict[str, Any]:
    today = datetime.fromtimestamp(now, timezone.utc)
    months = sorted({(today - timedelta(days=PAST_DAYS)).strftime("%Y%m"), today.strftime("%Y%m"),
                     (today + timedelta(days=AHEAD_DAYS)).strftime("%Y%m")})
    events: List[Dict[str, Any]] = []
    name = logo = None
    first = None
    for month in months:
        answer = _get(f"{ESPN}/site/v2/sports/{path}/scoreboard?dates={month}&limit=200")
        if answer is None:
            continue
        first = first or answer
        events += _espn_events(answer)
    if first is None:
        # Some competitions only answer without dates (between rounds, a single race weekend)
        first = _get(f"{ESPN}/site/v2/sports/{path}/scoreboard")
        events += _espn_events(first)
    if first is None:
        raise LookupError("no answer")
    leagues = first.get("leagues") if isinstance(first, dict) else None
    league = leagues[0] if isinstance(leagues, list) and leagues and isinstance(leagues[0], dict) else {}
    name = _text(league.get("abbreviation") or league.get("name"))
    logo = _logo(league)
    if path.startswith("racing/"):
        weekends = [e for e in _espn_calendar(first) if not any(abs(e["start"] - x["start"]) < 4 * 86400_000 for x in events)]
        events += weekends
    standings = _espn_standings(_get(f"{ESPN}/v2/sports/{path}/standings"), path)
    return {"name": name, "logo": logo, "events": events, "standings": standings}


# ---------------------------------------------------------------------------------------------------- TheSportsDB


def _sportsdb_event(event: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not isinstance(event, dict):
        return None
    start = _millis(event.get("strTimestamp")) or _millis(f"{_text(event.get('dateEvent'), 10)}T{_text(event.get('strTime'), 8) or '00:00:00'}")
    if not start:
        return None
    status = (_text(event.get("strStatus"), 30) or "").lower()
    home_score, away_score = _number(event.get("intHomeScore")), _number(event.get("intAwayScore"))
    state = "post" if status in ("ft", "match finished", "aet", "pen") or (home_score is not None and start < time.time() * 1000 - 3 * 3600_000) else "pre"

    def side(prefix: str, score: Optional[str]) -> Optional[Dict[str, Any]]:
        name = _text(event.get(f"str{prefix}Team"))
        return {"name": name, "short": None, "logo": _url(event.get(f"str{prefix}TeamBadge")), "score": score, "form": None,
                "home": prefix == "Home"} if name else None

    home, away = side("Home", home_score), side("Away", away_score)
    return {"id": _text(event.get("idEvent"), 20), "start": start, "state": state, "name": _text(event.get("strEvent"), 120),
            "home": home, "away": away, "venue": _text(event.get("strVenue"))}


def _sportsdb(league_id: str, now: float) -> Dict[str, Any]:
    key = vault.sportsdb_key() or SPORTSDB_FREE_KEY
    base = f"{SPORTSDB}/{urllib.parse.quote(key, safe='')}"
    league = _get(f"{base}/lookupleague.php?id={league_id}")
    infos = (league or {}).get("leagues") if isinstance(league, dict) else None
    info = infos[0] if isinstance(infos, list) and infos and isinstance(infos[0], dict) else {}
    if not info:
        raise LookupError("no answer")
    events = []
    for path in ("eventspastleague.php", "eventsnextleague.php"):
        answer = _get(f"{base}/{path}?id={league_id}")
        events += [e for e in (_sportsdb_event(x) for x in ((answer or {}).get("events") or [] if isinstance(answer, dict) else [])) if e]
    season = _text(info.get("strCurrentSeason"), 20)
    rows = []
    if season:
        table = _get(f"{base}/lookuptable.php?l={league_id}&s={urllib.parse.quote(season)}")
        for index, row in enumerate((table or {}).get("table") or [] if isinstance(table, dict) else []):
            if not isinstance(row, dict) or not _text(row.get("strTeam")):
                continue
            wins, draws, losses = _number(row.get("intWin")), _number(row.get("intDraw")), _number(row.get("intLoss"))
            rows.append({
                "rank": core.to_int(row.get("intRank")) or index + 1,
                "name": _text(row.get("strTeam")),
                "short": None,
                "logo": _url(row.get("strBadge")),
                "played": _number(row.get("intPlayed")),
                "points": _number(row.get("intPoints")),
                "record": "-".join(v for v in (wins, draws, losses) if v is not None) or None,
                "diff": _number(row.get("intGoalDifference")),
            })
    rows.sort(key=lambda row: row["rank"])
    return {
        "name": _text(info.get("strLeagueAlternate") or info.get("strLeague")),
        "logo": _url(info.get("strBadge")),
        "events": events,
        "standings": [{"name": None, "rows": rows[:MAX_ROWS]}] if rows else [],
    }


# ---------------------------------------------------------------------------------------------------- board


def _trim(competition: Dict[str, Any], now: float) -> Dict[str, Any]:
    """The events of the calendar window, live first then by time, at most MAX_EVENTS."""
    low, high = (now - PAST_DAYS * 86400) * 1000, (now + AHEAD_DAYS * 86400) * 1000
    seen = set()
    events = []
    for event in sorted(competition.get("events") or [], key=lambda e: e["start"]):
        key = (event.get("id"), event["start"])
        if key in seen or not low <= event["start"] <= high:
            continue
        seen.add(key)
        events.append(event)
    past = [e for e in events if e["state"] == "post"][-12:]
    rest = [e for e in events if e["state"] != "post"][: MAX_EVENTS - len(past)]
    competition["events"] = past + rest
    return competition


def competition(redis_client, topic: str, now: Optional[float] = None) -> Optional[Dict[str, Any]]:
    """The calendar and standings of [topic] (a catalog id), from the cache or its source; None when unknown or
    its source did not answer."""
    source = COMPETITIONS.get(topic)
    if source is None:
        return None
    now = time.time() if now is None else now
    key = CACHE_KEY.format(topic)
    if redis_client is not None:
        try:
            cached = redis_client.get(key)
            if cached:
                value = json.loads(core.to_text(cached))
                return value or None
        except Exception:
            pass
    try:
        kind, ref = source
        found = _espn(ref, now) if kind == "espn" else _sportsdb(ref, now)
        value = _trim({"topic": topic, **found}, now)
        keep = KEEP_LIVE if any(e["state"] == "in" for e in value["events"]) else KEEP
    except Exception as error:
        logger.info("Télé API: %s not read (%s)", topic, type(error).__name__)
        value, keep = {}, KEEP_FAILED
    if redis_client is not None:
        try:
            redis_client.setex(key, keep, json.dumps(value, separators=(",", ":")))
        except Exception:
            pass
    return value or None


def board(redis_client, topics: List[str], now: Optional[float] = None) -> List[Dict[str, Any]]:
    """The competitions of [topics] known here, in their order, the ones that answered."""
    found = []
    for topic in [t for t in dict.fromkeys(topics) if known(t)][:MAX_TOPICS]:
        value = competition(redis_client, topic, now)
        if value:
            found.append(value)
    return found


def test_sportsdb_key(key: str) -> bool:
    """Whether TheSportsDB accepts [key] (a league looked up with it)."""
    answer = _get(f"{SPORTSDB}/{urllib.parse.quote(key.strip(), safe='')}/lookupleague.php?id=4334")
    return isinstance(answer, dict) and isinstance(answer.get("leagues"), list) and bool(answer["leagues"])
