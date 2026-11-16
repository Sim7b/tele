import unittest
from datetime import datetime, timedelta, timezone

from tele_api import core

NOW = datetime(2026, 11, 9, 20, 30, tzinfo=timezone.utc)


def program(epg_id, start_h, end_h, title):
    return {
        "epg_id": epg_id,
        "title": title,
        "sub_title": None,
        "start": NOW.replace(minute=0) + timedelta(hours=start_h),
        "end": NOW.replace(minute=0) + timedelta(hours=end_h),
    }


class ConversionTest(unittest.TestCase):
    def test_text_and_numbers_from_redis(self):
        self.assertEqual(core.to_text(b"abc"), "abc")
        self.assertIsNone(core.to_text(""))
        self.assertIsNone(core.to_text("None"))
        self.assertEqual(core.to_int(b"12"), 12)
        self.assertEqual(core.to_int("x", 3), 3)
        self.assertIsNone(core.to_float("nan"))
        self.assertIsNone(core.to_float("inf"))
        self.assertEqual(core.to_float("1.5"), 1.5)

    def test_query_int_is_clamped(self):
        self.assertEqual(core.query_int("99999", 10, 1, 100), 100)
        self.assertEqual(core.query_int("-5", 10, 1, 100), 1)
        self.assertEqual(core.query_int(None, 10, 1, 100), 10)
        self.assertEqual(core.query_int("abc", 10, 1, 100), 10)

    def test_channel_number(self):
        self.assertEqual(core.channel_number(4.0), 4)
        self.assertEqual(core.channel_number(4.5), 4.5)
        self.assertIsNone(core.channel_number(0))
        self.assertIsNone(core.channel_number(None))

    def test_override_wins_when_set(self):
        self.assertEqual(core.effective("base", "override"), "override")
        self.assertEqual(core.effective("base", None), "base")
        self.assertEqual(core.effective(True, False), False)


class PageTest(unittest.TestCase):
    def test_bounds(self):
        page = core.page_of(2500, "3", "1000")
        self.assertEqual((page.number, page.pages, page.start, page.end), (3, 3, 2000, 2500))

    def test_page_past_the_end_is_empty(self):
        page = core.page_of(10, "7", "5")
        self.assertEqual((page.number, page.start, page.end), (7, 10, 10))

    def test_empty(self):
        page = core.page_of(0, None, None)
        self.assertEqual((page.number, page.pages, page.start, page.end), (1, 1, 0, 0))

    def test_page_size_is_limited(self):
        self.assertEqual(core.page_of(10, 1, 10**9).size, core.MAX_PAGE_SIZE)


class EtagTest(unittest.TestCase):
    def test_stable_and_matching(self):
        tag = core.etag({"b": 1, "a": [1, 2]})
        self.assertEqual(tag, core.etag({"a": [1, 2], "b": 1}))
        self.assertNotEqual(tag, core.etag({"a": [2, 1], "b": 1}))
        self.assertTrue(core.etag_matches(tag, tag))
        self.assertTrue(core.etag_matches('W/' + tag + ', "other"', tag))
        self.assertFalse(core.etag_matches('"other"', tag))
        self.assertFalse(core.etag_matches(None, tag))


class ProgramsTest(unittest.TestCase):
    def test_now_and_next(self):
        result = core.now_and_next(
            [
                program(1, -2, -1, "past"),
                program(1, 0, 1, "now"),
                program(1, 1, 2, "next"),
                program(1, 2, 3, "later"),
                program(2, 1, 2, "after a gap"),
            ],
            NOW,
        )
        self.assertEqual(result[1]["now"]["title"], "now")
        self.assertEqual(result[1]["next"]["title"], "next")
        self.assertNotIn("now", result[2])
        self.assertEqual(result[2]["next"]["title"], "after a gap")
        self.assertEqual(result[1]["now"]["start"], "2026-11-09T20:00:00+00:00")

    def test_malformed_programs_are_skipped(self):
        result = core.now_and_next([{"epg_id": None}, {"epg_id": 3, "start": None, "end": None}], NOW)
        self.assertEqual(result, {})

    def test_sub_title_is_bounded(self):
        data = core.program_json({"title": "t", "sub_title": "x" * 5000, "start": NOW, "end": NOW})
        self.assertEqual(len(data["sub_title"]), 512)


