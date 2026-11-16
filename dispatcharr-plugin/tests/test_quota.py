"""Budgets of the shared services."""

import unittest

from tele_api import quota


class FakeCounter:
    def __init__(self):
        self.values = {}
        self.expiries = {}

    def incr(self, key):
        self.values[key] = self.values.get(key, 0) + 1
        return self.values[key]

    def expire(self, key, seconds):
        self.expiries[key] = seconds


class QuotaTest(unittest.TestCase):
    def test_a_budget_per_who_and_period(self):
        store = FakeCounter()
        self.assertEqual([quota.allow(store, "dl", 7, 2, 86400) for _ in range(3)], [True, True, False])
        self.assertTrue(quota.allow(store, "dl", 8, 2, 86400))  # another user
        self.assertTrue(all(ttl > 86400 for ttl in store.expiries.values()))

    def test_no_redis_no_lockout(self):
        self.assertTrue(quota.allow(None, "dl", 7, 1, 60))
        self.assertTrue(quota.allow(object(), "dl", 7, 1, 60))  # a broken client: allowed


if __name__ == "__main__":
    unittest.main()
