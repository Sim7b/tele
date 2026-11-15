"""Routes and views of the plugin with a minimal Django (the Dispatcharr data layer is patched)."""

import unittest
from types import SimpleNamespace
from unittest import mock

import django
from django.conf import settings

if not settings.configured:
    settings.configure(
        DEBUG=False,
        SECRET_KEY="tests",
        ROOT_URLCONF="tests.fake_urls",
        ALLOWED_HOSTS=["*"],
        INSTALLED_APPS=["django.contrib.contenttypes", "django.contrib.auth", "rest_framework"],
        DATABASES={},
        USE_TZ=True,
        REST_FRAMEWORK={"DEFAULT_AUTHENTICATION_CLASSES": [], "UNAUTHENTICATED_USER": None},
    )
    django.setup()

from django.urls import resolve, reverse  # noqa: E402
from rest_framework.test import APIRequestFactory, force_authenticate  # noqa: E402

from tele_api import core, routes, views  # noqa: E402
from tests import fake_urls  # noqa: E402


def user(pk=7, level=1, name="tv"):
    return SimpleNamespace(pk=pk, id=pk, user_level=level, username=name, is_authenticated=True)


def session(client="c1", owner="7", ip="10.0.0.2", ua="Tele", kind="live"):
    return core.Session(kind, client, "abc" if kind == "live" else None, "TF1", owner, ip, ua, 1.0, 3)


class RoutesTest(unittest.TestCase):
    def setUp(self):
        routes.install()

    def test_routes_come_before_the_catch_all(self):
        self.assertTrue(routes.installed())
        self.assertEqual(resolve("/api/tele/v1/info/").func.view_class, views.InfoView)
        self.assertEqual(reverse("tele_api:slots"), "/api/tele/v1/slots/")
        self.assertEqual(resolve("/somewhere/").func, fake_urls.spa)

    def test_install_again_replaces(self):
        routes.install()
        routes.install()
        self.assertEqual(sum(1 for p in fake_urls.urlpatterns if routes._is_ours(p)), 1)

    def test_uninstall(self):
        routes.uninstall()
        self.assertFalse(routes.installed())
        self.assertEqual(resolve("/api/tele/v1/info/").func, fake_urls.spa)
        routes.install()


