"""Light posters of the plugin."""

import io
import os
import tempfile
import unittest
from unittest import mock

from PIL import Image

from tele_api import posters, probe


class PostersTest(unittest.TestCase):
    def test_width_is_one_of_a_few(self):
        self.assertEqual(posters.width_of("300"), 342)
        self.assertEqual(posters.width_of(None), 342)
        self.assertEqual(posters.width_of("x"), 342)
        self.assertEqual(posters.width_of(99999), 500)
        self.assertEqual(posters.width_of(1), 185)

    def test_a_big_poster_becomes_a_light_jpeg(self):
        big = io.BytesIO()
        Image.new("RGBA", (2000, 3000), (200, 30, 30, 255)).save(big, "PNG")
        light = posters.shrink(big.getvalue(), 342)
        with Image.open(io.BytesIO(light)) as image:
            self.assertEqual((image.format, image.width, image.height), ("JPEG", 342, 513))
        self.assertLess(len(light), len(big.getvalue()))
        self.assertIsNone(posters.shrink(b"not an image", 342))

    def test_only_public_hosts_and_the_data_folder(self):
        self.assertIsNone(posters.fetch("file:///etc/passwd"))
        with mock.patch.object(probe, "resolve", return_value=["192.168.1.2"]):
            self.assertIsNone(posters.fetch("http://router.local/poster.jpg"))
        self.assertIsNone(posters.local("/data/../etc/passwd"))
        self.assertIsNone(posters.local("/etc/passwd"))

    def test_posters_are_kept_on_disk(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        source = io.BytesIO()
        Image.new("RGB", (800, 1200)).save(source, "JPEG")
        logos = mock.MagicMock()
        logos.objects.filter.return_value.values_list.return_value.first.return_value = "https://image.example/p.jpg"
        with mock.patch.object(probe, "data_dir", return_value=folder.name), \
                mock.patch.dict("sys.modules", {"apps": mock.MagicMock(), "apps.vod": mock.MagicMock(), "apps.vod.models": mock.MagicMock(VODLogo=logos)}), \
                mock.patch.object(posters, "fetch", return_value=source.getvalue()) as fetch:
            first = posters.poster(7, 240)
            again = posters.poster(7, 240)
        self.assertEqual(first, again)
        fetch.assert_called_once()
        self.assertTrue(os.path.exists(os.path.join(folder.name, "posters", "7-240.jpg")))


if __name__ == "__main__":
    unittest.main()
