"""Stream health from the Dispatcharr events."""

import json
import unittest
from unittest import mock

from tele_api import Plugin, core, health


class FakeHash:
    def __init__(self):
        self.values = {}

    def hget(self, key, field):
        return self.values.get(field)

    def hset(self, key, field, value):
        self.values[field] = value

    def hdel(self, key, field):
        self.values.pop(field, None)

    def hmget(self, key, fields):
        return [self.values.get(f) for f in fields]

    def hlen(self, key):
        return len(self.values)


class HealthCoreTest(unittest.TestCase):
    def test_failures_decay_by_half(self):
        state = core.health_update(None, "failure", 1000.0)
        self.assertEqual(core.health_score(state, 1000.0), 1.0)
        self.assertAlmostEqual(core.health_score(state, 1000.0 + core.HEALTH_HALF_LIFE), 0.5)
        self.assertTrue(core.health_json(state, 1000.0)["unhealthy"])
        self.assertFalse(core.health_json(state, 1000.0 + core.HEALTH_HALF_LIFE)["unhealthy"])

    def test_buffering_counts_less_and_plays_do_not_hide_failures(self):
        state = core.health_update(None, "buffering", 0.0)
        self.assertAlmostEqual(core.health_score(state, 0.0), 0.3)
        state = core.health_update(core.health_update(state, "failure", 1.0), "play", 2.0)
        self.assertGreater(core.health_score(state, 2.0), 1.2)

    def test_events(self):
        self.assertEqual(core.event_health("channel_error", {"stream_id": "7"}), (7, "failure"))
        self.assertEqual(core.event_health("channel_failover", {"previous_stream_id": 8, "stream_id": 9}), (8, "failure"))
        self.assertEqual(core.event_health("channel_buffering", {"stream_id": 7}), (7, "buffering"))
        self.assertEqual(core.event_health("channel_start", {"stream_id": 7}), (7, "play"))
        for event, payload in (("stream_switch", {"previous_stream_id": 7}), ("channel_error", {}),
                               ("channel_error", {"stream_id": "x"}), ("channel_error", None)):
            self.assertIsNone(core.event_health(event, payload))

    def test_ids(self):
        self.assertEqual(core.parse_ids("1, 2,3"), [1, 2, 3])
        for value in (None, "", "1,x", "-1", ",".join(["1"] * 101)):
            self.assertIsNone(core.parse_ids(value))


class HealthStoreTest(unittest.TestCase):
    def test_record_and_scores(self):
        store = FakeHash()
        self.assertTrue(health.record(store, "channel_error", {"stream_id": 7}))
        self.assertFalse(health.record(store, "stream_switch", {"previous_stream_id": 7}))
        # A stream that only plays is not kept
        health.record(store, "channel_start", {"stream_id": 8})
        self.assertNotIn("8", store.values)
        store.values["9"] = "{broken"
        result = health.scores(store, [7, 8, 9])
        self.assertTrue(result["7"]["unhealthy"])
        self.assertEqual(result["8"]["score"], 0.0)
        self.assertEqual(result["9"]["score"], 0.0)

    def test_the_plugin_action_is_the_event_hook(self):
        store = FakeHash()
        with mock.patch("tele_api.data.redis_client", return_value=store):
            Plugin().run("stream_health", {"event": "channel_failover", "payload": {"previous_stream_id": 3}}, {})
            message = Plugin().run("stream_health", {}, {})["message"]
        self.assertIn("3", store.values)
        self.assertEqual(json.loads(store.values["3"])["f"], 1.0)
        self.assertTrue(message.startswith("1 "))


if __name__ == "__main__":
    unittest.main()


class DevicesTest(unittest.TestCase):
    def test_install_id_and_list(self):
        from tele_api import core, devices

        agent = "TV-AndroidTV/1.0 (Android TV; Tele-0a1b2c3d4e5f)"
        self.assertEqual(core.device_id(agent), "0a1b2c3d4e5f")
        self.assertIsNone(core.device_id("TV-AndroidTV/1.0 (Android TV)"))
        store = FakeHash()
        store.hgetall = lambda key: dict(store.values)
        devices.seen(store, agent, 7, "0.3.27", "10.0.0.2")
        devices.seen(store, agent, 7, "<script>", "10.0.0.2")  # within a minute: not written again
        devices.seen(store, "VLC/3.0", 7, "x", "10.0.0.3")
        session = core.Session("live", "c", "abc", "TF1", "7", "10.0.0.2", agent, 1.0, 3)
        listed = core.devices_json(devices.all_raw(store), {"7": "salon"}, [session], 2e9 - 1)
        self.assertEqual(listed, [])  # forgotten after 90 days
        listed = core.devices_json(devices.all_raw(store), {"7": "salon"}, [session], json.loads(store.values["0a1b2c3d4e5f"])["seen"])
        self.assertEqual(len(listed), 1)
        self.assertEqual((listed[0]["user"], listed[0]["version"], listed[0]["sessions"]), ("salon", "0.3.27", 1))
        self.assertEqual(core.device_users(devices.all_raw(store)), ["7"])
        self.assertIsNone(core.app_version("<script>"))


class StatsTest(unittest.TestCase):
    def test_top_channels_and_peaks(self):
        top = core.top_channels([{"a": "3", "b": 1}, {b"a": b"2", "c": "x"}], {"a": "TF1"}, limit=2)
        self.assertEqual(top, [{"channel": "a", "name": "TF1", "starts": 5}, {"channel": "b", "name": "b", "starts": 1}])
        peaks = core.peaks(["20261115", "20261114"], [{"1": "3", "9": 2}, {"1": 1}], {1: ("Provider", 4)})
        self.assertEqual(peaks, [{"account": 1, "name": "Provider", "max": 4,
                                  "days": [{"day": "20261114", "used": 1}, {"day": "20261115", "used": 3}]}])
        self.assertEqual(len(core.last_days(0, 7)), 7)

    def test_views_are_counted_from_the_start_events(self):
        from tele_api import stats

        counts = {}

        class Store(FakeHash):
            def hincrby(self, key, field, amount):
                counts[(key, field)] = counts.get((key, field), 0) + amount

            def expire(self, key, ttl):
                pass

        store = Store()
        with mock.patch("tele_api.data.redis_client", return_value=store):
            Plugin().run("stream_health", {"event": "channel_start", "payload": {"channel_id": "u1", "channel_name": "TF1", "stream_id": 3}}, {})
        self.assertEqual(list(counts.values()), [1])
        self.assertEqual(store.values["u1"], "TF1")
        stats.record_view(store, {"channel_id": "x" * 100})
        self.assertEqual(len(counts), 1)