class SlotsTest(unittest.TestCase):
    def test_sums_limited_active_profiles(self):
        limits = [
            core.ProfileLimit(1, 10, "Provider B", 2, True),
            core.ProfileLimit(2, 10, "Provider B", 1, True),
            core.ProfileLimit(3, 20, "provider a", 0, True),
            core.ProfileLimit(4, 30, "Off", 5, False),
        ]
        result = core.slots(limits, {1: 2, 2: 5, 4: 1})
        self.assertEqual((result["total"], result["used"], result["free"]), (3, 3, 0))
        self.assertTrue(result["unlimited"])
        self.assertTrue(result["limited"])
        self.assertEqual([a["name"] for a in result["accounts"]], ["provider a", "Provider B"])
        self.assertEqual(result["accounts"][1]["used"], 3)

    def test_negative_and_malformed_counters(self):
        result = core.slots([core.ProfileLimit(1, 1, "P", 4, True)], {1: -3})
        self.assertEqual(result["free"], 4)
        result = core.slots([core.ProfileLimit(1, 1, "P", 4, True)], {1: "x"})
        self.assertEqual(result["free"], 4)

    def test_no_limit(self):
        result = core.slots([], {})
        self.assertFalse(result["limited"])
        self.assertEqual(result["free"], 0)


def session(kind="live", client="client_1_2", channel="abc", user="7", ip="10.0.0.2", ua="Tele", at=1.0):
    return core.Session(kind, client, channel if kind == "live" else None, "TF1", user, ip, ua, at, 3)


class SessionIdTest(unittest.TestCase):
    def test_round_trip(self):
        live = session()
        self.assertEqual(core.parse_session_id(live.id), ("live", "abc", "client_1_2"))
        vod = session(kind="vod", client="9f3e-12")
        self.assertEqual(core.parse_session_id(vod.id), ("vod", None, "9f3e-12"))

    def test_refuses_malformed_ids(self):
        for value in (None, "", "live:abc", "live::x", "vod:", "other:a:b", "vod:a*b", "live:a/b:c",
                      "vod:" + "a" * 400, 12, ["live:a:b"]):
            self.assertIsNone(core.parse_session_id(value), value)


class VisibleSessionsTest(unittest.TestCase):
    def sessions(self):
        return [session(client="mine", at=2.0), session(client="other", user="8", ip="10.0.0.9", at=3.0),
                session(client="mine-elsewhere", ip="10.0.0.5", at=1.0)]

    def test_standard_user_sees_own_sessions_without_viewer_details(self):
        result = core.visible_sessions(self.sessions(), 7, admin=False, everyone=True, ip="10.0.0.2", user_agent="Tele")
        self.assertEqual([s["id"] for s in result], ["live:abc:mine", "live:abc:mine-elsewhere"])
        self.assertTrue(result[0]["this_device"])
        self.assertFalse(result[1]["this_device"])
        self.assertNotIn("ip", result[0])
        self.assertNotIn("user_agent", result[0])

    def test_admin_sees_everyone_only_when_asked(self):
        own = core.visible_sessions(self.sessions(), 7, admin=True, everyone=False, ip=None, user_agent=None)
        self.assertEqual(len(own), 2)
        every = core.visible_sessions(self.sessions(), 7, admin=True, everyone=True, ip=None, user_agent=None,
                                      usernames={"8": "bob"})
        self.assertEqual(every[0]["user"], "bob")
        self.assertFalse(every[0]["mine"])
        self.assertEqual(every[0]["ip"], "10.0.0.9")

    def test_same_device_needs_an_address(self):
        self.assertFalse(core.is_same_device(session(ip=None), None, "Tele"))
        self.assertFalse(core.is_same_device(session(), "10.0.0.2", "Other player"))

    def test_release_rights(self):
        self.assertTrue(core.may_release(session(), 7, admin=False))
        self.assertFalse(core.may_release(session(user="8"), 7, admin=False))
        self.assertTrue(core.may_release(session(user="8"), 7, admin=True))
        self.assertFalse(core.may_release(None, 7, admin=True))


