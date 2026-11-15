from django.urls import path

from . import views

urlpatterns = [
    path("v1/info/", views.InfoView.as_view(), name="info"),
    path("v1/channels/", views.ChannelsView.as_view(), name="channels"),
    path("v1/guide/", views.GuideView.as_view(), name="guide"),
    path("v1/slots/", views.SlotsView.as_view(), name="slots"),
    path("v1/sessions/", views.SessionsView.as_view(), name="sessions"),
    path("v1/sessions/release/", views.ReleaseSessionsView.as_view(), name="sessions-release"),
    path("v1/streams/health/", views.StreamHealthView.as_view(), name="streams-health"),
    path("v1/vod/languages/", views.VodLanguagesView.as_view(), name="vod-languages"),
    path("v1/vod/media/", views.VodMediaView.as_view(), name="vod-media"),
    path("v1/admin/devices/", views.AdminDevicesView.as_view(), name="admin-devices"),
    path("v1/admin/devices/release/", views.AdminDeviceReleaseView.as_view(), name="admin-devices-release"),
    path("v1/admin/stats/", views.AdminStatsView.as_view(), name="admin-stats"),
    path("v1/admin/probes/", views.AdminProbesView.as_view(), name="admin-probes"),
    path("v1/admin/probes/run/", views.AdminProbeRunView.as_view(), name="admin-probes-run"),
    path("v1/admin/probes/reset/", views.AdminProbeResetView.as_view(), name="admin-probes-reset"),
    path("v1/admin/probes/export/", views.AdminExportView.as_view(), name="admin-probes-export"),
    path("ui/", views.UiView.as_view(), name="ui"),
]
