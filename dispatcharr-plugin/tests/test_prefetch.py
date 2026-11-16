"""Episodes loaded in advance."""

import unittest
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest import mock

from tele_api import prefetch


class PrefetchTest(unittest.TestCase):
    def test_only_stale_series_and_a_few_per_run(self):
        now = datetime.now(timezone.utc)
        fresh = SimpleNamespace(id=1, last_episode_refresh=now - timedelta(hours=1))
        stale = SimpleNamespace(id=2, last_episode_refresh=now - timedelta(days=2))
        never = SimpleNamespace(id=3, last_episode_refresh=None)
        relations = {10: [fresh], 11: [stale, never]}
        models = mock.MagicMock()
        models.M3USeriesRelation.objects.filter.side_effect = lambda series_id, **_: mock.MagicMock(
            select_related=lambda *a: relations.get(series_id, []))
        with mock.patch.dict("sys.modules", {"apps": mock.MagicMock(), "apps.vod": mock.MagicMock(), "apps.vod.models": models}), \
                mock.patch.object(prefetch, "_admin", return_value=object()), \
                mock.patch.object(prefetch, "candidates", return_value=[10, 11]), \
                mock.patch.object(prefetch, "refresh", return_value=True) as refresh, \
                mock.patch.object(prefetch.time, "sleep"):
            self.assertEqual(prefetch.run(object(), lambda: False), {"loaded": 2})
        self.assertEqual([c.args[1].id for c in refresh.call_args_list], [2, 3])
        self.assertEqual(prefetch.run(None, lambda: False), {"loaded": 0})


if __name__ == "__main__":
    unittest.main()
