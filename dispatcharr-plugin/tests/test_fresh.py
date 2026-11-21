"""What is new in the VOD: added titles, latest episodes of followed series."""

import unittest
from unittest import mock

from tele_api import core, fresh


class FakeRedis:
    def __init__(self):
        self.scores = {}

    def zadd(self, key, mapping):
        self.scores.setdefault(key, {}).update(mapping)

    def zremrangebyscore(self, key, low, high):
        values = self.scores.get(key, {})
        for member in [m for m, s in values.items() if low <= s <= high]:
            del values[member]

    def zrevrangebyscore(self, key, high, low, start=0, num=None):
        values = sorted(((s, m) for m, s in self.scores.get(key, {}).items() if s >= low), reverse=True)
        return [m.encode() for _, m in values][start:start + num]


class FreshTest(unittest.TestCase):
    def test_newest_first_each_title_once(self):
        items = [{"kind": "movie", "id": 1, "at": 5.0}, {"kind": "series", "id": 2, "at": 9.0}, {"kind": "movie", "id": 1, "at": 4.0},
                 {"kind": "movie", "id": 3, "at": "bad"}]
        self.assertEqual([("series", 2), ("movie", 1), ("movie", 3)], [(i["kind"], i["id"]) for i in core.newest(items, 10)])
        self.assertEqual(1, len(core.newest(items, 1)))

    def test_followed_series_are_kept_a_few_days(self):
        redis = FakeRedis()
        with mock.patch("time.time", return_value=1000.0):
            fresh.follow(redis, [7, 8])
        with mock.patch("time.time", return_value=2000.0):
            fresh.follow(redis, [9])
            self.assertEqual([9, 8, 7], fresh.followed(redis))
        with mock.patch("time.time", return_value=1000.0 + fresh.FOLLOWED_FOR + 1):
            self.assertEqual([9], fresh.followed(redis))
        self.assertEqual([], fresh.followed(None))
        fresh.follow(None, [1])
