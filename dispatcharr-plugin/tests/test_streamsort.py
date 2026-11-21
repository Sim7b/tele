"""Order of the streams of the channels: availability first, then quality (core), and the run over Dispatcharr."""

import json
import unittest
from unittest import mock

from tele_api import core, streamsort


class FakeRedis:
    def __init__(self, keys=()):
        self.values = {}
        self.keys = list(keys)

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def setex(self, key, ttl, value):
        self.values[key] = value

    def get(self, key):
        return self.values.get(key)

    def delete(self, key):
        self.values.pop(key, None)

    def scan_iter(self, match=None, count=None):
        return iter(self.keys)


class OrderTest(unittest.TestCase):
    def test_height_measured_first_then_read_from_the_name(self):
        self.assertEqual(1080, core.stream_height({"resolution": "1920x1080"}, "TF1 SD"))
        self.assertEqual(2160, core.stream_height(None, "FR | TF1 UHD"))
        self.assertEqual(2160, core.stream_height({}, "TF1 4K"))
        self.assertEqual(1080, core.stream_height(None, "TF1 FHD"))
        self.assertEqual(1080, core.stream_height(None, "TF1 Full HD"))
        self.assertEqual(720, core.stream_height(None, "TF1 HD"))
        self.assertEqual(480, core.stream_height(None, "TF1 SD"))
        self.assertIsNone(core.stream_height(None, "TF1"))
        self.assertIsNone(core.stream_height({"resolution": "garbage"}, "HDMI box"))
        self.assertIsNone(core.stream_height({"resolution": "1x99999"}, None))

    def test_available_streams_first_then_the_best(self):
        streams = [
            {"id": 1, "height": 1080, "failing": True},
            {"id": 2, "height": 720},
            {"id": 3, "height": None},
            {"id": 4, "height": 2160, "active": False},
            {"id": 5, "height": 1080},
            {"id": 6, "height": 480},
            {"id": 7, "height": 2160, "stale": True},
            {"id": 8, "height": 1080},
        ]
        # Available: FHD (5, 8 in their order), HD, unknown, SD; then the unavailable ones by quality
        self.assertEqual([5, 8, 2, 3, 6, 4, 7, 1], core.stream_order(streams))
        self.assertEqual([], core.stream_order([]))


class RunTest(unittest.TestCase):
    def rows(self, channel, uuid, streams):
        return [
            {"id": 100 + index, "channel_id": channel, "channel__uuid": uuid, "stream_id": stream_id,
             "stream__name": name, "stream__stream_stats": stats, "stream__is_stale": False,
             "stream__m3u_account__is_active": True}
            for index, (stream_id, name, stats) in enumerate(streams)
        ]

    def test_only_changed_channels_not_being_watched_are_written(self):
        dead_first = self.rows(1, "u1", [(154, "C+ HD", None), (553959, "C+ FHD", None)])
        in_order = self.rows(2, "u2", [(10, "TF1 FHD", None), (11, "TF1 HD", None)])
        watched = self.rows(3, "u3", [(20, "M6 SD", None), (21, "M6 FHD", None)])
        single = self.rows(4, "u4", [(30, "Arte", None)])
        client = FakeRedis(keys=["live:channel:u3:clients"])
        written = []
        with mock.patch.object(streamsort, "_rows", return_value=iter([dead_first, in_order, watched, single])), \
                mock.patch.object(streamsort, "_write", side_effect=lambda rows, order: written.append(order)), \
                mock.patch("tele_api.health.scores", return_value={"154": {"unhealthy": True}}):
            done = streamsort.run(client)
        self.assertEqual([[553959, 154]], written)
        self.assertEqual((3, 1, 1, "done"), (done["channels"], done["changed"], done["playing"], done["outcome"]))
        self.assertEqual(done["changed"], json.loads(client.get(streamsort.LAST_KEY))["changed"])
        self.assertIsNone(client.get(streamsort.LOCK_KEY))

    def test_one_run_at_a_time_and_every_30_minutes(self):
        client = FakeRedis()
        client.set(streamsort.LOCK_KEY, "1")
        with mock.patch.object(streamsort, "_rows") as rows:
            self.assertEqual("running", streamsort.run(client)["outcome"])
        rows.assert_not_called()
        self.assertTrue(streamsort.due(client))
        self.assertFalse(streamsort.due(client))
        self.assertFalse(streamsort.due(None))

    def test_a_failure_is_reported_and_unlocks(self):
        client = FakeRedis()
        with mock.patch.object(streamsort, "_rows", side_effect=RuntimeError("db")), \
                self.assertLogs("apps.tele_api.streamsort", level="ERROR"):
            done = streamsort.run(client)
        self.assertEqual("error: RuntimeError", done["outcome"])
        self.assertIsNone(client.get(streamsort.LOCK_KEY))

    def test_describe(self):
        self.assertEqual(
            "2 of 5 channels with several streams put in order, 1 being watched left for the next run",
            streamsort.describe({"changed": 2, "channels": 5, "playing": 1}),
        )
