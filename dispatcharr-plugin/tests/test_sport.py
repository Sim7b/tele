"""Calendars and standings of the followed competitions (sport.py), from answers shaped like ESPN and TheSportsDB ones."""

import json
import unittest
from datetime import datetime, timezone
from unittest import mock

from tele_api import sport, vault

NOW = datetime(2026, 11, 22, 12, 0, tzinfo=timezone.utc).timestamp()


def espn_team(team_id, name, short, home, score, form="WWDLW"):
    return {"homeAway": "home" if home else "away", "score": score, "form": form,
            "team": {"id": team_id, "displayName": name, "abbreviation": short, "logo": f"https://a.espncdn.com/{team_id}.png"}}


SCOREBOARD = {
    "leagues": [{"abbreviation": "Ligue 1", "name": "French Ligue 1", "logos": [{"href": "https://a.espncdn.com/l1.png"}]}],
    "events": [
        {"id": "1", "date": "2026-11-21T20:00Z", "name": "Marseille at PSG", "status": {"type": {"state": "post"}},
         "competitions": [{"venue": {"fullName": "Parc des Princes"},
                           "competitors": [espn_team("160", "Paris Saint-Germain", "PSG", True, "2"), espn_team("176", "Marseille", "OM", False, "1")]}]},
        {"id": "2", "date": "2026-11-29T19:45Z", "name": "Monaco at Lyon", "status": {"type": {"state": "pre"}},
         "competitions": [{"competitors": [espn_team("167", "Lyon", "OL", True, "0"), espn_team("174", "AS Monaco", "ASM", False, "0")]}]},
        # Out of the window: left out
        {"id": "3", "date": "2027-03-01T19:45Z", "name": "Far away", "status": {"type": {"state": "pre"}},
         "competitions": [{"competitors": [espn_team("1", "A", "A", True, "0"), espn_team("2", "B", "B", False, "0")]}]},
        "broken", {"id": "4", "date": "not a date"},
    ],
}

STANDINGS = {"children": [{"name": "French Ligue 1 2026-27", "standings": {"entries": [
    {"team": {"displayName": "Marseille", "abbreviation": "OM", "logos": [{"href": "https://a.espncdn.com/176.png"}]},
     "stats": [{"name": "rank", "displayValue": "2"}, {"name": "points", "displayValue": "24"}, {"name": "gamesPlayed", "displayValue": "11"},
               {"name": "wins", "displayValue": "7"}, {"name": "ties", "displayValue": "3"}, {"name": "losses", "displayValue": "1"}]},
    {"team": {"displayName": "Paris Saint-Germain", "abbreviation": "PSG", "logos": [{"href": "https://a.espncdn.com/160.png"}]},
     "stats": [{"name": "rank", "displayValue": "1"}, {"name": "points", "displayValue": "26"}, {"name": "gamesPlayed", "displayValue": "11"},
               {"name": "wins", "displayValue": "8"}, {"name": "ties", "displayValue": "2"}, {"name": "losses", "displayValue": "1"},
               {"name": "pointDifferential", "displayValue": "+13"}]},
    {"team": {"displayName": "", "abbreviation": "X"}, "stats": []},
]}}]}

F1 = {
    "leagues": [{"abbreviation": "F1", "logos": [{"href": "https://a.espncdn.com/f1.png"}],
                 "calendar": [{"label": "Abu Dhabi Grand Prix", "startDate": "2026-12-04T09:30Z", "endDate": "2026-12-06T15:00Z"},
                              {"label": "Las Vegas Grand Prix", "startDate": "2026-11-20T00:30Z", "endDate": "2026-11-22T04:00Z"}]}],
    "events": [{"id": "9", "date": "2026-11-20T00:30Z", "name": "Las Vegas Grand Prix", "status": {"type": {"state": "post"}},
                "competitions": [
                    {"date": "2026-11-21T04:00Z", "type": {"abbreviation": "Qual"}, "status": {"type": {"state": "post"}}, "competitors": []},
                    {"date": "2026-11-22T04:00Z", "type": {"abbreviation": "Race"}, "status": {"type": {"state": "post"}}, "competitors": []},
                ]}],
}


class FakeRedis:
    def __init__(self):
        self.values = {}
        self.ttl = {}

    def get(self, key):
        return self.values.get(key)

    def setex(self, key, ttl, value):
        self.values[key] = value
        self.ttl[key] = ttl


def answers(mapping):
    def get(url):
        for part, answer in mapping.items():
            if part in url:
                return answer
        return None
    return get