class ViewTest(unittest.TestCase):
    factory = APIRequestFactory()

    def setUp(self):
        patcher = mock.patch.object(views.plugin_state, "get", return_value=(True, {}))
        self.state = patcher.start()
        self.addCleanup(patcher.stop)

    def call(self, view, method="get", path="/", who=None, body=None, **headers):
        request = getattr(self.factory, method)(path, body, format="json", HTTP_USER_AGENT="Tele",
                                                REMOTE_ADDR="10.0.0.2", **headers)
        if who is not None:
            force_authenticate(request, user=who)
        return view.as_view()(request)

    def test_anonymous_and_streamer_users_are_refused(self):
        self.assertIn(self.call(views.InfoView).status_code, (401, 403))
        self.assertEqual(self.call(views.InfoView, who=user(level=0)).status_code, 403)

    def test_disabled_plugin_refuses(self):
        self.state.return_value = (False, {})
        self.assertEqual(self.call(views.InfoView, who=user()).status_code, 403)

    def test_message_for_the_tvs(self):
        self.state.return_value = (True, {"tv_message": "  Maintenance tonight  " + "x" * 600})
        message = self.call(views.InfoView, who=user()).data["message"]
        self.assertTrue(message.startswith("Maintenance tonight"))
        self.assertEqual(len(message), 500)
        self.state.return_value = (True, {"tv_message": "   "})
        self.assertIsNone(self.call(views.InfoView, who=user()).data["message"])

    def test_info(self):
        response = self.call(views.InfoView, who=user())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data["api"], core.API_VERSION)
        self.assertFalse(response.data["user"]["admin"])

    def test_guide_answers_304_while_unchanged(self):
        programs = [{"uuid": "u1", "programs": [{"id": "1", "title": "Journal", "start": "s", "end": "e"}]}]
        with mock.patch.object(views.data, "guide", return_value=programs), \
                mock.patch.object(views.time, "time", return_value=3600 * 1000.0):
            first = self.call(views.GuideView, path="/?hours=24", who=user())
            again = self.call(views.GuideView, path="/?hours=24", who=user(), HTTP_IF_NONE_MATCH=first["ETag"])
        self.assertEqual(first.data["results"], programs)
        self.assertEqual(again.status_code, 304)

    def test_sync_is_per_user_and_can_be_turned_off(self):
        with mock.patch("tele_api.sync.merge", return_value=({"favorites": {}}, 0)) as merge:
            response = self.call(views.SyncView, "post", who=user(), body={"changes": {"favorites": {}}})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(merge.call_args.args[0], 7)
        self.state.return_value = (True, {"allow_sync": False})
        self.assertEqual(self.call(views.SyncView, who=user()).status_code, 403)

    def test_slots(self):
        limits = [core.ProfileLimit(1, 1, "P", 2, True)]
        with mock.patch.object(views.data, "profile_limits", return_value=limits), \
                mock.patch.object(views.data, "connections_by_profile", return_value={1: 1}) as used:
            response = self.call(views.SlotsView, who=user())
        self.assertEqual(response.data["free"], 1)
        self.assertEqual(list(used.call_args[0][0]), [1])

    def test_provider_names_are_for_admins_unless_shown(self):
        limits = [core.ProfileLimit(1, 1, "My provider", 2, True)]
        with mock.patch.object(views.data, "profile_limits", return_value=limits), \
                mock.patch.object(views.data, "connections_by_profile", return_value={}):
            self.assertIsNone(self.call(views.SlotsView, who=user()).data["accounts"][0]["name"])
            self.assertEqual(self.call(views.SlotsView, who=user(level=10)).data["accounts"][0]["name"], "My provider")
            self.state.return_value = (True, {"show_provider_names": True})
            self.assertEqual(self.call(views.SlotsView, who=user()).data["accounts"][0]["name"], "My provider")

    def test_slots_without_redis(self):
        with mock.patch.object(views.data, "profile_limits", return_value=[]), \
                mock.patch.object(views.data, "connections_by_profile", return_value=None):
            self.assertEqual(self.call(views.SlotsView, who=user()).status_code, 503)

    def test_sessions_of_the_caller_only(self):
        sessions = [session("mine"), session("other", owner="8")]
        with mock.patch.object(views.data, "all_sessions", return_value=sessions), \
                mock.patch.object(views.data, "client_ip", return_value="10.0.0.2"):
            response = self.call(views.SessionsView, path="/?all=1", who=user())
        self.assertEqual([s["id"] for s in response.data["sessions"]], ["live:abc:mine"])
        self.assertTrue(response.data["sessions"][0]["this_device"])

    def test_release_checks_the_owner_in_redis(self):
        sessions = {"mine": session("mine"), "other": session("other", owner="8")}
        with mock.patch.object(views.data, "find_session", side_effect=lambda k, c, i: sessions.get(i)), \
                mock.patch.object(views.data, "stop_session", return_value=True) as stop:
            response = self.call(views.ReleaseSessionsView, "post", who=user(),
                                 body={"ids": ["live:abc:mine", "live:abc:other", "live:abc:gone"]})
        self.assertEqual(response.data["released"], ["live:abc:mine"])
        self.assertEqual(stop.call_count, 1)

    def test_admin_may_release_any_session(self):
        with mock.patch.object(views.data, "find_session", return_value=session("other", owner="8")), \
                mock.patch.object(views.data, "stop_session", return_value=True):
            response = self.call(views.ReleaseSessionsView, "post", who=user(level=10),
                                 body={"ids": ["live:abc:other"]})
        self.assertEqual(response.data["released"], ["live:abc:other"])

    def test_release_this_device_keeps_the_given_sessions(self):
        sessions = [session("preview"), session("playing"), session("elsewhere", ip="10.0.0.9"),
                    session("someone", owner="8")]
        with mock.patch.object(views.data, "all_sessions", return_value=sessions), \
                mock.patch.object(views.data, "client_ip", return_value="10.0.0.2"), \
                mock.patch.object(views.data, "stop_session", return_value=True):
            response = self.call(views.ReleaseSessionsView, "post", who=user(),
                                 body={"this_device": True, "keep": ["live:abc:playing"]})
        self.assertEqual(response.data["released"], ["live:abc:preview"])

    def test_release_refuses_malformed_requests(self):
        for body in ({"ids": "live:abc:x"}, {"ids": ["vod:a*b"]}, {"ids": ["live:a:b"] * 51}):
            self.assertEqual(self.call(views.ReleaseSessionsView, "post", who=user(), body=body).status_code, 400)

    def test_release_can_be_turned_off(self):
        self.state.return_value = (True, {"allow_release": False})
        response = self.call(views.ReleaseSessionsView, "post", who=user(), body={"ids": []})
        self.assertEqual(response.status_code, 403)