class GuideTest(unittest.TestCase):
    def test_window_on_hour_boundaries(self):
        start, end = core.guide_window(3600 * 100 + 1234, "24")
        self.assertEqual((start, end), (3600 * 99.0, 3600 * 125.0))
        self.assertEqual(core.guide_window(3600 * 100 + 3000, None), (start, end))
        self.assertEqual(core.guide_window(0, "9999")[1], 3600.0 * (core.GUIDE_MAX_HOURS + 1))
        # A day of past programs for the replay, no more
        self.assertEqual(core.guide_window(3600 * 100, "24", "24")[0], 3600 * 76.0)
        self.assertEqual(core.guide_window(3600 * 100, "24", "999")[0], 3600 * 76.0)

    def test_program_and_channels(self):
        row = {"id": 5, "title": "Journal", "sub_title": None, "description": "x" * 5000, "start_time": NOW,
               "end_time": NOW + timedelta(minutes=40), "custom_properties": {"rating": " 12 ", "icon": "javascript:x"}}
        program = core.guide_program(row)
        self.assertEqual(program["id"], "5")
        self.assertEqual(len(program["description"]), core.MAX_DESCRIPTION)
        self.assertEqual(program["rating"], "12")
        self.assertNotIn("icon", program)
        self.assertIsNone(core.guide_program({"title": "", "start_time": NOW, "end_time": NOW}))
        channels = core.guide_channels([("u1", 3), ("u2", 3), ("u3", 4)], {3: [program]})
        self.assertEqual([c["uuid"] for c in channels], ["u1", "u2"])


if __name__ == "__main__":
    unittest.main()


class GroupLanguagesTest(unittest.TestCase):
    def guess(self, name):
        found = core.group_languages(name)
        return found["audio"], found["subtitles"]

    def test_country_dubbed_and_subtitled_groups(self):
        self.assertEqual(self.guess("FR | FILMS"), (["fr"], []))
        self.assertEqual(self.guess("FR | FILMS VOSTFR"), ([], ["fr"]))
        self.assertEqual(self.guess("|EN| NETFLIX"), (["en"], []))
        self.assertEqual(self.guess("TR | DUBLAJ"), (["tr"], []))
        self.assertEqual(self.guess("EN SERIES SUB FR"), ([], ["fr"]))
        self.assertEqual(self.guess("FR - MULTI"), (["fr"], []))
        self.assertEqual(self.guess("VO SERIES"), ([], []))
        self.assertEqual(self.guess("Action"), ([], []))

    def test_arabic_groups_are_english_with_arabic_subtitles(self):
        self.assertEqual(self.guess("ARABIC MOVIES"), (["en"], ["ar"]))
        self.assertEqual(self.guess("AR | TURKISH SERIES"), (["tr"], ["ar"]))

    def test_the_arabic_code_alone_is_arabic_audio(self):
        self.assertEqual(self.guess("AR | FILMS"), (["ar"], []))
        self.assertEqual(self.guess("[AR] SERIES"), (["ar"], []))
        self.assertEqual(self.guess("[AR] يعرض الآن أجنبي"), (["ar"], []))
        self.assertEqual(self.guess("[AR] VOST"), ([], ["ar"]))
        self.assertEqual(self.guess("افلام اجنبية مترجمة"), (["en"], ["ar"]))
        self.assertEqual(self.guess("افلام تركية"), (["tr"], ["ar"]))
        self.assertEqual(self.guess("افلام عربية"), (["ar"], []))

    def test_prefix_and_tags(self):
        found = core.group_languages("|FR| KIDS 4K")
        self.assertEqual(found["prefix"], "FR")
        self.assertEqual(set(found["tags"]), {"kids", "4k"})
        self.assertIn("MULTISUB", core.group_languages("MULTI-SUB 4K")["tags"])
        self.assertIn("adult", core.group_languages("FR XXX 18+")["tags"])
        self.assertIn("studio", core.group_languages("|EN| NETFLIX")["tags"])
        self.assertIsNone(core.group_prefix("HD Movies"))
        self.assertEqual(core.group_languages("x" * 10000)["audio"], [])

    def test_selection_is_validated(self):
        self.assertEqual(core.parse_selection({"mode": "all", "ids": [1]}), {"mode": "all", "ids": []})
        self.assertEqual(core.parse_selection({"mode": "selected", "ids": [3, "2", 3]}), {"mode": "selected", "ids": [2, 3]})
        for bad in ({"mode": "selected", "ids": [0]}, {"mode": "selected", "ids": [True]}, {"mode": "selected"},
                    {"mode": "x"}, [], {"mode": "selected", "ids": [1] * (core.MAX_SELECTED_GROUPS + 1)}):
            self.assertIsNone(core.parse_selection(bad))


