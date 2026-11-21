"""What the TVs of a user play, resumed on another one, and sent from one to another."""

import json
import unittest
from unittest import mock

from tele_api import core, handoff


class FakeRedis:
    def __init__(self):
        self.hashes = {}
        self.values = {}

    def hset(self, key, field, value):
        self.hashes.setdefault(key, {})[field] = value

    def hget(self, key, field):
        return self.hashes.get(key, {}).get(field)

    def hdel(self, key, field):
        return 1 if self.hashes.get(key, {}).pop(field, None) is not None else 0

    def hgetall(self, key):
        return dict(self.hashes.get(key, {}))

    def expire(self, key, ttl):
        pass

    def setex(self, key, ttl, value):
        self.values[key] = value

    def get(self, key):
        return self.values.get(key)

    def delete(self, key):
        self.values.pop(key, None)


ITEM = {"kind": "vod", "key": "s:tmdb:1#S1E3", "title": "Engrenages", "position_ms": 60000, "duration_ms": 3000000}


class HandoffTest(unittest.TestCase):
    def setUp(self):
        self.redis = FakeRedis()
        seen = {
            "aaaaaaaaaaaa": json.dumps({"user": "7", "seen": 1000.0}),
            "bbbbbbbbbbbb": json.dumps({"user": "7", "seen": 1000.0}),
            "cccccccccccc": json.dumps({"user": "8", "seen": 1000.0}),
        }
        patch = mock.patch("tele_api.devices.all_raw", return_value=seen)
        patch.start()
        self.addCleanup(patch.stop)

    def test_what_the_other_tvs_play_now(self):
        with mock.patch("time.time", return_value=1000.0):
            handoff.report(self.redis, 7, "aaaaaaaaaaaa", "Salon", ITEM)
            handoff.report(self.redis, 7, "bbbbbbbbbbbb", "Chambre", {**ITEM, "key": "x"})
            seen_from_b = handoff.others(self.redis, 7, "bbbbbbbbbbbb")
        self.assertEqual(["aaaaaaaaaaaa"], [p["device"] for p in seen_from_b["playing"]])
        self.assertEqual("Salon", seen_from_b["playing"][0]["name"])
        self.assertEqual([{"device": "aaaaaaaaaaaa", "name": "Salon"}], seen_from_b["tvs"])
        with mock.patch("time.time", return_value=1000.0 + handoff.NOW_FRESH + 1):
            self.assertEqual([], handoff.others(self.redis, 7, "bbbbbbbbbbbb")["playing"])
        handoff.report(self.redis, 7, "aaaaaaaaaaaa", None, None)
        self.assertEqual({}, self.redis.hgetall("tele_api:handoff:now:7").get("aaaaaaaaaaaa") or {})

    def test_sent_to_a_tv_of_the_same_user_read_once(self):
        handoff.report(self.redis, 7, "aaaaaaaaaaaa", "Salon", None)
        self.assertTrue(handoff.send(self.redis, 7, "aaaaaaaaaaaa", "bbbbbbbbbbbb", ITEM))
        self.assertFalse(handoff.send(self.redis, 7, "aaaaaaaaaaaa", "cccccccccccc", ITEM))  # another user
        self.assertFalse(handoff.send(self.redis, 7, "aaaaaaaaaaaa", "aaaaaaaaaaaa", ITEM))
        got = handoff.incoming(self.redis, "bbbbbbbbbbbb")
        self.assertEqual(("s:tmdb:1#S1E3", "Salon"), (got["key"], got["name"]))
        self.assertIsNone(handoff.incoming(self.redis, "bbbbbbbbbbbb"))

    def test_items_and_names_are_checked(self):
        self.assertEqual(ITEM, core.handoff_item(ITEM))
        self.assertEqual(0, core.handoff_item({**ITEM, "position_ms": -5})["position_ms"])
        for bad in (None, {}, {**ITEM, "kind": "radio"}, {**ITEM, "key": ""}, {**ITEM, "key": "a\nb"}, {**ITEM, "key": "x" * 301}):
            self.assertIsNone(core.handoff_item(bad))
        self.assertEqual("Salon", core.device_name("  Salon "))
        self.assertIsNone(core.device_name("\x00\x01"))
        self.assertEqual(60, len(core.device_name("a" * 99)))