class RateLimitTest(ViewTest):
    class Counter:
        def __init__(self):
            self.counts = {}

        def incr(self, key):
            self.counts[key] = self.counts.get(key, 0) + 1
            return self.counts[key]

        def expire(self, key, ttl):
            pass

    def test_standard_users_are_limited_per_minute(self):
        self.state.return_value = (True, {"rate_limit_per_minute": 2})
        with mock.patch.object(views.data, "redis_client", return_value=self.Counter()):
            codes = [self.call(views.InfoView, who=user()).status_code for _ in range(3)]
            admin = [self.call(views.InfoView, who=user(pk=1, level=10)).status_code for _ in range(3)]
        self.assertEqual(codes, [200, 200, 429])
        self.assertEqual(admin, [200, 200, 200])

    def test_no_limit_without_redis_or_when_off(self):
        self.state.return_value = (True, {"rate_limit_per_minute": 1})
        with mock.patch.object(views.data, "redis_client", return_value=None):
            self.assertEqual([self.call(views.InfoView, who=user()).status_code for _ in range(3)], [200] * 3)
        self.state.return_value = (True, {"rate_limit_per_minute": 0})
        with mock.patch.object(views.data, "redis_client", return_value=self.Counter()):
            self.assertEqual([self.call(views.InfoView, who=user()).status_code for _ in range(3)], [200] * 3)


