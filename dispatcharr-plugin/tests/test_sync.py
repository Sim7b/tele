"""Sync between the TVs of a user."""

import tempfile
import unittest
from unittest import mock

from tele_api import core, probe, sync


class MergeTest(unittest.TestCase):
    def test_most_recent_change_wins_item_by_item(self):
        stored = {"favorites": {"a": {"v": 0, "t": 100}, "b": {"v": 1, "t": 300}}}
        changes = {"favorites": {"a": {"v": 5, "t": 200}, "b": {"d": True, "t": 250}, "c": {"v": 2, "t": 400}}}
        merged, kept = core.sync_merge(stored, changes, 1000)
        self.assertEqual(merged["favorites"]["a"], {"v": 5, "t": 200, "d": False})
        self.assertEqual(merged["favorites"]["b"]["v"], 1)  # older deletion loses
        self.assertEqual(merged["favorites"]["c"]["v"], 2)
        self.assertEqual(kept, 2)

    def test_deletion_wins_when_newer_and_fades_after_a_while(self):
        merged, _ = core.sync_merge({"my_list": {"x": {"v": 0, "t": 100}}}, {"my_list": {"x": {"d": True, "t": 200}}}, 1000)
        self.assertTrue(merged["my_list"]["x"]["d"])
        later = 200 + (core.SYNC_TOMBSTONE_DAYS + 1) * 86400 * 1000
        merged, _ = core.sync_merge(merged, None, later)
        self.assertNotIn("x", merged["my_list"])

    def test_untrusted_input_is_bounded(self):
        changes = {
            "unknown": {"a": {"v": 1, "t": 5}},
            "favorites": {"": {"v": 1, "t": 5}, "k" * 300: {"v": 1, "t": 5}, "bad": {"v": 1}, "big": {"v": "x" * 5000, "t": 5},
                          "future": {"v": 1, "t": 10 ** 15}},
        }
        merged, kept = core.sync_merge(None, changes, 1000)
        self.assertEqual(set(merged), set(core.SYNC_COLLECTIONS))
        self.assertEqual(list(merged["favorites"]), ["future"])
        self.assertEqual(merged["favorites"]["future"]["t"], 1000)  # a clock ahead is taken as now
        self.assertEqual(kept, 1)
        self.assertEqual(core.sync_merge("garbage", "garbage", 1)[1], 0)

    def test_oldest_items_go_beyond_the_limit(self):
        with mock.patch.object(core, "MAX_SYNC_ITEMS", 2):
            merged, _ = core.sync_merge(None, {"vod_progress": {"a": {"v": 1, "t": 1}, "b": {"v": 1, "t": 3}, "c": {"v": 1, "t": 2}}}, 10)
        self.assertEqual(sorted(merged["vod_progress"]), ["b", "c"])


class StoreTest(unittest.TestCase):
    def test_round_trip_per_user(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        with mock.patch.object(probe, "data_dir", return_value=folder.name):
            items, kept = sync.merge(7, {"favorites": {"a": {"v": 0, "t": 5}}})
            self.assertEqual(kept, 1)
            self.assertEqual(sync.load(7)["favorites"]["a"]["v"], 0)
            self.assertEqual(sync.load(8)["favorites"], {})
            with open(sync.path(7), "w") as fh:
                fh.write("{broken")
            self.assertEqual(sync.load(7)["favorites"], {})


if __name__ == "__main__":
    unittest.main()
