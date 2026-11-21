"""Provider connection of a paused VOD kept for its resume."""

import unittest
from unittest import mock

from tele_api import core, holds


class FakeRedis:
    def __init__(self):
        self.hashes = {}
        self.values = {}

    def hget(self, key, field):
        return self.hashes.get(key, {}).get(field)

    def hset(self, key, field, value):
        self.hashes.setdefault(key, {})[field] = value

    def hsetnx(self, key, field, value):
        if field in self.hashes.get(key, {}):
            return False
        self.hset(key, field, value)
        return True

    def hdel(self, key, field):
        return 1 if self.hashes.get(key, {}).pop(field, None) is not None else 0

    def hgetall(self, key):
        return dict(self.hashes.get(key, {}))

    def incr(self, key):
        self.values[key] = int(self.values.get(key, 0)) + 1
        return self.values[key]

    def decr(self, key):
        self.values[key] = int(self.values.get(key, 0)) - 1
        return self.values[key]

    def set(self, key, value):
        self.values[key] = value


DEVICE = "1f2cdea2a0bf"
UA = f"TV-AndroidTV/1.0 (Android TV; Tele-{DEVICE})"


def session(ua=UA, user="7", profile=17, at=10.0):
    return core.Session("vod", "vod_1", None, "Engrenages", user, "10.0.0.2", ua, at, profile)


class HoldTest(unittest.TestCase):
    def setUp(self):
        self.redis = FakeRedis()
        patches = [
            mock.patch("tele_api.data.vod_sessions", return_value=[session(), session(ua="Other", profile=18)]),
            mock.patch("tele_api.data._profile_key", side_effect=lambda pid: f"profile_connections:{pid}"),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def count(self):
        return int(self.redis.values.get("profile_connections:17", 0))

    def test_the_connection_of_the_session_of_this_tv_is_held_once(self):
        self.redis.values["profile_connections:17"] = 1  # the paused stream, still open
        self.assertTrue(holds.hold(self.redis, DEVICE, 7, 30)["held"])
        self.assertTrue(holds.hold(self.redis, DEVICE, 7, 30)["held"])  # held longer, not twice
        self.assertEqual(2, self.count())
        self.redis.values["profile_connections:17"] = 1  # Dispatcharr closed the paused stream: still taken
        self.assertTrue(holds.release(self.redis, DEVICE))
        self.assertFalse(holds.release(self.redis, DEVICE))
        self.assertEqual(0, self.count())

    def test_no_session_of_this_tv_or_user_holds_nothing(self):
        self.assertFalse(holds.hold(self.redis, "aaaaaaaaaaaa", 7, 30)["held"])
        self.assertFalse(holds.hold(self.redis, DEVICE, 8, 30)["held"])
        self.assertEqual(0, self.count())

    def test_holds_past_their_time_are_given_back(self):
        with mock.patch("time.time", return_value=1000.0):
            holds.hold(self.redis, DEVICE, 7, 1)
        self.assertEqual(0, holds.expire(self.redis, now=1059.0))
        self.assertEqual(1, self.count())
        self.assertEqual(1, holds.expire(self.redis, now=1061.0))
        self.assertEqual(0, self.count())
        self.redis.hset(holds.HOLDS_KEY, "broken", "{not json")
        self.assertEqual(1, holds.expire(self.redis, now=1062.0))

    def test_the_counter_never_goes_below_zero(self):
        holds.hold(self.redis, DEVICE, 7, 30)
        self.redis.values["profile_connections:17"] = 0  # Dispatcharr counted again meanwhile
        holds.release(self.redis, DEVICE)
        self.assertEqual(0, self.count())
