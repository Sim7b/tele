from django.urls import path

from . import views

urlpatterns = [
    path("v1/info/", views.InfoView.as_view(), name="info"),
    path("v1/channels/", views.ChannelsView.as_view(), name="channels"),
    path("v1/slots/", views.SlotsView.as_view(), name="slots"),
    path("v1/sessions/", views.SessionsView.as_view(), name="sessions"),
    path("v1/sessions/release/", views.ReleaseSessionsView.as_view(), name="sessions-release"),
]
