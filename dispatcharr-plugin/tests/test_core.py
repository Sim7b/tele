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


if __name__ == "__main__":
    unittest.main()