class VodLanguagesViewTest(ViewTest):
    def test_adult_categories_hidden_for_users_hiding_mature_content(self):
        measured = [{"id": 3, "name": "FR | FILMS"}, {"id": 4, "name": "XXX"}]
        prudish = user()
        prudish.custom_properties = {"hide_adult_content": True}
        with mock.patch.object(views.probe, "measured_categories", return_value=measured), \
                mock.patch.object(views.data, "adult_categories", return_value={4}):
            self.assertEqual([c["id"] for c in self.call(views.VodLanguagesView, who=prudish).data["categories"]], [3])
            self.assertEqual(len(self.call(views.VodLanguagesView, who=user()).data["categories"]), 2)
            self.assertTrue(self.call(views.InfoView, who=prudish).data["user"]["hide_adult"])

    def test_measured_languages_for_standard_users(self):
        measured = [{"id": 3, "name": "FR | MULTI", "type": "movie", "audio": ["fr"], "subtitles": [], "probed_at": 1}]
        with mock.patch.object(views.probe, "measured_categories", return_value=measured):
            response = self.call(views.VodLanguagesView, who=user())
        self.assertEqual(response.data["categories"], measured)

    def test_media_of_a_title_is_measured_on_demand(self):
        uuid = "0a1b2c3d-0000-4000-8000-000000000000"
        query = f"/?kind=movie&uuid={uuid}"
        with mock.patch.object(views.probe, "title_result", return_value={"status": "ok", "info": {"audio": []}}):
            self.assertEqual(self.call(views.VodMediaView, path=query, who=user()).data["status"], "ok")
        with mock.patch.object(views.probe, "title_result", return_value=None), \
                mock.patch.object(views.data, "redis_client", return_value=object()), \
                mock.patch.object(views.probe, "request_title", return_value="queued") as queue, \
                mock.patch("tele_api.tasks.probe_vod_languages") as task:
            self.assertEqual(self.call(views.VodMediaView, path=query, who=user()).data["status"], "queued")
        queue.assert_called_once()
        task.delay.assert_called_once()
        with mock.patch.object(views.probe, "title_result", return_value={"status": "failed", "attempts": 3}):
            self.assertEqual(self.call(views.VodMediaView, path=query, who=user()).data["status"], "failed")
        for bad in ("/?kind=movie&uuid=../x", "/?kind=series&uuid=" + uuid, "/"):
            self.assertEqual(self.call(views.VodMediaView, path=bad, who=user()).status_code, 400)

    def test_stream_health(self):
        with mock.patch.object(views.data, "redis_client", return_value=object()), \
                mock.patch("tele_api.health.scores", return_value={"7": {"score": 1.0, "unhealthy": True}}) as scores:
            response = self.call(views.StreamHealthView, path="/?ids=7,8", who=user())
        self.assertTrue(response.data["streams"]["7"]["unhealthy"])
        self.assertEqual(list(scores.call_args.args[1]), [7, 8])
        self.assertEqual(self.call(views.StreamHealthView, path="/?ids=x", who=user()).status_code, 400)

    def test_a_tv_is_recorded_and_its_sessions_closed_by_an_admin(self):
        agent = "TV-AndroidTV/1.0 (Android TV; Tele-0a1b2c3d4e5f)"
        with mock.patch("tele_api.devices.seen") as seen:
            request = self.factory.get("/", HTTP_USER_AGENT=agent, HTTP_X_TELE_VERSION="0.3.27", REMOTE_ADDR="10.0.0.2")
            force_authenticate(request, user=user())
            views.InfoView.as_view()(request)
        self.assertEqual(seen.call_args.args[1:4], (agent, 7, "0.3.27"))
        tv = session("tv", ua=agent)
        with mock.patch.object(views.data, "all_sessions", return_value=[tv, session("other")]), \
                mock.patch.object(views.data, "stop_session", return_value=True):
            response = self.call(views.AdminDeviceReleaseView, "post", who=user(level=10), body={"device": "0a1b2c3d4e5f"})
            bad = self.call(views.AdminDeviceReleaseView, "post", who=user(level=10), body={"device": "../x"})
            refused = self.call(views.AdminDeviceReleaseView, "post", who=user(), body={"device": "0a1b2c3d4e5f"})
        self.assertEqual(response.data["released"], ["live:abc:tv"])
        self.assertEqual((bad.status_code, refused.status_code), (400, 403))

    def test_tmdb_ids_and_key_for_admins(self):
        with mock.patch("tele_api.tmdb.ids", return_value={"5": "438631"}):
            response = self.call(views.TmdbIdsView, path="/?kind=movie", who=user())
            again = self.call(views.TmdbIdsView, path="/?kind=movie", who=user(), HTTP_IF_NONE_MATCH=response["ETag"])
        self.assertEqual(response.data["ids"], {"5": "438631"})
        self.assertEqual(again.status_code, 304)
        self.assertEqual(self.call(views.TmdbIdsView, path="/?kind=x", who=user()).status_code, 400)
        self.assertEqual(self.call(views.AdminTmdbView, "post", who=user(), body={"key": "k" * 32}).status_code, 403)
        with mock.patch("tele_api.tmdb.test_key", return_value=False), mock.patch("tele_api.vault.set_tmdb_key") as saved:
            refused = self.call(views.AdminTmdbView, "post", who=user(level=10), body={"key": "k" * 32})
        self.assertEqual(refused.status_code, 400)
        saved.assert_not_called()
        with mock.patch("tele_api.tmdb.test_key", return_value=True), mock.patch("tele_api.vault.set_tmdb_key") as saved:
            accepted = self.call(views.AdminTmdbView, "post", who=user(level=10), body={"key": "k" * 32})
        self.assertTrue(accepted.data["key_set"])
        saved.assert_called_once()

    def test_category_actions_of_the_page(self):
        admin = user(level=10)
        with mock.patch.object(views.probe, "probe_category_now", return_value=(True, "measured")) as now:
            response = self.call(views.AdminCategoryActionView, "post", who=admin, body={"id": 3, "action": "probe"})
        self.assertEqual(response.data["message"], "measured")
        now.assert_called_once()
        with mock.patch.object(views.probe, "set_manual", return_value=True) as manual:
            self.call(views.AdminCategoryActionView, "post", who=admin, body={"id": 3, "action": "manual", "audio": ["en"], "subtitles": ["fr"]})
            bad = self.call(views.AdminCategoryActionView, "post", who=admin, body={"id": 3, "action": "manual", "audio": ["english"]})
        self.assertEqual(manual.call_args.args, (3, ["en"], ["fr"]))
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(self.call(views.AdminCategoryActionView, "post", who=admin, body={"id": 3, "action": "drop"}).status_code, 400)
        self.assertEqual(self.call(views.AdminCategoryActionView, "post", who=user(), body={"id": 3, "action": "probe"}).status_code, 403)
        with mock.patch("tele_api.tmdb.set_manual") as tmdb_manual:
            self.call(views.AdminTmdbItemView, "post", who=admin, body={"kind": "movie", "item": 6, "tmdb": 12345})
            wrong = self.call(views.AdminTmdbItemView, "post", who=admin, body={"kind": "movie", "item": 6, "tmdb": "abc"})
        self.assertEqual(tmdb_manual.call_args.args, ("movie", 6, 12345))
        self.assertEqual(wrong.status_code, 400)

    def test_backup_for_admins_only(self):
        self.assertEqual(self.call(views.AdminBackupView, who=user()).status_code, 403)
        bad = self.call(views.AdminBackupView, "post", who=user(level=10), body={"format": "x"})
        self.assertEqual(bad.status_code, 400)
        with mock.patch("tele_api.backup.restore", return_value={"groups": 2}) as restore:
            done = self.call(views.AdminBackupView, "post", who=user(level=10), body={"format": "tele-api-backup", "version": 1})
        self.assertEqual(done.data["groups"], 2)
        restore.assert_called_once()

    def test_targeted_messages(self):
        store = {}

        class Store:
            def hset(self, key, field, value):
                store[field] = value

            def hdel(self, key, *fields):
                for field in fields:
                    store.pop(field, None)

            def hgetall(self, key):
                return dict(store)

        admin = user(level=10)
        with mock.patch.object(views.data, "redis_client", return_value=Store()):
            sent = self.call(views.AdminMessagesView, "post", who=admin, body={"kind": "user", "target": "7", "text": "Hello", "hours": 2})
            bad = self.call(views.AdminMessagesView, "post", who=admin, body={"kind": "device", "target": "nope", "text": "x"})
            listed = self.call(views.AdminMessagesView, who=admin)
            info = self.call(views.InfoView, who=user())
            self.call(views.AdminMessagesView, "post", who=admin, body={"kind": "user", "target": "7", "text": ""})
        self.assertTrue(sent.data["ok"])
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(listed.data["messages"][0]["text"], "Hello")
        self.assertEqual(info.data["message"], "Hello")
        self.assertEqual(store, {})
        self.assertEqual(self.call(views.AdminMessagesView, who=user()).status_code, 403)

    def test_groups_served_to_the_tvs(self):
        served = {"mode": "selected", "groups": [{"name": "FR | FILMS", "type": "movie"}]}
        with mock.patch.object(views.probe, "served_groups", return_value=served):
            response = self.call(views.VodGroupsView, who=user())
            again = self.call(views.VodGroupsView, who=user(), HTTP_IF_NONE_MATCH=response["ETag"])
        self.assertEqual(response.data["groups"], served["groups"])
        self.assertEqual(again.status_code, 304)

    def test_selection_of_the_groups_to_process(self):
        admin = user(level=10)
        with mock.patch.object(views.probe, "save_selection") as save:
            done = self.call(views.AdminCategorySelectionView, "post", who=admin, body={"mode": "selected", "ids": [4, 2, 4]})
            every = self.call(views.AdminCategorySelectionView, "post", who=admin, body={"mode": "all"})
            bad = self.call(views.AdminCategorySelectionView, "post", who=admin, body={"mode": "selected", "ids": ["x"]})
        self.assertEqual(done.data["selected"], 2)
        self.assertEqual([c.args[0] for c in save.call_args_list], [{"mode": "selected", "ids": [2, 4]}, {"mode": "all", "ids": []}])
        self.assertEqual(every.data["mode"], "all")
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(self.call(views.AdminCategorySelectionView, "post", who=user(), body={"mode": "all"}).status_code, 403)

    def test_admin_endpoints_are_for_admins_only(self):
        for view in (views.AdminProbesView, views.AdminExportView):
            self.assertEqual(self.call(view, who=user()).status_code, 403)
        with mock.patch.object(views.probe, "export", return_value={"cases": []}):
            response = self.call(views.AdminExportView, who=user(level=10))
        self.assertEqual(response.status_code, 200)
        self.assertIn("attachment", response["Content-Disposition"])

    def test_reset_one_or_all(self):
        with mock.patch.object(views.probe, "forget") as forget:
            self.call(views.AdminProbeResetView, "post", who=user(level=10), body={"id": 4})
            self.call(views.AdminProbeResetView, "post", who=user(level=10), body={})
            bad = self.call(views.AdminProbeResetView, "post", who=user(level=10), body={"id": "x"})
        self.assertEqual([c.args for c in forget.call_args_list], [(4,), (None,)])
        self.assertEqual(bad.status_code, 400)

    def test_the_page_is_static_and_off_with_the_plugin(self):
        response = self.call(views.UiView)
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Admins only", response.content)
        self.assertIn("frame-ancestors", response["Content-Security-Policy"])
        self.state.return_value = (False, {})
        self.assertEqual(self.call(views.UiView).status_code, 404)

    def test_a_tv_short_of_a_connection_makes_the_probes_yield(self):
        with mock.patch.object(views.data, "all_sessions", return_value=[]), \
                mock.patch.object(views.data, "redis_client", return_value=object()), \
                mock.patch.object(views.probe, "ask_to_yield") as yield_:
            self.call(views.ReleaseSessionsView, "post", who=user(), body={"this_device": True})
        yield_.assert_called_once()


