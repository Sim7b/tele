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

    def lrange(self, key, start, end):
        return list(self.values.get(key, []))

    def rpush(self, key, value):
        self.values.setdefault(key, []).append(value)

    def lindex(self, key, index):
        items = self.values.get(key, [])
        return items[index] if items else None

    def lrem(self, key, count, value):
        if value in self.values.get(key, []):
            self.values[key].remove(value)


def ffprobe_output(*streams):
    return json.dumps({"streams": list(streams)})


class MediaInfoTest(unittest.TestCase):
    def test_xtream_info_format_with_quality(self):
        output = json.dumps({
            "streams": [
                {"codec_type": "video", "codec_name": "hevc", "width": 3840, "height": 1600, "color_transfer": "smpte2084"},
                {"codec_type": "video", "codec_name": "mjpeg", "disposition": {"attached_pic": 1}},
                {"codec_type": "audio", "codec_name": "eac3", "channels": 6, "tags": {"language": "fre"}},
                {"codec_type": "audio", "codec_name": "truehd", "channels": 8, "profile": "Dolby TrueHD + Dolby Atmos",
                 "tags": {"language": "eng"}},
                {"codec_type": "subtitle", "codec_name": "subrip", "tags": {"language": "fre"}, "disposition": {"forced": 1}},
            ],
            "format": {"bit_rate": "25000000", "duration": "7260.5", "filename": "http://u:p@provider/x.mkv"},
        })
        info = core.media_info(output)
        self.assertEqual(info["video"]["codec_name"], "hevc")
        self.assertEqual(info["video"]["height"], 1600)
        self.assertEqual([a["tags"] for a in info["audio"]], [{"language": "fr"}, {"language": "en"}])
        self.assertTrue(info["subtitles"][0]["forced"])
        self.assertEqual((info["bitrate"], info["duration_secs"]), (25000, 7260))
        # The address of the file (credentials) is never kept
        self.assertNotIn("provider", json.dumps(info))
        summary = core.quality_summary(info)
        self.assertEqual((summary["height"], summary["hdr"], summary["audio_channels"]), (1600, True, 8))
        self.assertEqual(summary["audio_codecs"], ["eac3", "truehd"])

    def test_uuids(self):
        self.assertEqual(core.valid_uuid("0A1B2C3D-0000-4000-8000-000000000000"), "0a1b2c3d-0000-4000-8000-000000000000")
        for value in (None, "", "x", "../../etc", "0a1b2c3d-0000-4000-8000-00000000000g"):
            self.assertIsNone(core.valid_uuid(value))


class TitleQueueTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        patcher = mock.patch.object(probe, "data_dir", return_value=self.dir.name)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.redis = FakeRedis()
        for patcher in (mock.patch.object(probe.time, "sleep"),
                        mock.patch.object(probe, "free_profile", return_value=SimpleNamespace(id=1))):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_queue_is_deduplicated_and_bounded(self):
        self.assertEqual(probe.request_title(self.redis, "movie", "a"), "queued")
        self.assertEqual(probe.request_title(self.redis, "movie", "a"), "queued")
        self.assertEqual(len(self.redis.values[probe.TITLE_QUEUE]), 1)
        for i in range(probe.MAX_QUEUED_TITLES):
            probe.request_title(self.redis, "movie", f"t{i}")
        self.assertEqual(probe.request_title(self.redis, "movie", "late"), "full")

    def test_queued_titles_are_probed_and_kept(self):
        uuid = "0a1b2c3d-0000-4000-8000-000000000000"
        probe.request_title(self.redis, "movie", uuid)
        info = {"audio": [{"tags": {"language": "en"}}], "subtitles": []}
        with mock.patch.object(probe, "title_relation", return_value=SimpleNamespace(m3u_account=None)), \
                mock.patch.object(probe, "probe_file", return_value=(True, info)):
            self.assertEqual(probe.probe_titles(self.redis, 1, 5, time.monotonic()), 1)
        self.assertEqual(probe.title_result("movie", uuid)["info"], info)
        self.assertEqual(self.redis.values[probe.TITLE_QUEUE], [])

    def test_no_room_keeps_the_title_queued(self):
        uuid = "0a1b2c3d-0000-4000-8000-000000000000"
        probe.request_title(self.redis, "movie", uuid)
        with mock.patch.object(probe, "title_relation", return_value=SimpleNamespace(m3u_account=None)), \
                mock.patch.object(probe, "free_profile", return_value=None):
            self.assertEqual(probe.probe_titles(self.redis, 1, 5, time.monotonic()), 0)
        self.assertEqual(len(self.redis.values[probe.TITLE_QUEUE]), 1)

    def test_kept_titles_are_bounded(self):
        with mock.patch.object(probe, "MAX_KEPT_TITLES", 3):
            for i in range(5):
                probe.save_title("movie", f"t{i}", {"status": "ok", "at": float(i), "info": {}})
        self.assertEqual(sorted(probe._read(probe.titles_path())), ["movie:t2", "movie:t3", "movie:t4"])


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

    def test_states_manual_languages_and_retry(self):
        self.assertEqual(probe.state_of({}), "pending")
        self.assertEqual(probe.state_of({"status": "failed", "attempts": 1}), "retrying")
        self.assertEqual(probe.state_of({"status": "failed", "attempts": 3}), "failed")
        self.assertEqual(probe.state_of({"status": "manual"}), "manual")
        probe.save_result(3, {"name": "NETFLIX", "status": "manual", "audio": ["en"], "subtitles": ["fr"]})
        probe.save_result(4, {"name": "X", "status": "failed", "attempts": 3})
        probe.save_result(5, {"name": "Y", "status": "excluded"})
        # Manual languages are given to Télé and exported like measures
        self.assertEqual([c["id"] for c in probe.measured_categories()], [3])
        self.assertEqual(probe.export()["cases"][0]["source"], "manual")
        self.assertEqual(probe.retry_failed(), 1)
        self.assertEqual(sorted(probe.load_results()), ["3", "5"])
        self.assertEqual(core.language_codes(["FR", "en", "fr"]), ["fr", "en"])
        self.assertIsNone(core.language_codes(["french"]))
        self.assertIsNone(core.language_codes("fr"))

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
        for target, value in (("load_results", {}), ("free_profile", SimpleNamespace(id=1)), ("probe_titles", 0)):
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