class BackupTest(unittest.TestCase):
    def test_backup_is_checked_entry_by_entry(self):
        data = {
            "format": "tele-api-backup", "version": 1,
            "groups": {"mode": "selected", "groups": [{"id": 3, "name": "FR | FILMS", "type": "movie"}, {"name": "x", "type": "live"}]},
            "languages": [
                {"id": 4, "name": "MULTI", "type": "movie", "status": "manual", "audio": ["fr", "en"], "subtitles": []},
                {"name": "VO", "type": "series", "status": "excluded"},
                {"name": "bad", "type": "movie", "status": "manual", "audio": ["french"]},
                {"name": "empty", "type": "movie", "status": "manual", "audio": [], "subtitles": []},
            ],
            "tmdb": [{"kind": "movie", "item": 5, "name": "Dune", "tmdb": 438631, "manual": True},
                     {"kind": "movie", "item": "x", "name": "", "tmdb": 1}, {"kind": "tv", "item": 1, "tmdb": 1}],
        }
        parsed = core.parse_backup(data)
        self.assertEqual(parsed["mode"], "selected")
        self.assertEqual(parsed["groups"], [{"id": 3, "name": "FR | FILMS", "type": "movie"}])
        self.assertEqual([item["name"] for item in parsed["languages"]], ["MULTI", "VO"])
        self.assertEqual(parsed["tmdb"], [{"kind": "movie", "item": 5, "name": "Dune", "tmdb": 438631, "manual": True}])
        self.assertIsNone(core.parse_backup({"format": "other", "version": 1}))
        self.assertIsNone(core.parse_backup([]))
        # A further part: the groups are left as they are
        self.assertIsNone(core.parse_backup({"format": "tele-api-backup", "version": 1, "tmdb": []})["mode"])


