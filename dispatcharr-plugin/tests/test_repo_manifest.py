import hashlib
import importlib.util
import json
import os
import unittest

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts", "build_repo_manifest.py")
_SPEC = importlib.util.spec_from_file_location("build_repo_manifest", _PATH)
manifest = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(manifest)

PLUGIN = {"name": "Télé API", "version": "0.3.16", "description": "Endpoints for Télé"}
NOW = "2026-11-22T10:00:00Z"


class RepoManifestTest(unittest.TestCase):
    def test_first_release_lists_this_version_with_its_checksum(self):
        files = manifest.build("v0.3.42", PLUGIN, b"zip", [], NOW)
        repository = files["manifest.json"]["manifest"]
        self.assertEqual("Sim7b/tele", repository["registry_name"])
        entry = repository["plugins"][0]
        self.assertEqual("tele_api", entry["slug"])
        self.assertEqual("0.3.16", entry["latest_version"])
        self.assertEqual("https://github.com/Sim7b/tele/releases/download/v0.3.42/tele_api-0.3.16.zip", entry["latest_url"])
        self.assertEqual(hashlib.sha256(b"zip").hexdigest(), entry["latest_sha256"])
        # The plugin manifest is declared by the repository at the address Dispatcharr checks
        self.assertEqual("https://github.com/Sim7b/tele/releases/latest/download/tele_api.json", entry["manifest_url"])
        detail = files["tele_api.json"]["manifest"]
        self.assertEqual(["0.3.16"], [v["version"] for v in detail["versions"]])
        self.assertEqual(entry["latest_url"], detail["latest"]["url"])
        self.assertEqual(entry["latest_sha256"], detail["versions"][0]["checksum_sha256"])
        # Readable by Dispatcharr: {"manifest": {"plugins": [...]}}, the name not taken for an official one
        self.assertNotIn("official", repository["registry_name"].lower())
        json.dumps(files)

    def test_a_version_already_published_keeps_its_entry(self):
        old = {"version": "0.3.16", "url": "https://github.com/Sim7b/tele/releases/download/v0.3.41/tele_api-0.3.16.zip",
               "checksum_sha256": "aa", "last_updated": "2026-11-21T00:00:00Z"}
        older = {"version": "0.3.15", "url": "https://x/tele_api-0.3.15.zip", "checksum_sha256": "bb", "last_updated": "2026-11-20T00:00:00Z"}
        files = manifest.build("v0.3.42", PLUGIN, b"rebuilt zip", [old, older], NOW)
        entry = files["manifest.json"]["manifest"]["plugins"][0]
        # The zip of the release that first shipped it: same address, same checksum, no update shown
        self.assertEqual(old["url"], entry["latest_url"])
        self.assertEqual("aa", entry["latest_sha256"])
        self.assertEqual(["0.3.16", "0.3.15"], [v["version"] for v in files["tele_api.json"]["manifest"]["versions"]])

    def test_a_new_version_comes_first_and_the_list_is_bounded(self):
        previous = [{"version": f"0.3.{n}", "url": f"https://x/{n}.zip", "checksum_sha256": "c", "last_updated": NOW} for n in range(15, -5, -1)]
        versions = manifest.build("v0.3.42", PLUGIN, b"zip", previous, NOW)["tele_api.json"]["manifest"]["versions"]
        self.assertEqual("0.3.16", versions[0]["version"])
        self.assertEqual("0.3.15", versions[1]["version"])
        self.assertEqual(manifest.MAX_VERSIONS, len(versions))

    def test_previous_versions_skip_broken_entries(self):
        self.assertEqual([], manifest.previous_versions(None))


if __name__ == "__main__":
    unittest.main()