class AddressTest(unittest.TestCase):
    def test_public_addresses_only(self):
        for address in ("8.8.8.8", "2606:4700:4700::1111", "::ffff:8.8.8.8"):
            self.assertTrue(core.is_public_address(address), address)
        for address in ("10.0.0.1", "192.168.1.20", "172.16.0.5", "127.0.0.1", "::1", "169.254.169.254",
                        "fe80::1%eth0", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::ffff:192.168.1.1", "nonsense"):
            self.assertFalse(core.is_public_address(address), address)

    def test_trusted_provider_addresses(self):
        self.assertTrue(core.host_allowed(["192.168.1.20"], trusted=["192.168.1.20"]))
        self.assertFalse(core.host_allowed(["8.8.8.8", "192.168.1.20"]))
        self.assertFalse(core.host_allowed([]))


class SafeUrlTest(unittest.TestCase):
    """Redirects followed by hand against a local server standing for the provider (trusted, as set in Dispatcharr)."""

    def setUp(self):
        import http.server
        import threading

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                targets = {"/to-lan": "http://10.0.0.1/movie.mkv", "/to-self": "/movie.mkv"}
                if self.path in targets:
                    self.send_response(302)
                    self.send_header("Location", targets[self.path])
                    self.end_headers()
                else:
                    self.send_response(206)
                    self.end_headers()
                    self.wfile.write(b"x")

            def log_message(self, *args):
                pass

        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def test_redirects_on_the_provider_are_followed(self):
        self.assertEqual(probe.safe_url(self.base + "/to-self", "127.0.0.1", None), self.base + "/movie.mkv")

    def test_a_redirect_to_the_local_network_is_refused(self):
        self.assertIsNone(probe.safe_url(self.base + "/to-lan", "127.0.0.1", None))

    def test_a_local_host_that_is_not_the_provider_is_refused(self):
        self.assertIsNone(probe.safe_url(self.base + "/movie.mkv", "provider.example", None))
        self.assertIsNone(probe.safe_url("file:///etc/passwd", "127.0.0.1", None))


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

    def test_network_protocols_only(self):
        with mock.patch.object(probe.subprocess, "Popen", side_effect=OSError) as popen:
            probe.run_ffprobe("http://provider.invalid/x", None, FakeRedis())
        command = popen.call_args.args[0]
        self.assertEqual(command[command.index("-protocol_whitelist") + 1], "http,https,tcp,tls,crypto")
        self.assertEqual(command[-1], "http://provider.invalid/x")

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


class RunnerTest(unittest.TestCase):
    def test_runner_only_in_web_processes_and_wake(self):
        from tele_api import runner

        self.assertFalse(runner.start())  # Not under uWSGI
        redis = FakeRedis()
        runner.wake(redis)
        self.assertTrue(redis.exists(runner.WAKE_KEY))

    def test_a_newer_load_stops_the_older_runner(self):
        import sys
        import threading

        from tele_api import runner

        with mock.patch.object(runner, "CHECK_WAKE_EVERY", 0.01), mock.patch.object(runner, "_settings", return_value=None), \
                mock.patch("tele_api.data.redis_client", return_value=FakeRedis()):
            token = object()
            setattr(sys, runner._HOLDER, token)
            thread = threading.Thread(target=runner._loop, args=(token,), daemon=True)
            thread.start()
            setattr(sys, runner._HOLDER, object())
            thread.join(timeout=2)
        self.assertFalse(thread.is_alive())