class TargetedMessageTest(unittest.TestCase):
    def test_tv_then_user_then_everyone(self):
        import json

        targeted = {
            "device:0a1b2c3d4e5f": json.dumps({"text": "For this TV", "until": 200}),
            b"user:7": json.dumps({"text": "For you", "until": 200}).encode(),
            "user:8": json.dumps({"text": "Old", "until": 50}),
        }
        self.assertEqual(core.pick_message("All", targeted, "0a1b2c3d4e5f", 7, 100), "For this TV")
        self.assertEqual(core.pick_message("All", targeted, "ffffffffffff", 7, 100), "For you")
        self.assertEqual(core.pick_message("All", targeted, None, 8, 100), "All")  # expired
        self.assertIsNone(core.pick_message("  ", {}, None, None, 100))
        self.assertEqual([m["target"] for m in core.active_messages(targeted, 100)], ["0a1b2c3d4e5f", "7"])

    def test_targets_are_checked(self):
        self.assertEqual(core.message_target("device", "0a1b2c3d4e5f"), "device:0a1b2c3d4e5f")
        self.assertEqual(core.message_target("user", 12), "user:12")
        for kind, target in (("device", "x"), ("user", "abc"), ("all", "1"), ("device", "0a1b2c3d4e5f:x")):
            self.assertIsNone(core.message_target(kind, target))


class VodStatsTest(unittest.TestCase):
    def test_play_keys(self):
        uuid = "0a1b2c3d-0000-4000-8000-000000000001"
        self.assertEqual(core.vod_play_key("movie", uuid, "Dune"), f"movie:{uuid}")
        self.assertEqual(core.vod_play_key(None, None, " Dune "), "name:Dune")
        self.assertIsNone(core.vod_play_key("movie", "../x", ""))

    def test_top_and_users(self):
        top = core.top_vod([{"movie:a": "3", "name:Dune": 1}, {b"movie:a": b"2"}], {"movie:a": "Parasite"})
        self.assertEqual(top[0], {"key": "movie:a", "kind": "movie", "name": "Parasite", "plays": 5})
        self.assertEqual(top[1]["name"], "Dune")
        self.assertEqual(core.users_plays([{"7": 2}, {b"7": b"1", "8": 1}], {"7": "salon"}), [{"user": "salon", "plays": 3}, {"user": "8", "plays": 1}])

    def test_popular_leaves_adult_titles_out(self):
        plays = {"movie:a": 5, "episode:e1": 3, "episode:e2": 4, "movie:x": 9, "movie:gone": 7}
        titles = {"movie:a": ("movie", 1), "episode:e1": ("series", 2), "episode:e2": ("series", 2), "movie:x": ("movie", 3)}
        found = core.popular_titles(plays, titles, {("movie", 3)})
        self.assertEqual(found, [{"kind": "series", "id": 2, "plays": 7}, {"kind": "movie", "id": 1, "plays": 5}])
        self.assertTrue(core.is_adult_group("FR | XXX"))
        self.assertTrue(core.is_adult_group("PORN 18+"))
        self.assertFalse(core.is_adult_group("FR | FILMS"))


class SubtitlesTest(unittest.TestCase):
    def test_search_query_is_checked_sorted_and_lowercase(self):
        query = core.subtitle_search_query({"tmdb_id": "438631", "languages": "fr,EN", "api_key": "x", "year": "20x1"})
        self.assertEqual(query, "languages=fr%2Cen&tmdb_id=438631")
        self.assertEqual(core.subtitle_search_query({"query": "Dune", "year": "2021"}), "query=dune&year=2021")
        self.assertIsNone(core.subtitle_search_query({"languages": "fr"}))  # nothing to search for
        self.assertIsNone(core.subtitle_search_query(None))

    def test_results_keep_the_fields_read(self):
        data = [
            {"attributes": {"language": "fr", "release": "Dune.2021", "download_count": "12", "files": [{"file_id": 7, "file_name": "x.srt"}],
                            "url": "https://x", "uploader": {"name": "y"}}},
            {"attributes": {"language": "<b>", "files": [{"file_id": 8}]}},
            {"attributes": {"language": "en", "files": []}},
            "garbage",
        ]
        results = core.subtitle_results(data)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["attributes"]["files"], [{"file_id": 7}])
        self.assertEqual(results[0]["attributes"]["download_count"], 12)
        self.assertNotIn("url", results[0]["attributes"])


