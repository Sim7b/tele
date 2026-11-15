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