class SportTest(unittest.TestCase):
    def test_a_league_gives_its_calendar_scores_and_standings(self):
        with mock.patch.object(sport, "_get", side_effect=answers({"/scoreboard": SCOREBOARD, "/standings": STANDINGS})):
            competition = sport.competition(None, "football/france/ligue1", NOW)
        self.assertEqual("Ligue 1", competition["name"])
        self.assertEqual("https://a.espncdn.com/l1.png", competition["logo"])
        events = competition["events"]
        self.assertEqual(["1", "2"], [e["id"] for e in events])
        played, coming = events
        self.assertEqual(("PSG", "2", "OM", "1"), (played["home"]["short"], played["home"]["score"], played["away"]["short"], played["away"]["score"]))
        self.assertEqual("Parc des Princes", played["venue"])
        # Not played yet: no score
        self.assertIsNone(coming["home"]["score"])
        self.assertEqual("pre", coming["state"])
        table = competition["standings"][0]
        self.assertEqual(["Paris Saint-Germain", "Marseille"], [row["name"] for row in table["rows"]])
        self.assertEqual(("26", "8-2-1", "+13", "11"), (table["rows"][0]["points"], table["rows"][0]["record"], table["rows"][0]["diff"], table["rows"][0]["played"]))

    def test_a_race_weekend_lists_its_sessions_and_the_next_races(self):
        with mock.patch.object(sport, "_get", side_effect=answers({"/scoreboard": F1, "/standings": None})):
            competition = sport.competition(None, "motorsport/f1", NOW)
        names = [(e.get("session"), e["name"]) for e in competition["events"]]
        self.assertIn(("Qual", "Las Vegas Grand Prix"), names)
        self.assertIn(("Race", "Las Vegas Grand Prix"), names)
        # The next weekend from the calendar, the current one not twice
        self.assertIn((None, "Abu Dhabi Grand Prix"), names)
        self.assertFalse(any(e["name"] == "Las Vegas Grand Prix" and not e.get("session") for e in competition["events"]))
        self.assertEqual([], competition["standings"])

    def test_american_leagues_rank_by_wins(self):
        table = {"children": [{"name": "East", "standings": {"entries": [
            {"team": {"displayName": "Bucks"}, "stats": [{"name": "playoffSeed", "displayValue": "2"}, {"name": "points", "displayValue": "-7.5"},
                                                      {"name": "winPercent", "displayValue": ".500"}, {"name": "wins", "displayValue": "5"}, {"name": "losses", "displayValue": "5"}]},
            {"team": {"displayName": "76ers"}, "stats": [{"name": "playoffSeed", "displayValue": "1"}, {"name": "winPercent", "displayValue": ".824"},
                                                      {"name": "wins", "displayValue": "14"}, {"name": "losses", "displayValue": "3"}]},
        ]}}]}
        rows = sport._espn_standings(table, "basketball/nba")[0]["rows"]
        self.assertEqual([("76ers", ".824", "14-3"), ("Bucks", ".500", "5-5")], [(r["name"], r["points"], r["record"]) for r in rows])

    def test_thesportsdb_fills_the_competitions_espn_lacks_with_the_key_of_the_vault(self):
        urls = []
        league = {"leagues": [{"strLeague": "French Pro D2", "strLeagueAlternate": "Pro D2", "strBadge": "https://r2.thesportsdb.com/b.png",
                               "strCurrentSeason": "2026-2027"}]}
        next_events = {"events": [{"idEvent": "5", "strTimestamp": "2026-11-26T20:00:00", "strEvent": "CA Brive vs Biarritz",
                                   "strHomeTeam": "CA Brive", "strAwayTeam": "Biarritz", "strHomeTeamBadge": "https://r2.thesportsdb.com/h.png"}]}
        past_events = {"events": [{"idEvent": "4", "strTimestamp": "2026-11-20T20:00:00", "strEvent": "Oyonnax vs CA Brive",
                                   "strHomeTeam": "Oyonnax", "strAwayTeam": "CA Brive", "intHomeScore": "32", "intAwayScore": "10", "strStatus": "FT"}]}
        table = {"table": [{"intRank": "1", "strTeam": "Oyonnax", "intPlayed": "11", "intPoints": "40", "intWin": "9", "intDraw": "0", "intLoss": "2"}]}

        def get(url):
            urls.append(url)
            return answers({"lookupleague": league, "eventsnext": next_events, "eventspast": past_events, "lookuptable": table})(url)

        with mock.patch.object(sport, "_get", side_effect=get), mock.patch.object(vault, "sportsdb_key", return_value="secret1"):
            competition = sport.competition(None, "rugby/prod2", NOW)
        self.assertTrue(all("/secret1/" in url for url in urls))
        self.assertEqual("Pro D2", competition["name"])
        self.assertEqual([("4", "post", "32"), ("5", "pre", None)], [(e["id"], e["state"], e["home"]["score"]) for e in competition["events"]])
        self.assertEqual(("Oyonnax", "40", "9-0-2"), tuple(competition["standings"][0]["rows"][0][k] for k in ("name", "points", "record")))

    def test_answers_are_kept_and_a_failure_is_kept_shorter(self):
        redis = FakeRedis()
        with mock.patch.object(sport, "_get", side_effect=answers({"/scoreboard": SCOREBOARD, "/standings": STANDINGS})) as get:
            sport.competition(redis, "football/france/ligue1", NOW)
            calls = get.call_count
            sport.competition(redis, "football/france/ligue1", NOW)
            self.assertEqual(calls, get.call_count)
        self.assertEqual(sport.KEEP, redis.ttl["tele_api:sport:football/france/ligue1"])
        with mock.patch.object(sport, "_get", return_value=None):
            self.assertIsNone(sport.competition(redis, "rugby/top14", NOW))
        self.assertEqual(sport.KEEP_FAILED, redis.ttl["tele_api:sport:rugby/top14"])
        self.assertEqual({}, json.loads(redis.values["tele_api:sport:rugby/top14"]))

    def test_only_known_competitions_are_read_and_unsafe_urls_are_dropped(self):
        with mock.patch.object(sport, "_get", side_effect=answers({"/scoreboard": SCOREBOARD, "/standings": STANDINGS})) as get:
            board = sport.board(None, ["football/france/ligue1", "../../etc", "unknown/league", "football/france/ligue1"], NOW)
        self.assertEqual(["football/france/ligue1"], [c["topic"] for c in board])
        self.assertTrue(all(call.args[0].startswith(("https://site.api.espn.com/", "https://www.thesportsdb.com/")) for call in get.call_args_list))
        self.assertIsNone(sport._url("javascript:alert(1)"))
        self.assertIsNone(sport._url("http://plain.example/logo.png"))


if __name__ == "__main__":
    unittest.main()