class ChannelJsonTest(unittest.TestCase):
    def row(self, **override):
        row = {
            "id": 5, "uuid": "u-1", "name": "TF1 FHD", "channel_number": 1.0, "channel_group_id": 2,
            "channel_group__name": "TNT", "logo_id": 9, "tvg_id": "tf1.fr", "epg_data_id": 4,
            "epg_data__tvg_id": "TF1.fr", "is_radio": False, "is_catchup": True, "catchup_days": 7,
        }
        for field in ("name", "channel_number", "channel_group_id", "channel_group__name", "logo_id", "tvg_id",
                      "epg_data_id", "epg_data__tvg_id", "is_radio"):
            row[f"override__{field}"] = override.get(field)
        return row

    def test_plain_channel(self):
        data = views.data.channel_json(self.row())
        self.assertEqual(data["number"], 1)
        self.assertEqual(data["group"], {"id": 2, "name": "TNT"})
        self.assertEqual(data["tvg_id"], "TF1.fr")
        self.assertEqual(data["catchup_days"], 7)

    def test_override(self):
        data = views.data.channel_json(self.row(name="TF1", channel_group_id=3, channel_group__name="Favoris",
                                                epg_data_id=8, epg_data__tvg_id=None, channel_number=101.0))
        self.assertEqual(data["name"], "TF1")
        self.assertEqual(data["group"], {"id": 3, "name": "Favoris"})
        self.assertEqual(data["epg_data_id"], 8)
        # Overridden EPG data without tvg_id: the tvg_id of the channel, not the one of the replaced EPG data
        self.assertEqual(data["tvg_id"], "tf1.fr")
        self.assertEqual(data["number"], 101)


if __name__ == "__main__":
    unittest.main()
