"""Redis reading of the sessions, with an in-memory Redis."""

import fnmatch
import json
import os
import unittest
from unittest import mock

from tele_api import core, data


class FakeRedis:
    def __init__(self, hashes=None, sets=None, strings=None):
        self.hashes = hashes or {}
        self.sets = sets or {}
        self.strings = strings or {}
        self.expiring = {}

    def scan_iter(self, match=None, count=None):
        keys = list(self.hashes) + list(self.sets) + list(self.strings)
        return iter(k for k in keys if fnmatch.fnmatchcase(k, match))

    def hmget(self, key, *fields):
        values = self.hashes.get(key, {})
        return [values.get(f) for f in fields]

    def smembers(self, key):
        return set(self.sets.get(key, ()))

    def mget(self, keys):
        return [self.strings.get(k) for k in keys]

    def setex(self, key, ttl, value):
        self.expiring[key] = (ttl, value)


def redis():
    return FakeRedis(
        hashes={
            "live:channel:abc:metadata": {"channel_name": "TF1", "m3u_profile": "3"},
            "live:channel:abc:clients:client_1": {"user_agent": "Tele", "ip_address": "10.0.0.2",
                                                  "connected_at": "100.5", "user_id": "7"},
            "vod_persistent_connection:s1": {"user_id": "7", "client_ip": "10.0.0.2", "client_user_agent": "Tele",
                                             "content_name": "Film", "created_at": "50", "m3u_profile_id": ""},
        },
        sets={"live:channel:abc:clients": {"client_1", "ghost"}},
        strings={"profile_connections:3": "2"},
    )


class SessionsTest(unittest.TestCase):
    def test_reads_live_and_vod_sessions(self):
        with mock.patch.object(data, "redis_client", return_value=redis()):
            sessions = data.all_sessions()
        self.assertEqual(sorted(s.id for s in sessions), ["live:abc:client_1", "vod:s1"])
        live = next(s for s in sessions if s.kind == "live")
        self.assertEqual((live.title, live.user_id, live.connected_at, live.profile_id), ("TF1", "7", 100.5, 3))
        vod = next(s for s in sessions if s.kind == "vod")
        self.assertIsNone(vod.profile_id)

    def test_find_session(self):
        with mock.patch.object(data, "redis_client", return_value=redis()):
            self.assertEqual(data.find_session("live", "abc", "client_1").user_id, "7")
            self.assertIsNone(data.find_session("live", "abc", "ghost"))
            self.assertIsNone(data.find_session("live", "other", "client_1"))
            self.assertEqual(data.find_session("vod", None, "s1").title, "Film")
            self.assertIsNone(data.find_session("vod", None, "nope"))

    def test_without_redis(self):
        with mock.patch.object(data, "redis_client", return_value=None):
            self.assertIsNone(data.all_sessions())
            self.assertIsNone(data.connections_by_profile([1]))

    def test_connection_counters(self):
        with mock.patch.object(data, "redis_client", return_value=redis()):
            self.assertEqual(data.connections_by_profile([3, 4]), {3: 2, 4: 0})

    def test_stop_vod_session_sets_the_stop_signal(self):
        fake = redis()
        with mock.patch.object(data, "redis_client", return_value=fake):
            self.assertTrue(data.stop_session(core.Session("vod", "s1", None, None, "7", None, None, None, None)))
        self.assertEqual(fake.expiring, {"vod_proxy:client:s1:stop": (60, "true")})

    def test_scan_is_bounded(self):
        fake = FakeRedis(sets={f"live:channel:c{i}:clients": {"x"} for i in range(data.MAX_SCANNED_KEYS + 10)})
        self.assertEqual(sum(1 for _ in data._scan(fake, "live:channel:*:clients")), data.MAX_SCANNED_KEYS)


class ManifestTest(unittest.TestCase):
    def test_versions_match(self):
        path = os.path.join(os.path.dirname(data.__file__), "plugin.json")
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(json.load(fh)["version"], core.PLUGIN_VERSION)


if __name__ == "__main__":
    unittest.main()
