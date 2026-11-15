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

    def test_info(self):
        response = self.call(views.InfoView, who=user())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data["api"], core.API_VERSION)
        self.assertFalse(response.data["user"]["admin"])

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
