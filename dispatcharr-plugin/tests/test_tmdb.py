"""TMDB matching of the plugin."""

import os
import stat
import tempfile
import unittest
from unittest import mock

from tele_api import core, probe, tmdb, vault


class SearchTitleTest(unittest.TestCase):
    def test_provider_names_are_cleaned(self):
        self.assertEqual(core.search_title("FR | Dune: Part Two (2024) [4K] MULTI"), ("Dune: Part Two", 2024))
        self.assertEqual(core.search_title("|EN| Avatar 2009"), ("Avatar", 2009))
        self.assertEqual(core.search_title("Parasite VOSTFR", 2019), ("Parasite", 2019))
        self.assertEqual(core.search_title("It (2017)"), ("It", 2017))
        self.assertEqual(core.search_title(None), ("", None))

    def test_only_a_sure_match(self):
        results = [
            {"id": 1, "title": "Dune", "release_date": "1984-12-14"},
            {"id": 2, "title": "Dune", "original_title": "Dune", "release_date": "2021-09-15"},
            {"id": 3, "title": "Dune: Part Two", "release_date": "2024-02-27"},
        ]
        self.assertEqual(core.best_tmdb_match(results, "Dune", 2021, "movie"), 2)
        self.assertEqual(core.best_tmdb_match(results, "Dune", 2022, "movie"), 2)  # a year within one
        self.assertIsNone(core.best_tmdb_match(results, "Dune", 2010, "movie"))
        self.assertIsNone(core.best_tmdb_match(results, "Dune", None, "movie"))  # two of that title: unsure
        self.assertEqual(core.best_tmdb_match(results, "Dune Part Two", None, "movie"), 3)
        self.assertEqual(core.best_tmdb_match([{"id": 9, "name": "Été", "first_air_date": "2020-01-01"}], "Ete", 2020, "series"), 9)
        self.assertIsNone(core.best_tmdb_match("garbage", "Dune", 2021, "movie"))

    def test_v3_key_and_v4_token(self):
        self.assertEqual(core.tmdb_auth("a" * 32), ({}, {"api_key": "a" * 32}))
        headers, params = core.tmdb_auth("ey" + "x" * 200)
        self.assertTrue(headers["Authorization"].startswith("Bearer "))
        self.assertEqual(params, {})


class StorageTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        patcher = mock.patch.object(probe, "data_dir", return_value=folder.name)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_key_is_kept_apart_and_private(self):
        self.assertIsNone(vault.tmdb_key())
        vault.set_tmdb_key("  abcdef0123456789abcdef0123456789 ")
        self.assertEqual(vault.tmdb_key(), "abcdef0123456789abcdef0123456789")
        mode = stat.S_IMODE(os.stat(os.path.join(probe.data_dir(), "secrets.json")).st_mode)
        self.assertEqual(mode & 0o077, 0)
        vault.set_tmdb_key(None)
        self.assertIsNone(vault.tmdb_key())

    def test_ids_and_counts(self):
        with tmdb._connection() as connection:
            connection.execute("INSERT INTO ids (kind, item, tmdb, at) VALUES ('movie', 5, 438631, 1.0)")
            connection.execute("INSERT INTO ids (kind, item, tmdb, at, name, query, year) VALUES ('movie', 6, NULL, 2.0, 'FR | Truc (1999)', 'Truc', 1999)")
            connection.execute("INSERT INTO ids (kind, item, tmdb, at) VALUES ('series', 7, 1399, 1.0)")
        self.assertEqual(tmdb.ids("movie"), {"5": "438631"})
        self.assertEqual(tmdb.counts(), {"matched": 2, "unmatched": 1})
        self.assertEqual(tmdb.unmatched(), [{"kind": "movie", "item": 6, "name": "FR | Truc (1999)", "query": "Truc", "year": 1999}])
        # Set by hand, then searched again
        tmdb.set_manual("movie", 6, 12345)
        self.assertEqual(tmdb.ids("movie"), {"5": "438631", "6": "12345"})
        self.assertEqual(tmdb.unmatched(), [])
        tmdb.set_manual("movie", 8, None)
        with tmdb._connection() as connection:
            connection.execute("INSERT INTO ids (kind, item, tmdb, at) VALUES ('movie', 9, NULL, 1.0)")
        self.assertEqual(tmdb.retry_unmatched(), 1)
        tmdb.forget()
        self.assertEqual(tmdb.counts(), {"matched": 0, "unmatched": 0})

    def test_no_key_no_request(self):
        with mock.patch.object(tmdb, "_get") as get:
            self.assertEqual(tmdb.match_batch({}, lambda: False), {"matched": 0, "unmatched": 0})
        get.assert_not_called()


if __name__ == "__main__":
    unittest.main()
