"""VOD language probes: parsing, choice of the categories, storage, export, the run loop and ffprobe itself."""

import json
import os
import stat
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from tele_api import core, probe


class FakeRedis:
    def __init__(self):
        self.values = {}

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def setex(self, key, ttl, value):
        self.values[key] = value

    def exists(self, key):
        return key in self.values

    def delete(self, key):
        self.values.pop(key, None)


def ffprobe_output(*streams):
    return json.dumps({"streams": list(streams)})


class ParseTest(unittest.TestCase):
    def test_languages_of_the_tracks(self):
        output = ffprobe_output(
            {"codec_type": "video"},
            {"codec_type": "audio", "tags": {"language": "eng"}},
            {"codec_type": "audio", "tags": {"language": "fre"}},
            {"codec_type": "audio", "tags": {"language": "eng"}},
            {"codec_type": "subtitle", "tags": {"language": "fr-FR"}},
            {"codec_type": "subtitle", "tags": {"language": "und"}},
            {"codec_type": "subtitle"},
        )
        self.assertEqual(core.parse_probe(output), {"audio": ["en", "fr"], "subtitles": ["fr"]})

    def test_audio_without_language_still_counts(self):
        self.assertEqual(core.parse_probe(ffprobe_output({"codec_type": "audio"})), {"audio": [], "subtitles": []})

    def test_unreadable_outputs(self):
        for output in (None, "", "not json", "[]", '{"streams": 3}', ffprobe_output({"codec_type": "video"}),
                       "x" * (core.MAX_PROBE_OUTPUT + 1), "[" * 100000):
            self.assertIsNone(core.parse_probe(output))

    def test_track_language(self):
        self.assertEqual(core.track_language("ara"), "ar")
        self.assertEqual(core.track_language("FRA"), "fr")
        self.assertEqual(core.track_language("en_US"), "en")
        self.assertEqual(core.track_language("French"), "fr")
        self.assertIsNone(core.track_language("mul"))
        self.assertIsNone(core.track_language("xyz"))
        self.assertIsNone(core.track_language(None))


class UncertaintyTest(unittest.TestCase):
    def test_uncertain_categories(self):
        for name in ("NETFLIX", "Action", "FR | FILMS MULTI-SUB", "FR | MULTI", "FILMS VOST", "EN | VO",
                     "ARABIC MOVIES", "AR | FILMS", "أفلام أجنبية", "KR | MOVIES ENG SUB"):
            self.assertTrue(core.category_needs_probe(name), name)

    def test_clear_categories(self):
        for name in ("FR | FILMS", "FR | FILMS VOSTFR", "EN | NETFLIX MOVIES", "BR | LEGENDADOS", "Películas Latino"):
            self.assertFalse(core.category_needs_probe(name), name)

    def test_room_on_a_profile(self):
        self.assertTrue(core.profile_has_room(0, 50, 1))
        self.assertTrue(core.profile_has_room(4, 3, 1))
        self.assertFalse(core.profile_has_room(4, 4, 1))
        self.assertFalse(core.profile_has_room(4, 3, 2))
        self.assertTrue(core.profile_has_room(4, -2, 1))


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        patcher = mock.patch.object(probe, "data_dir", return_value=self.dir.name)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_results_round_trip_summary_and_forget(self):
        probe.save_result(3, {"name": "FR | MULTI", "type": "movie", "status": "ok", "audio": ["fr", "en"],
                              "subtitles": [], "sample": "Dune (2024)", "at": 1.0})
        probe.save_result(4, {"name": "NETFLIX", "type": "series", "status": "failed", "attempts": 3})
        probe.save_result(5, {"name": "Action", "status": "failed", "attempts": 1})
        self.assertEqual(probe.summary(), {"measured": 1, "failed": 1, "retrying": 1})
        self.assertEqual([c["id"] for c in probe.measured_categories()], [3])

        probe.forget(3)
        self.assertNotIn("3", probe.load_results())
        probe.forget()
        self.assertEqual(probe.load_results(), {})

    def test_damaged_file_reads_as_empty(self):
        with open(probe.results_path(), "w") as fh:
            fh.write("{broken")
        self.assertEqual(probe.load_results(), {})

    def test_export_is_a_test_case_file_without_addresses(self):
        probe.save_result(3, {"name": "FR | MULTI", "type": "movie", "status": "ok", "audio": ["fr", "en"],
                              "subtitles": ["fr"], "sample": "Dune (2024)", "at": 1.0})
        probe.save_result(4, {"name": "NETFLIX", "status": "failed", "attempts": 1})
        exported = probe.export()
        self.assertEqual(exported["format"], core.EXPORT_FORMAT)
        self.assertEqual(exported["cases"], [
            {"group": "FR | MULTI", "name": "Dune (2024)", "kind": "movie", "audio": ["en", "fr"], "subtitles": ["fr"]}
        ])