class TmdbFactsTest(unittest.TestCase):
    def test_facts_of_a_tmdb_answer(self):
        details = {
            "vote_average": 8.43, "vote_count": "1532", "popularity": 81.234, "original_language": "en",
            "production_countries": [{"iso_3166_1": "US"}, {"iso_3166_1": "<b>"}], "origin_country": ["us", "GB"],
            "networks": [{"id": 213, "name": "Netflix"}], "production_companies": [{"id": 213, "name": "Netflix"}, {"id": 5, "name": "X"}],
        }
        facts = core.tmdb_facts(details)
        self.assertEqual(facts, {"v": 8.4, "n": 1532, "p": 81.23, "l": "en", "c": ["US", "GB"], "s": [["213", "Netflix"], ["5", "X"]]})
        self.assertEqual(core.tmdb_facts({"vote_average": 99, "vote_count": -3})["v"], 0)
        self.assertIsNone(core.tmdb_facts("garbage"))


class CatalogItemTest(unittest.TestCase):
    def test_item_in_the_format_of_the_vod_api(self):
        from datetime import datetime, timezone

        values = {"id": 5, "uuid": "0a1b2c3d-0000-4000-8000-000000000001", "name": "FR - Dune (2021)", "year": 2021,
                  "logo_id": 9, "description": "x" * 900, "genre": "Science-Fiction", "tmdb_id": "", "imdb_id": "tt1160419",
                  "created_at": datetime(2026, 11, 1, tzinfo=timezone.utc)}
        item = core.catalog_item(values, "FR | FILMS", "438631")
        self.assertEqual(item["tmdb_id"], "438631")
        self.assertEqual(item["category"], "FR | FILMS")
        self.assertEqual(item["logo"], 9)
        self.assertEqual(len(item["description"]), 400)
        self.assertTrue(item["created_at"].startswith("2026-11-01T00:00:00"))
        self.assertIsNone(core.catalog_item({"id": 1, "name": "x"}, None, None)["tmdb_id"])


class ExtrasTest(unittest.TestCase):
    def test_segments_of_theintrodb(self):
        answer = {"tvdb_id": 81189, "type": "tv", "intro": [{"start_ms": 228665, "end_ms": 246498}, {"start_ms": 9, "end_ms": 3}],
                  "credits": [{"start_ms": 3432884, "end_ms": None}], "recap": [{"start_ms": None, "end_ms": None}], "preview": "x"}
        self.assertEqual(core.parse_segments(answer), {
            "intro": [{"start_ms": 228665, "end_ms": 246498}], "credits": [{"start_ms": 3432884, "end_ms": None}]})
        self.assertEqual(core.parse_segments("garbage"), {})

    def test_recommendations_then_similar(self):
        details = {"id": 1, "recommendations": {"results": [{"id": 5}, {"id": 1}, {"id": "x"}]},
                   "similar": {"results": [{"id": 5}, {"id": 7}]}}
        self.assertEqual(core.related_ids(details), ["5", "7"])
        self.assertEqual(core.related_ids(None), [])


class UpdatesTest(unittest.TestCase):
    def test_versions_and_latest_release(self):
        self.assertEqual(core.version_tuple("v0.3.35"), (0, 3, 35))
        self.assertIsNone(core.version_tuple("0.3.35-beta"))
        self.assertTrue(core.is_behind("0.3.9", "0.3.35"))
        self.assertFalse(core.is_behind("0.3.35", "0.3.35"))
        self.assertFalse(core.is_behind(None, "0.3.35"))
        answer = {"tag_name": "v0.3.35", "html_url": "https://github.com/Sim7b/tele/releases/tag/v0.3.35"}
        self.assertEqual(core.latest_release(answer), {"version": "0.3.35", "url": answer["html_url"]})
        self.assertIsNone(core.latest_release(dict(answer, prerelease=True)))
        self.assertIsNone(core.latest_release(dict(answer, html_url="https://evil.example/")))
