"""How long the channels take to start (starts.py), from the metadata Dispatcharr writes."""

import json
import unittest
from unittest import mock

from tele_api import starts

NOW = 1_795_000_000.0


class FakeRedis:
    def __init__(self, channels):
        self.channels = channels
        self.values = {}
        self.lists = {}

    def hgetall(self, key):
        return self.channels.get(key, {})

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def lpush(self, key, value):
        self.lists.setdefault(key, []).insert(0, value)

    def ltrim(self, key, start, end):
        self.lists[key] = self.lists.get(key, [])[start:end + 1]

    def lrange(self, key, start, end):
        return self.lists.get(key, [])[start:end + 1]


def metadata(state="active", init=NOW - 30, ready=NOW - 27.5, changed=NOW - 25, **extra):
    found = {"state": state, "init_time": str(init), "connection_ready_time": str(ready), "state_changed_at": str(changed),
             "channel_name": "TF1", "stream_id": "7", "stream_profile": "2"}
    found.update(extra)
    return {k.encode(): str(v).encode() for k, v in found.items()}


class StartsTest(unittest.TestCase):
    def test_a_start_is_the_provider_then_the_buffer(self):
        start = starts.measure("abc", {k.decode(): v for k, v in metadata().items()}, NOW)
        self.assertEqual((2500, 2500, 5000), (start["provider_ms"], start["buffer_ms"], start["total_ms"]))
        self.assertEqual(("TF1", 7, 2), (start["channel"], start["stream"], start["profile"]))

    def test_unfinished_or_stale_starts_are_left_out(self):
        def measure(**values):
            return starts.measure("abc", {k.decode(): v for k, v in metadata(**values).items()}, NOW)
        self.assertIsNone(measure(state="connecting"))
        # A state changed long after the start (a stream switch): not the end of the start
        self.assertIsNone(measure(init=NOW - 600, ready=NOW - 598, changed=NOW - 10))
        # Times out of order
        self.assertIsNone(measure(ready=NOW - 40))
        self.assertIsNone(starts.measure("abc", {"state": "active"}, NOW))

    def test_each_start_is_kept_once(self):
        redis = FakeRedis({"live:channel:abc:metadata": metadata(), "live:channel:def:metadata": metadata(state="initializing")})
        with mock.patch.object(starts.data, "_scan", return_value=iter(["live:channel:abc:metadata", "live:channel:def:metadata"])):
            self.assertEqual(1, starts.observe(redis, NOW))
        with mock.patch.object(starts.data, "_scan", return_value=iter(["live:channel:abc:metadata"])):
            self.assertEqual(0, starts.observe(redis, NOW))
        kept = starts.recent(redis, NOW)
        self.assertEqual(["abc"], [s["key"] for s in kept])
        # Older than a day: left out
        self.assertEqual([], starts.recent(redis, NOW + 2 * 86400))

    def test_the_summary_gives_medians_slowest_channels_and_advice(self):
        def start(channel, provider, buffer, profile=1):
            return {"key": channel, "at": NOW, "channel": channel, "profile": profile,
                    "provider_ms": provider, "buffer_ms": buffer, "total_ms": provider + buffer}
        found = [start("TF1", 5100, 1000), start("TF1", 5200, 1200), start("M6", 5000, 900), start("Arte", 5050, 800), start("W9", 5300, 1100)]
        summary = starts.summary(found, {1: "Proxy"})
        self.assertEqual(5100, summary["provider_median_ms"])
        self.assertEqual(["dns"], summary["advice"])
        self.assertEqual("W9", summary["slowest"][0]["channel"])
        self.assertEqual([{"profile": "Proxy", "starts": 5, "buffer_median_ms": 1000}], summary["profiles"])
        slow_buffer = starts.summary([start("TF1", 800, 6000, 3), start("M6", 900, 5000, 3)], {3: "ffmpeg"})
        self.assertEqual(["ffmpeg"], slow_buffer["advice"])
        self.assertEqual(["provider"], starts.summary([start("TF1", 3500, 500)])["advice"])
        self.assertEqual({"starts": 0, "median_ms": None, "p90_ms": None, "provider_median_ms": None, "buffer_median_ms": None,
                          "slowest": [], "profiles": [], "advice": []}, starts.summary([]))
        json.dumps(summary)


if __name__ == "__main__":
    unittest.main()