class RunTest(unittest.TestCase):
    def setUp(self):
        self.redis = FakeRedis()
        for target, value in (("load_results", {}), ("free_profile", SimpleNamespace(id=1))):
            patcher = mock.patch.object(probe, target, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)
        for patcher in (mock.patch("tele_api.data.redis_client", return_value=self.redis),
                        mock.patch.object(probe.time, "sleep")):
            patcher.start()
            self.addCleanup(patcher.stop)
        # Django is configured by test_views when run together; the run only closes connections
        patcher = mock.patch.dict("sys.modules", {"django.db": SimpleNamespace(close_old_connections=lambda: None)})
        patcher.start()
        self.addCleanup(patcher.stop)

    def categories(self, n):
        return [(SimpleNamespace(id=i, name=f"C{i}"), SimpleNamespace(m3u_account=None)) for i in range(n)]

    def test_probes_one_category_after_the_other_then_frees_the_lock(self):
        with mock.patch.object(probe, "candidates", return_value=iter(self.categories(3))), \
                mock.patch.object(probe, "probe_one", return_value=True) as one:
            self.assertEqual(probe.run({}), 3)
        self.assertEqual(one.call_count, 3)
        self.assertFalse(self.redis.exists(probe.LOCK_KEY))
        probe.time.sleep.assert_called_with(5)

    def test_a_second_run_waits_for_the_first(self):
        self.redis.set(probe.LOCK_KEY, "1")
        with mock.patch.object(probe, "candidates") as candidates:
            self.assertEqual(probe.run({}), 0)
        candidates.assert_not_called()

    def test_a_viewer_short_of_a_connection_stops_the_run(self):
        def probe_and_yield(*args):
            probe.ask_to_yield(self.redis)
            return True

        with mock.patch.object(probe, "candidates", return_value=iter(self.categories(3))), \
                mock.patch.object(probe, "probe_one", side_effect=probe_and_yield) as one:
            self.assertEqual(probe.run({}), 1)
        self.assertEqual(one.call_count, 1)

    def test_no_room_skips_the_account_and_off_does_nothing(self):
        with mock.patch.object(probe, "candidates", return_value=iter(self.categories(2))), \
                mock.patch.object(probe, "free_profile", return_value=None), \
                mock.patch.object(probe, "probe_one") as one:
            self.assertEqual(probe.run({}), 0)
        one.assert_not_called()
        self.assertEqual(probe.run({"probe_enabled": False}), 0)


@unittest.skipIf(os.name != "posix", "fake ffprobe is a shell script")
class FfprobeTest(unittest.TestCase):
    def fake(self, script):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = os.path.join(folder.name, "ffprobe")
        with open(path, "w") as fh:
            fh.write("#!/bin/sh\n" + script)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
        patcher = mock.patch.dict(os.environ, {"PATH": folder.name + os.pathsep + os.environ.get("PATH", "")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_reads_the_output(self):
        self.fake("echo '{\"streams\": [{\"codec_type\": \"audio\", \"tags\": {\"language\": \"eng\"}}]}'\n")
        output = probe.run_ffprobe("http://provider.invalid/movie/u/p/1.mkv", "Agent", FakeRedis())
        self.assertEqual(core.parse_probe(output), {"audio": ["en"], "subtitles": []})

    def test_error_and_yield(self):
        self.fake("exit 1\n")
        self.assertIsNone(probe.run_ffprobe("http://provider.invalid/x", None, FakeRedis()))
        self.fake("sleep 30\n")
        redis = FakeRedis()
        probe.ask_to_yield(redis)
        started = time.monotonic()
        self.assertIsNone(probe.run_ffprobe("http://provider.invalid/x", None, redis))
        self.assertLess(time.monotonic() - started, 5)


if __name__ == "__main__":
    unittest.main()
